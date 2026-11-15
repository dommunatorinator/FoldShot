package com.foldshot.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Manages user preferences for FoldShot across Samsung One UI, Xiaomi HyperOS/MIUI, and all Android devices:
 * - Controls Visible & Usable Folders Allowlist (optional strict folder filtering)
 * - Resolves the 3 Favorite Albums (respecting the Visible & Usable Folders allowlist)
 * - Stores dock position & vertical offset
 * - Stores thumb-reachable Screenshot Preview Scale (default 0.82f = 82%)
 * - Exports/imports & auto-backs up full Settings Profile JSON (folders + triggers + editor settings)
 *   so settings survive uninstall/reinstall APK updates.
 */
public class FoldShotPrefs {

    public static final String PROFILE_RELATIVE_DIR = Environment.DIRECTORY_DOCUMENTS + "/FoldShot/";
    public static final String PROFILE_FILE_NAME = "FoldShot_Profile.json";

    private static final String PREFS_NAME = "foldshot_zfold7_prefs";
    private static final String KEY_FOLDER_ALLOWLIST_ENABLED = "folder_allowlist_enabled";
    private static final String KEY_ALLOWED_FOLDERS = "allowed_folders_list";
    private static final String KEY_PROFILE_CONFIGURED = "profile_user_configured";
    private static final String KEY_AUTO_BACKUP_URI = "profile_auto_backup_uri";
    private static final String KEY_SAF_BACKUP_URI = "profile_saf_backup_uri";
    private static final String KEY_LAST_BACKUP_TS = "profile_last_backup_ts";
    private static final String KEY_VAULT_PIN_SALT = "vault_pin_salt";
    private static final String KEY_VAULT_PIN_HASH = "vault_pin_hash";
    private static final String KEY_VAULT_UNLOCK_SPOT = "vault_unlock_spot";
    private static final String KEY_STRIP_METADATA = "privacy_strip_metadata";
    private static final String KEY_CLIPBOARD_AUTO_CLEAR_SEC = "privacy_clipboard_clear_sec";
    private static final String KEY_AUTO_EXPIRE_FOLDER = "privacy_auto_expire_folder";
    private static final String KEY_AUTO_EXPIRE_TTL_HOURS = "privacy_auto_expire_ttl_hours";
    private static final String KEY_LAST_AUTO_EXPIRE_MS = "privacy_last_auto_expire_ms";
    private static final String KEY_ANONYMOUS_FILENAME = "privacy_anonymous_filename";
    private static final String KEY_SECURE_OVERLAY = "privacy_secure_overlay";
    private static final String KEY_CROP_ASPECT_RATIO = "crop_aspect_ratio";
    private static final int MAX_PROFILE_BYTES = 1024 * 1024;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService BACKUP_EXECUTOR = Executors.newSingleThreadExecutor();
    private static Runnable pendingBackupRunnable;

    private final Context context;
    private final SharedPreferences prefs;

    public FoldShotPrefs(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean hasUserConfiguredProfile() {
        return prefs.getBoolean(KEY_PROFILE_CONFIGURED, false);
    }

    private void markConfiguredAndBackup() {
        prefs.edit().putBoolean(KEY_PROFILE_CONFIGURED, true).apply();
        scheduleAutoProfileBackup();
    }

    public boolean isFolderAllowlistEnabled() {
        return prefs.getBoolean(KEY_FOLDER_ALLOWLIST_ENABLED, false);
    }

    public void setFolderAllowlistEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FOLDER_ALLOWLIST_ENABLED, enabled).apply();
        markConfiguredAndBackup();
    }

    public List<String> getAllowedFolders() {
        String raw = prefs.getString(KEY_ALLOWED_FOLDERS, "");
        List<String> list = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            String[] lines = raw.split("\n");
            for (String line : lines) {
                String trimmed = line != null ? line.trim() : "";
                if (trimmed.isEmpty()) continue;
                String lower = trimmed.toLowerCase(Locale.US);
                if (seen.add(lower)) {
                    list.add(trimmed);
                }
            }
        }
        return list;
    }

    public void setAllowedFolders(List<String> folders) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        if (folders != null) {
            for (String f : folders) {
                if (f == null) continue;
                String clean = f.replace('\n', ' ').replace('\r', ' ').trim();
                if (clean.isEmpty()) continue;
                String lower = clean.toLowerCase(Locale.US);
                if (seen.add(lower)) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(clean);
                }
            }
        }
        prefs.edit().putString(KEY_ALLOWED_FOLDERS, sb.toString()).apply();
        markConfiguredAndBackup();
    }

    public void addAllowedFolder(String folderName) {
        if (folderName == null) return;
        String clean = folderName.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.isEmpty()) return;
        List<String> current = getAllowedFolders();
        for (String existing : current) {
            if (existing.equalsIgnoreCase(clean)) {
                return;
            }
        }
        current.add(clean);
        setAllowedFolders(current);
    }

    public boolean isFolderUsable(String folderName) {
        if (folderName == null || folderName.trim().isEmpty()) {
            return false;
        }
        if (!isFolderAllowlistEnabled()) {
            return true;
        }
        String target = folderName.trim();
        for (String allowed : getAllowedFolders()) {
            if (allowed.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    public String[] resolveTopThreeAlbums() {
        if (isFolderAllowlistEnabled()) {
            return resolveTopThreeFromAllowlist();
        }

        String[] defaults = new String[]{"Receipts", "Work", "Saved"};
        List<MediaStoreHelper.GalleryAlbumInfo> discovered = MediaStoreHelper.queryDeviceGalleryAlbums(context);

        String[] result = new String[3];
        int discoveredIdx = 0;

        for (int i = 0; i < 3; i++) {
            String pinned = prefs.getString("pinned_album_" + i, null);
            if (pinned != null && !pinned.trim().isEmpty()) {
                result[i] = pinned.trim();
            } else {
                String candidate = null;
                while (discoveredIdx < discovered.size()) {
                    String name = discovered.get(discoveredIdx++).bucketName;
                    boolean alreadyUsed = false;
                    for (int j = 0; j < i; j++) {
                        if (name.equalsIgnoreCase(result[j])) {
                            alreadyUsed = true;
                            break;
                        }
                    }
                    if (!alreadyUsed) {
                        candidate = name;
                        break;
                    }
                }
                result[i] = candidate != null ? candidate : defaults[i];
            }
        }
        return result;
    }

    private String[] resolveTopThreeFromAllowlist() {
        List<String> allowed = getAllowedFolders();
        String[] result = new String[]{"", "", ""};
        Set<String> usedLower = new LinkedHashSet<>();

        for (int i = 0; i < 3; i++) {
            String pinned = prefs.getString("pinned_album_" + i, null);
            if (pinned != null && !pinned.trim().isEmpty()) {
                String clean = pinned.trim();
                String lower = clean.toLowerCase(Locale.US);
                if (isFolderUsable(clean) && !usedLower.contains(lower)) {
                    result[i] = clean;
                    usedLower.add(lower);
                }
            }
        }

        int allowedIdx = 0;
        for (int i = 0; i < 3; i++) {
            if (!result[i].isEmpty()) continue;
            while (allowedIdx < allowed.size()) {
                String candidate = allowed.get(allowedIdx++);
                String lower = candidate.toLowerCase(Locale.US);
                if (!usedLower.contains(lower)) {
                    result[i] = candidate;
                    usedLower.add(lower);
                    break;
                }
            }
        }
        return result;
    }

    public void setPinnedAlbum(int index, String name) {
        if (name == null || name.trim().isEmpty()) {
            prefs.edit().remove("pinned_album_" + index).apply();
        } else {
            prefs.edit().putString("pinned_album_" + index, name.trim()).apply();
        }
        markConfiguredAndBackup();
    }

    public void pruneDisallowedPinnedAlbums() {
        if (!isFolderAllowlistEnabled()) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < 3; i++) {
            String pinned = prefs.getString("pinned_album_" + i, null);
            if (pinned != null && !isFolderUsable(pinned)) {
                editor.remove("pinned_album_" + i);
            }
        }
        editor.apply();
        markConfiguredAndBackup();
    }

    public String getCropMode() {
        return prefs.getString("crop_mode", "auto");
    }

    public void setCropMode(String mode) {
        prefs.edit().putString("crop_mode", mode).apply();
        markConfiguredAndBackup();
    }

    public boolean isLoupeEnabled() {
        return prefs.getBoolean("show_loupe", true);
    }

    public void setLoupeEnabled(boolean enabled) {
        prefs.edit().putBoolean("show_loupe", enabled).apply();
        markConfiguredAndBackup();
    }

    public boolean isMagneticSnapEnabled() {
        return prefs.getBoolean("magnetic_snap", true);
    }

    public void setMagneticSnapEnabled(boolean enabled) {
        prefs.edit().putBoolean("magnetic_snap", enabled).apply();
        markConfiguredAndBackup();
    }

    /**
     * Thumb-reachable scale factor for the screenshot preview area inside the full-height overlay.
     * Defaults to 0.82f (82% of the safe area between top bar and bottom dock) so all corners are easy to reach.
     */
    public float getPreviewScale() {
        return prefs.getFloat("preview_scale", 0.82f);
    }

    public void setPreviewScale(float scale) {
        float clamped = Math.max(0.65f, Math.min(0.94f, scale));
        prefs.edit().putFloat("preview_scale", clamped).apply();
        markConfiguredAndBackup();
    }

    /**
     * Distance in dp from the bottom navigation/gesture bar to place FoldShot's floating dock.
     */
    public float getDockBottomClearanceDp() {
        return prefs.getFloat("dock_bottom_clearance_dp", 18f);
    }

    public void setDockBottomClearanceDp(float clearanceDp) {
        float clamped = Math.max(8f, Math.min(420f, clearanceDp));
        prefs.edit().putFloat("dock_bottom_clearance_dp", clamped).apply();
        markConfiguredAndBackup();
    }

    /**
     * Optional auto-open trigger for images saved/downloaded from Facebook Messenger (OFF by default).
     */
    public boolean isTriggerOnMessengerEnabled() {
        return prefs.getBoolean("trigger_on_messenger", false);
    }

    public void setTriggerOnMessengerEnabled(boolean enabled) {
        prefs.edit().putBoolean("trigger_on_messenger", enabled).apply();
        markConfiguredAndBackup();
    }

    /**
     * Optional auto-open trigger for images downloaded from Chrome or mobile browsers (OFF by default).
     */
    public boolean isTriggerOnBrowserDownloadsEnabled() {
        return prefs.getBoolean("trigger_on_browser_downloads", false);
    }

    public void setTriggerOnBrowserDownloadsEnabled(boolean enabled) {
        prefs.edit().putBoolean("trigger_on_browser_downloads", enabled).apply();
        markConfiguredAndBackup();
    }

    /**
     * Option to hide the persistent FoldShot tray notification (OFF by default).
     */
    public boolean isHideTrayNotificationEnabled() {
        return prefs.getBoolean("hide_tray_notification", false);
    }

    public void setHideTrayNotificationEnabled(boolean enabled) {
        prefs.edit().putBoolean("hide_tray_notification", enabled).apply();
        markConfiguredAndBackup();
    }

    public boolean hasVaultPin() {
        String salt = prefs.getString(KEY_VAULT_PIN_SALT, "");
        String hash = prefs.getString(KEY_VAULT_PIN_HASH, "");
        return salt != null && !salt.isEmpty() && hash != null && !hash.isEmpty();
    }

    public String getVaultPinSalt() {
        return prefs.getString(KEY_VAULT_PIN_SALT, "");
    }

    public String getVaultPinHash() {
        return prefs.getString(KEY_VAULT_PIN_HASH, "");
    }

    public void setVaultPinCredentials(String saltBase64, String hashBase64) {
        prefs.edit()
                .putString(KEY_VAULT_PIN_SALT, saltBase64 != null ? saltBase64 : "")
                .putString(KEY_VAULT_PIN_HASH, hashBase64 != null ? hashBase64 : "")
                .apply();
    }

    public String getVaultUnlockSpot() {
        String spot = prefs.getString(KEY_VAULT_UNLOCK_SPOT, "summer_cat");
        if ("summer_cat".equals(spot) || "title".equals(spot) || "subtitle".equals(spot)
                || "system_header".equals(spot) || "profile_header".equals(spot)) {
            return spot;
        }
        return "summer_cat";
    }

    public void setVaultUnlockSpot(String spot) {
        if (!"summer_cat".equals(spot) && !"title".equals(spot) && !"subtitle".equals(spot)
                && !"system_header".equals(spot) && !"profile_header".equals(spot)) {
            spot = "summer_cat";
        }
        prefs.edit().putString(KEY_VAULT_UNLOCK_SPOT, spot).apply();
        markConfiguredAndBackup();
    }

    public boolean isStripMetadataEnabled() {
        return prefs.getBoolean(KEY_STRIP_METADATA, true);
    }

    public void setStripMetadataEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_STRIP_METADATA, enabled).apply();
        markConfiguredAndBackup();
    }

    public int getClipboardAutoClearSec() {
        int val = prefs.getInt(KEY_CLIPBOARD_AUTO_CLEAR_SEC, 0);
        if (val == 30 || val == 120 || val == 300) return val;
        return 0;
    }

    public void setClipboardAutoClearSec(int seconds) {
        int clean = (seconds == 30 || seconds == 120 || seconds == 300) ? seconds : 0;
        prefs.edit().putInt(KEY_CLIPBOARD_AUTO_CLEAR_SEC, clean).apply();
        markConfiguredAndBackup();
    }

    public String getAutoExpireFolderName() {
        String folder = prefs.getString(KEY_AUTO_EXPIRE_FOLDER, "Temp");
        return (folder == null || folder.trim().isEmpty()) ? "Temp" : folder.trim();
    }

    public void setAutoExpireFolderName(String folderName) {
        String clean = (folderName == null || folderName.trim().isEmpty()) ? "Temp" : folderName.trim();
        prefs.edit().putString(KEY_AUTO_EXPIRE_FOLDER, clean).apply();
        markConfiguredAndBackup();
    }

    public int getAutoExpireTtlHours() {
        int val = prefs.getInt(KEY_AUTO_EXPIRE_TTL_HOURS, 0);
        if (val == 24 || val == 72 || val == 168) return val;
        return 0;
    }

    public void setAutoExpireTtlHours(int hours) {
        int clean = (hours == 24 || hours == 72 || hours == 168) ? hours : 0;
        prefs.edit().putInt(KEY_AUTO_EXPIRE_TTL_HOURS, clean).apply();
        markConfiguredAndBackup();
    }

    public long getLastAutoExpireRunMs() {
        return prefs.getLong(KEY_LAST_AUTO_EXPIRE_MS, 0L);
    }

    public void setLastAutoExpireRunMs(long tsMs) {
        prefs.edit().putLong(KEY_LAST_AUTO_EXPIRE_MS, tsMs).apply();
    }

    public boolean isAnonymousFilenameEnabled() {
        return prefs.getBoolean(KEY_ANONYMOUS_FILENAME, false);
    }

    public void setAnonymousFilenameEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ANONYMOUS_FILENAME, enabled).apply();
        markConfiguredAndBackup();
    }

    public boolean isSecureOverlayEnabled() {
        return prefs.getBoolean(KEY_SECURE_OVERLAY, false);
    }

    public void setSecureOverlayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SECURE_OVERLAY, enabled).apply();
        markConfiguredAndBackup();
    }

    public String getCropAspectRatio() {
        String r = prefs.getString(KEY_CROP_ASPECT_RATIO, "free");
        if ("1:1".equals(r) || "4:5".equals(r) || "16:9".equals(r)) return r;
        return "free";
    }

    public void setCropAspectRatio(String ratio) {
        String clean = ("1:1".equals(ratio) || "4:5".equals(ratio) || "16:9".equals(ratio)) ? ratio : "free";
        prefs.edit().putString(KEY_CROP_ASPECT_RATIO, clean).apply();
        markConfiguredAndBackup();
    }

    public long getLastBackupTimestampMs() {
        return prefs.getLong(KEY_LAST_BACKUP_TS, 0L);
    }

    public Uri getSafBackupUri() {
        String raw = prefs.getString(KEY_SAF_BACKUP_URI, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Uri.parse(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void setSafBackupUri(Uri uri) {
        if (uri == null) {
            prefs.edit().remove(KEY_SAF_BACKUP_URI).apply();
        } else {
            prefs.edit().putString(KEY_SAF_BACKUP_URI, uri.toString()).apply();
        }
    }

    public String getProfileSummaryLine() {
        List<String> allowed = getAllowedFolders();
        String[] top3 = resolveTopThreeAlbums();
        StringBuilder topSb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (top3[i] != null && !top3[i].trim().isEmpty()) {
                if (topSb.length() > 0) topSb.append(", ");
                topSb.append(top3[i].trim());
            }
        }
        String foldersPart = isFolderAllowlistEnabled()
                ? (allowed.size() + " allowed folders")
                : "All folders";
        if (topSb.length() > 0) {
            foldersPart += " • Top 3: " + topSb;
        }
        int scalePct = Math.round(getPreviewScale() * 100f);
        return foldersPart + " • " + getCropMode() + " crop • " + scalePct + "% scale";
    }

    public String exportProfileJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("schema_version", 2);
            root.put("app", "FoldShot");
            root.put("updated_at_ms", System.currentTimeMillis());

            JSONObject folders = new JSONObject();
            folders.put("allowlist_enabled", isFolderAllowlistEnabled());
            JSONArray allowedArr = new JSONArray();
            for (String folder : getAllowedFolders()) {
                allowedArr.put(folder);
            }
            folders.put("allowed_folders", allowedArr);

            JSONArray pinnedArr = new JSONArray();
            for (int i = 0; i < 3; i++) {
                String pinned = prefs.getString("pinned_album_" + i, "");
                pinnedArr.put(pinned != null ? pinned.trim() : "");
            }
            folders.put("pinned_albums", pinnedArr);
            root.put("folders", folders);

            JSONObject triggers = new JSONObject();
            triggers.put("trigger_on_messenger", isTriggerOnMessengerEnabled());
            triggers.put("trigger_on_browser_downloads", isTriggerOnBrowserDownloadsEnabled());
            triggers.put("hide_tray_notification", isHideTrayNotificationEnabled());
            root.put("triggers", triggers);

            JSONObject editor = new JSONObject();
            editor.put("crop_mode", getCropMode());
            editor.put("crop_aspect_ratio", getCropAspectRatio());
            editor.put("show_loupe", isLoupeEnabled());
            editor.put("magnetic_snap", isMagneticSnapEnabled());
            editor.put("preview_scale", (double) getPreviewScale());
            editor.put("dock_bottom_clearance_dp", (double) getDockBottomClearanceDp());
            root.put("editor", editor);

            JSONObject privacy = new JSONObject();
            privacy.put("vault_unlock_spot", getVaultUnlockSpot());
            privacy.put("strip_metadata", isStripMetadataEnabled());
            privacy.put("clipboard_auto_clear_sec", getClipboardAutoClearSec());
            privacy.put("auto_expire_folder", getAutoExpireFolderName());
            privacy.put("auto_expire_ttl_hours", getAutoExpireTtlHours());
            privacy.put("anonymous_filename", isAnonymousFilenameEnabled());
            privacy.put("secure_overlay", isSecureOverlayEnabled());
            root.put("privacy", privacy);

            return root.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    public boolean importProfileJson(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(rawJson.trim());
            boolean hasAnySection = root.has("folders") || root.has("triggers") || root.has("editor") || root.has("privacy");
            if (!hasAnySection) return false;

            SharedPreferences.Editor ed = prefs.edit();

            JSONObject folders = root.optJSONObject("folders");
            if (folders != null) {
                if (folders.has("allowlist_enabled")) {
                    ed.putBoolean(KEY_FOLDER_ALLOWLIST_ENABLED, folders.optBoolean("allowlist_enabled", false));
                }
                JSONArray allowedArr = folders.optJSONArray("allowed_folders");
                if (allowedArr != null) {
                    StringBuilder sb = new StringBuilder();
                    Set<String> seen = new LinkedHashSet<>();
                    for (int i = 0; i < allowedArr.length(); i++) {
                        String val = allowedArr.optString(i, "").replace('\n', ' ').replace('\r', ' ').trim();
                        if (!val.isEmpty() && seen.add(val.toLowerCase(Locale.US))) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append(val);
                        }
                    }
                    ed.putString(KEY_ALLOWED_FOLDERS, sb.toString());
                }
                JSONArray pinnedArr = folders.optJSONArray("pinned_albums");
                if (pinnedArr != null) {
                    for (int i = 0; i < 3; i++) {
                        String slotVal = i < pinnedArr.length() ? pinnedArr.optString(i, "").trim() : "";
                        if (slotVal.isEmpty()) {
                            ed.remove("pinned_album_" + i);
                        } else {
                            ed.putString("pinned_album_" + i, slotVal);
                        }
                    }
                }
            }

            JSONObject triggers = root.optJSONObject("triggers");
            if (triggers != null) {
                if (triggers.has("trigger_on_messenger")) {
                    ed.putBoolean("trigger_on_messenger", triggers.optBoolean("trigger_on_messenger", false));
                }
                if (triggers.has("trigger_on_browser_downloads")) {
                    ed.putBoolean("trigger_on_browser_downloads", triggers.optBoolean("trigger_on_browser_downloads", false));
                }
                if (triggers.has("hide_tray_notification")) {
                    ed.putBoolean("hide_tray_notification", triggers.optBoolean("hide_tray_notification", false));
                }
            }

            JSONObject editorObj = root.optJSONObject("editor");
            if (editorObj != null) {
                if (editorObj.has("crop_mode")) {
                    String mode = editorObj.optString("crop_mode", "auto");
                    if ("auto".equals(mode) || "point-drag".equals(mode)
                            || "center-expand".equals(mode) || "pinch-spread".equals(mode)) {
                        ed.putString("crop_mode", mode);
                    }
                }
                if (editorObj.has("crop_aspect_ratio")) {
                    String ratio = editorObj.optString("crop_aspect_ratio", "free");
                    if ("free".equals(ratio) || "1:1".equals(ratio) || "4:5".equals(ratio) || "16:9".equals(ratio)) {
                        ed.putString(KEY_CROP_ASPECT_RATIO, ratio);
                    }
                }
                if (editorObj.has("show_loupe")) {
                    ed.putBoolean("show_loupe", editorObj.optBoolean("show_loupe", true));
                }
                if (editorObj.has("magnetic_snap")) {
                    ed.putBoolean("magnetic_snap", editorObj.optBoolean("magnetic_snap", true));
                }
                if (editorObj.has("preview_scale")) {
                    float scale = (float) editorObj.optDouble("preview_scale", 0.82);
                    ed.putFloat("preview_scale", Math.max(0.65f, Math.min(0.94f, scale)));
                }
                if (editorObj.has("dock_bottom_clearance_dp")) {
                    float dock = (float) editorObj.optDouble("dock_bottom_clearance_dp", 18.0);
                    ed.putFloat("dock_bottom_clearance_dp", Math.max(8f, Math.min(420f, dock)));
                }
            }

            JSONObject privacyObj = root.optJSONObject("privacy");
            if (privacyObj != null) {
                if (privacyObj.has("vault_unlock_spot")) {
                    String spot = privacyObj.optString("vault_unlock_spot", "summer_cat");
                    if ("summer_cat".equals(spot) || "title".equals(spot) || "subtitle".equals(spot)
                            || "system_header".equals(spot) || "profile_header".equals(spot)) {
                        ed.putString(KEY_VAULT_UNLOCK_SPOT, spot);
                    }
                }
                if (privacyObj.has("strip_metadata")) {
                    ed.putBoolean(KEY_STRIP_METADATA, privacyObj.optBoolean("strip_metadata", true));
                }
                if (privacyObj.has("clipboard_auto_clear_sec")) {
                    int sec = privacyObj.optInt("clipboard_auto_clear_sec", 0);
                    ed.putInt(KEY_CLIPBOARD_AUTO_CLEAR_SEC, (sec == 30 || sec == 120 || sec == 300) ? sec : 0);
                }
                if (privacyObj.has("auto_expire_folder")) {
                    String folder = privacyObj.optString("auto_expire_folder", "Temp").trim();
                    if (!folder.isEmpty()) {
                        ed.putString(KEY_AUTO_EXPIRE_FOLDER, folder);
                    }
                }
                if (privacyObj.has("auto_expire_ttl_hours")) {
                    int hrs = privacyObj.optInt("auto_expire_ttl_hours", 0);
                    ed.putInt(KEY_AUTO_EXPIRE_TTL_HOURS, (hrs == 24 || hrs == 72 || hrs == 168) ? hrs : 0);
                }
                if (privacyObj.has("anonymous_filename")) {
                    ed.putBoolean(KEY_ANONYMOUS_FILENAME, privacyObj.optBoolean("anonymous_filename", false));
                }
                if (privacyObj.has("secure_overlay")) {
                    ed.putBoolean(KEY_SECURE_OVERLAY, privacyObj.optBoolean("secure_overlay", false));
                }
            }

            ed.putBoolean(KEY_PROFILE_CONFIGURED, true);
            ed.apply();
            scheduleAutoProfileBackup();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean exportProfileToUri(Uri targetUri) {
        if (targetUri == null) return false;
        String json = exportProfileJson();
        if (!writeTextToUri(context, targetUri, json)) {
            return false;
        }
        prefs.edit()
                .putString(KEY_SAF_BACKUP_URI, targetUri.toString())
                .putLong(KEY_LAST_BACKUP_TS, System.currentTimeMillis())
                .apply();
        scheduleAutoProfileBackup();
        return true;
    }

    public boolean importProfileFromUri(Uri sourceUri) {
        if (sourceUri == null) return false;
        String text = readTextFromUri(context, sourceUri);
        if (text == null) return false;
        return importProfileJson(text);
    }

    public void scheduleAutoProfileBackup() {
        synchronized (FoldShotPrefs.class) {
            if (pendingBackupRunnable != null) {
                MAIN_HANDLER.removeCallbacks(pendingBackupRunnable);
            }
            pendingBackupRunnable = () -> BACKUP_EXECUTOR.execute(this::syncAutoProfileBackupNow);
            MAIN_HANDLER.postDelayed(pendingBackupRunnable, 500L);
        }
    }

    public boolean syncAutoProfileBackupNow() {
        if (!hasUserConfiguredProfile()) return false;
        String json = exportProfileJson();
        boolean wroteAny = false;

        Uri safUri = getSafBackupUri();
        if (safUri != null) {
            if (writeTextToUri(context, safUri, json)) {
                wroteAny = true;
            }
        }

        if (writeProfileToMediaStoreDocuments(json)) {
            wroteAny = true;
        }

        if (wroteAny) {
            prefs.edit().putLong(KEY_LAST_BACKUP_TS, System.currentTimeMillis()).apply();
        }
        return wroteAny;
    }

    /**
     * Automatically checks Documents/FoldShot/FoldShot_Profile.json on a brand-new install
     * and silently restores settings if readable by MediaStore.
     */
    public boolean tryAutoRestoreFromDocuments() {
        if (hasUserConfiguredProfile()) return false;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri filesUri = MediaStore.Files.getContentUri("external");
            String[] projection = new String[]{
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DATE_MODIFIED
            };
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND "
                    + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
            String[] args = new String[]{"FoldShot_Profile%", "%FoldShot%"};
            String sort = MediaStore.MediaColumns.DATE_MODIFIED + " DESC";

            try (Cursor c = resolver.query(filesUri, projection, selection, args, sort)) {
                if (c != null) {
                    int idCol = c.getColumnIndex(MediaStore.Files.FileColumns._ID);
                    while (c.moveToNext()) {
                        if (idCol < 0) continue;
                        long id = c.getLong(idCol);
                        Uri rowUri = Uri.withAppendedPath(filesUri, String.valueOf(id));
                        String text = readTextFromUri(context, rowUri);
                        if (text != null && importProfileJson(text)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean writeProfileToMediaStoreDocuments(String json) {
        ContentResolver resolver = context.getContentResolver();
        String existingRaw = prefs.getString(KEY_AUTO_BACKUP_URI, null);
        if (existingRaw != null && !existingRaw.isEmpty()) {
            try {
                Uri existingUri = Uri.parse(existingRaw);
                if (writeTextToUri(context, existingUri, json)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        Uri filesUri = MediaStore.Files.getContentUri("external");
        try (Cursor c = resolver.query(
                filesUri,
                new String[]{MediaStore.Files.FileColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                new String[]{PROFILE_FILE_NAME, "%FoldShot%"},
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC"
        )) {
            if (c != null && c.moveToFirst()) {
                int idCol = c.getColumnIndex(MediaStore.Files.FileColumns._ID);
                if (idCol >= 0) {
                    Uri rowUri = Uri.withAppendedPath(filesUri, String.valueOf(c.getLong(idCol)));
                    if (writeTextToUri(context, rowUri, json)) {
                        prefs.edit().putString(KEY_AUTO_BACKUP_URI, rowUri.toString()).apply();
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        String[] mimes = new String[]{"application/json", "text/plain"};
        for (String mime : mimes) {
            Uri inserted = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, PROFILE_FILE_NAME);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, PROFILE_RELATIVE_DIR);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                inserted = resolver.insert(filesUri, values);
                if (inserted == null) continue;

                if (!writeTextToUri(context, inserted, json)) {
                    resolver.delete(inserted, null, null);
                    continue;
                }

                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(inserted, done, null, null);
                prefs.edit().putString(KEY_AUTO_BACKUP_URI, inserted.toString()).apply();
                return true;
            } catch (Exception e) {
                if (inserted != null) {
                    try {
                        resolver.delete(inserted, null, null);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return false;
    }

    private static boolean writeTextToUri(Context ctx, Uri uri, String text) {
        ContentResolver resolver = ctx.getContentResolver();
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        String[] modes = new String[]{"wt", "w", ""};
        for (String mode : modes) {
            try (OutputStream out = mode.isEmpty()
                    ? resolver.openOutputStream(uri)
                    : resolver.openOutputStream(uri, mode)) {
                if (out == null) continue;
                out.write(data);
                out.flush();
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static String readTextFromUri(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int len;
            while ((len = in.read(buf)) != -1) {
                total += len;
                if (total > MAX_PROFILE_BYTES) {
                    return null;
                }
                bos.write(buf, 0, len);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }
}
