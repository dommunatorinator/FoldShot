package com.foldshot.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Manages user preferences for FoldShot across Samsung One UI, Xiaomi HyperOS/MIUI, and all Android devices:
 * - Controls Visible & Usable Folders Allowlist (optional strict folder filtering)
 * - Resolves the 3 Favorite Albums (respecting the Visible & Usable Folders allowlist)
 * - Stores dock position & vertical offset
 * - Stores thumb-reachable Screenshot Preview Scale (default 0.82f = 82%)
 */
public class FoldShotPrefs {

    private static final String PREFS_NAME = "foldshot_zfold7_prefs";
    private static final String KEY_FOLDER_ALLOWLIST_ENABLED = "folder_allowlist_enabled";
    private static final String KEY_ALLOWED_FOLDERS = "allowed_folders_list";

    private final Context context;
    private final SharedPreferences prefs;

    public FoldShotPrefs(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isFolderAllowlistEnabled() {
        return prefs.getBoolean(KEY_FOLDER_ALLOWLIST_ENABLED, false);
    }

    public void setFolderAllowlistEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FOLDER_ALLOWLIST_ENABLED, enabled).apply();
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
    }

    public String getCropMode() {
        return prefs.getString("crop_mode", "auto");
    }

    public void setCropMode(String mode) {
        prefs.edit().putString("crop_mode", mode).apply();
    }

    public boolean isLoupeEnabled() {
        return prefs.getBoolean("show_loupe", true);
    }

    public void setLoupeEnabled(boolean enabled) {
        prefs.edit().putBoolean("show_loupe", enabled).apply();
    }

    public boolean isMagneticSnapEnabled() {
        return prefs.getBoolean("magnetic_snap", true);
    }

    public void setMagneticSnapEnabled(boolean enabled) {
        prefs.edit().putBoolean("magnetic_snap", enabled).apply();
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
    }

    /**
     * Optional auto-open trigger for images saved/downloaded from Facebook Messenger (OFF by default).
     */
    public boolean isTriggerOnMessengerEnabled() {
        return prefs.getBoolean("trigger_on_messenger", false);
    }

    public void setTriggerOnMessengerEnabled(boolean enabled) {
        prefs.edit().putBoolean("trigger_on_messenger", enabled).apply();
    }

    /**
     * Optional auto-open trigger for images downloaded from Chrome or mobile browsers (OFF by default).
     */
    public boolean isTriggerOnBrowserDownloadsEnabled() {
        return prefs.getBoolean("trigger_on_browser_downloads", false);
    }

    public void setTriggerOnBrowserDownloadsEnabled(boolean enabled) {
        prefs.edit().putBoolean("trigger_on_browser_downloads", enabled).apply();
    }
}
