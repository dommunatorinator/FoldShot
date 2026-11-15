package com.foldshot.app;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Universal Play-Protect-Safe MediaStore, Multi-Volume FileObserver, Display Metrics & OEM Helper.
 *
 * Designed to encompass nearly every Android OEM (Android 10 - 16):
 * - Samsung (One UI / Z Fold / Flip / S / A)
 * - Xiaomi / Redmi / POCO (MIUI / HyperOS)
 * - Transsion: Infinix / Tecno / itel (XOS / HiOS)
 * - BBK: OPPO / Realme / OnePlus (ColorOS / Realme UI / OxygenOS)
 * - Vivo / iQOO (FuntouchOS / OriginOS)
 * - Huawei / Honor (EMUI / MagicOS)
 * - Google Pixel, Motorola, Nothing, Sony Xperia, Asus, Lenovo
 */
public class MediaStoreHelper {

    public static final int REQ_DELETE_SCREENSHOT = 901;
    public static final int MAX_DECODE_SIDE_PX = 2560;
    public static final int MAX_STITCH_HEIGHT = 6144;

    private static final Set<String> OWN_SAVED_KEYS = Collections.synchronizedSet(new LinkedHashSet<>());

    public static void recordOwnSavedOutput(Uri uri, String displayName) {
        if (OWN_SAVED_KEYS.size() > 80) {
            OWN_SAVED_KEYS.clear();
        }
        if (uri != null) {
            OWN_SAVED_KEYS.add(uri.toString());
        }
        if (displayName != null && !displayName.isEmpty()) {
            OWN_SAVED_KEYS.add(displayName.toLowerCase(Locale.US));
            String noExt = displayName;
            int dot = noExt.lastIndexOf('.');
            if (dot > 0) noExt = noExt.substring(0, dot);
            OWN_SAVED_KEYS.add(noExt.toLowerCase(Locale.US));
        }
    }

    public static boolean isOwnSavedOutput(Uri uri, String displayName) {
        if (uri != null && OWN_SAVED_KEYS.contains(uri.toString())) {
            return true;
        }
        if (displayName != null && !displayName.isEmpty()) {
            String lower = displayName.toLowerCase(Locale.US);
            if (OWN_SAVED_KEYS.contains(lower)) return true;
            int dot = lower.lastIndexOf('.');
            if (dot > 0 && OWN_SAVED_KEYS.contains(lower.substring(0, dot))) return true;
        }
        return false;
    }

    public enum PhotoAccessState {
        ALL,
        LIMITED,
        NONE
    }

    public static class ScreenGeometry {
        public final int screenWidthPx;
        public final int screenHeightPx;
        public final int statusBarHeightPx;
        public final int navBarBottomInsetPx;

        public ScreenGeometry(int screenWidthPx, int screenHeightPx, int statusBarHeightPx, int navBarBottomInsetPx) {
            this.screenWidthPx = Math.max(320, screenWidthPx);
            this.screenHeightPx = Math.max(480, screenHeightPx);
            this.statusBarHeightPx = Math.max(0, statusBarHeightPx);
            this.navBarBottomInsetPx = Math.max(0, navBarBottomInsetPx);
        }
    }

    public static class ScreenshotData {
        public final Uri uri;
        public final String filePath;
        public final String displayName;
        public final long dateModifiedSec;
        public final int width;
        public final int height;
        public final boolean isLongCapture;

        public ScreenshotData(Uri uri, String filePath, String displayName, long dateModifiedSec, int width, int height) {
            this.uri = uri;
            this.filePath = filePath;
            this.displayName = displayName;
            this.dateModifiedSec = dateModifiedSec;
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.isLongCapture = this.height > (this.width * 2.3f);
        }
    }

    public static class GalleryAlbumInfo {
        public final String bucketName;
        public final String relativePath;
        public int itemCount;

        public GalleryAlbumInfo(String bucketName, String relativePath, int itemCount) {
            this.bucketName = bucketName;
            this.relativePath = relativePath;
            this.itemCount = itemCount;
        }
    }

    public static long normalizeTimestampSeconds(long rawTs) {
        if (rawTs <= 0L) return 0L;
        if (rawTs > 100_000_000_000L) {
            return rawTs / 1000L;
        }
        return rawTs;
    }

    public static PhotoAccessState getPhotoAccessState(Context context) {
        if (Build.VERSION.SDK_INT >= 34) {
            boolean all = context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            if (all) return PhotoAccessState.ALL;
            boolean limited = context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
            return limited ? PhotoAccessState.LIMITED : PhotoAccessState.NONE;
        }
        if (Build.VERSION.SDK_INT == 33) {
            boolean all = context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            return all ? PhotoAccessState.ALL : PhotoAccessState.NONE;
        }
        boolean legacy = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return legacy ? PhotoAccessState.ALL : PhotoAccessState.NONE;
    }

    public static Display findActiveDisplay(Context context) {
        if (context == null) return null;
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return null;
            Display defaultDisp = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (defaultDisp != null && defaultDisp.getState() == Display.STATE_ON) {
                return defaultDisp;
            }
            Display[] displays = dm.getDisplays();
            if (displays != null) {
                for (Display d : displays) {
                    if (d != null && d.getState() == Display.STATE_ON) {
                        return d;
                    }
                }
                if (defaultDisp != null) return defaultDisp;
                if (displays.length > 0) return displays[0];
            }
            return defaultDisp;
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    public static List<int[]> getAllDisplayDimensions(Context context) {
        List<int[]> dims = new ArrayList<>();
        if (context != null) {
            try {
                DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    Display[] displays = dm.getDisplays();
                    if (displays != null) {
                        for (Display d : displays) {
                            if (d == null) continue;
                            DisplayMetrics metrics = new DisplayMetrics();
                            d.getRealMetrics(metrics);
                            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                                int minSide = Math.min(metrics.widthPixels, metrics.heightPixels);
                                int maxSide = Math.max(metrics.widthPixels, metrics.heightPixels);
                                dims.add(new int[]{minSide, maxSide});
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (dims.isEmpty() && context != null && context.getResources() != null) {
            DisplayMetrics fallback = context.getResources().getDisplayMetrics();
            dims.add(new int[]{
                    Math.min(fallback.widthPixels, fallback.heightPixels),
                    Math.max(fallback.widthPixels, fallback.heightPixels)
            });
        }
        return dims;
    }

    @SuppressWarnings("deprecation")
    public static ScreenGeometry getScreenGeometry(Context context, WindowManager wm) {
        Resources res = context.getResources();
        float dp = res.getDisplayMetrics().density;

        int statusResHeight = 0;
        int resId = res.getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            statusResHeight = res.getDimensionPixelSize(resId);
        }
        if (statusResHeight <= 0) {
            statusResHeight = Math.round(28f * dp);
        }

        if (Build.VERSION.SDK_INT >= 30 && wm != null) {
            try {
                WindowMetrics metrics = wm.getMaximumWindowMetrics();
                Rect bounds = metrics.getBounds();
                WindowInsets wi = metrics.getWindowInsets();
                Insets statusInsets = wi.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()
                );
                Insets navInsets = wi.getInsetsIgnoringVisibility(
                        WindowInsets.Type.navigationBars()
                );
                int topPx = Math.max(statusInsets.top, statusResHeight);
                int bottomPx = Math.max(navInsets.bottom, Math.round(16f * dp));
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return new ScreenGeometry(bounds.width(), bounds.height(), topPx, bottomPx);
                }
            } catch (Exception ignored) {
            }
        }

        DisplayMetrics dm = new DisplayMetrics();
        try {
            Display activeDisp = findActiveDisplay(context);
            if (activeDisp != null) {
                activeDisp.getRealMetrics(dm);
            } else if (wm != null && wm.getDefaultDisplay() != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
            } else {
                dm = res.getDisplayMetrics();
            }
        } catch (Exception e) {
            dm = res.getDisplayMetrics();
        }
        return new ScreenGeometry(dm.widthPixels, dm.heightPixels, statusResHeight, Math.round(24f * dp));
    }

    private static String mfgBrand() {
        String m = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase(Locale.US) : "";
        String b = Build.BRAND != null ? Build.BRAND.toLowerCase(Locale.US) : "";
        String model = Build.MODEL != null ? Build.MODEL.toLowerCase(Locale.US) : "";
        return m + " " + b + " " + model;
    }

    public static boolean isSamsungDevice() {
        return mfgBrand().contains("samsung");
    }

    public static boolean isXiaomiDevice() {
        String s = mfgBrand();
        return s.contains("xiaomi") || s.contains("redmi") || s.contains("poco");
    }

    public static boolean isTranssionDevice() {
        String s = mfgBrand();
        return s.contains("infinix") || s.contains("tecno") || s.contains("itel") || s.contains("transsion") || s.contains("x6851");
    }

    public static boolean isColorOsFamilyDevice() {
        String s = mfgBrand();
        return s.contains("oppo") || s.contains("realme") || s.contains("oneplus");
    }

    public static boolean isVivoDevice() {
        String s = mfgBrand();
        return s.contains("vivo") || s.contains("iqoo");
    }

    public static boolean isHuaweiHonorDevice() {
        String s = mfgBrand();
        return s.contains("huawei") || s.contains("honor");
    }

    public static String getDeviceFriendlyName() {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.trim() : "";
        String model = Build.MODEL != null ? Build.MODEL.trim() : "Android";
        if (model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))) {
            return capitalizeWords(model);
        }
        return capitalizeWords(manufacturer + " " + model).trim();
    }

    private static String capitalizeWords(String s) {
        if (s == null || s.isEmpty()) return "Android Device";
        String[] parts = s.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            if (p.length() <= 5 && p.equals(p.toUpperCase(Locale.US))) {
                sb.append(p);
            } else {
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
        }
        return sb.toString();
    }

    public static boolean hasUnrestrictedBattery(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                return pm.isIgnoringBatteryOptimizations(context.getPackageName());
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static void requestUnrestrictedBattery(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName())
            );
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    public static String getOemHelperTitle() {
        if (isTranssionDevice()) return "Infinix / Tecno XOS Background & Battery";
        if (isXiaomiDevice()) return "Xiaomi / Redmi / POCO Background & Pop-Up";
        if (isColorOsFamilyDevice()) return "OPPO / Realme / OnePlus Battery & Startup";
        if (isVivoDevice()) return "Vivo / iQOO Background Power Usage";
        if (isHuaweiHonorDevice()) return "Huawei / Honor App Launch (Manage Manually)";
        if (isSamsungDevice()) return "Samsung Smart Capture & Background Service";
        return "Unrestricted Background Utility";
    }

    public static String getOemHelperSubtitle() {
        if (isTranssionDevice()) return "In App Info > Battery, set Unrestricted so screenshots always auto-open on XOS";
        if (isXiaomiDevice()) return "In App Info > Battery & Permissions, allow Pop-up windows & No restrictions";
        if (isColorOsFamilyDevice()) return "In App Info > Battery usage, enable Allow background activity";
        if (isVivoDevice()) return "In App Info > Battery, allow High background power usage";
        if (isHuaweiHonorDevice()) return "In App Info > Battery > App launch, set Manual & allow Run in background";
        if (isSamsungDevice()) return "Turn OFF 'Show toolbar after capturing' in Samsung Settings so only FoldShot shows";
        return "Set FoldShot Battery to Unrestricted so background screenshot detection never sleeps";
    }

    public static void openOemBackgroundSettings(Context context) {
        if (isSamsungDevice()) {
            try {
                Intent intent = new Intent();
                intent.setClassName("com.android.settings", "com.android.settings.Settings$SmartCaptureSettingsActivity");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            } catch (Exception ignored) {
            }
        }
        if (!hasUnrestrictedBattery(context)) {
            requestUnrestrictedBattery(context);
            return;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName())
            );
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    public static boolean hasAutoOpenOverlayPermission(Context context) {
        return Settings.canDrawOverlays(context);
    }

    public static void requestAutoOpenOverlayPermission(Context context) {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())
            );
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    public static boolean hasSilentDeletePermission(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            return MediaStore.canManageMedia(context);
        }
        return true;
    }

    public static void requestSilentDeletePermission(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_REQUEST_MANAGE_MEDIA,
                        Uri.parse("package:" + context.getPackageName())
                );
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName())
            );
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private static final long ALBUM_CACHE_TTL_MS = 10_000L;
    private static List<GalleryAlbumInfo> cachedGalleryAlbums = null;
    private static long cachedGalleryAlbumsTimeMs = 0L;

    public static void invalidateAlbumCache() {
        synchronized (MediaStoreHelper.class) {
            cachedGalleryAlbums = null;
            cachedGalleryAlbumsTimeMs = 0L;
        }
    }

    public static List<GalleryAlbumInfo> queryDeviceGalleryAlbums(Context context) {
        long nowMs = System.currentTimeMillis();
        synchronized (MediaStoreHelper.class) {
            if (cachedGalleryAlbums != null && (nowMs - cachedGalleryAlbumsTimeMs) < ALBUM_CACHE_TTL_MS) {
                List<GalleryAlbumInfo> copy = new ArrayList<>(cachedGalleryAlbums.size());
                for (GalleryAlbumInfo info : cachedGalleryAlbums) {
                    copy.add(new GalleryAlbumInfo(info.bucketName, info.relativePath, info.itemCount));
                }
                return copy;
            }
        }

        Map<String, GalleryAlbumInfo> map = new LinkedHashMap<>();
        ContentResolver resolver = context.getContentResolver();
        String[] projection = new String[]{
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
        };
        String sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " DESC";

        try (Cursor cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
        )) {
            if (cursor != null) {
                int bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                int pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH);

                while (cursor.moveToNext()) {
                    String bucket = bucketCol >= 0 ? cursor.getString(bucketCol) : null;
                    String relPath = pathCol >= 0 ? cursor.getString(pathCol) : null;
                    if (bucket == null || bucket.trim().isEmpty()) continue;
                    String lower = bucket.trim().toLowerCase(Locale.US);
                    if (lower.equals("screenshots") || lower.equals("screenshot") || lower.equals("camera") || lower.startsWith(".")) continue;

                    String key = bucket.trim();
                    String folder = (relPath != null && !relPath.isEmpty())
                            ? normalizeRelPath(relPath)
                            : ("DCIM/" + key + "/");
                    GalleryAlbumInfo existing = map.get(key);
                    if (existing == null) {
                        map.put(key, new GalleryAlbumInfo(key, folder, 1));
                    } else {
                        existing.itemCount++;
                        boolean existingIsDcim = existing.relativePath != null
                                && existing.relativePath.toLowerCase(Locale.US).startsWith("dcim/");
                        boolean folderIsDcim = folder.toLowerCase(Locale.US).startsWith("dcim/");
                        if (!existingIsDcim && folderIsDcim) {
                            int count = existing.itemCount;
                            map.put(key, new GalleryAlbumInfo(key, folder, count));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        List<GalleryAlbumInfo> fresh = new ArrayList<>(map.values());
        synchronized (MediaStoreHelper.class) {
            cachedGalleryAlbums = fresh;
            cachedGalleryAlbumsTimeMs = System.currentTimeMillis();
        }
        return new ArrayList<>(fresh);
    }

    /**
     * Collects all potential screenshot folders across internal storage AND any removable/secondary StorageVolumes.
     */
    @SuppressWarnings("deprecation")
    private static List<File> getStorageRoots(Context context) {
        List<File> roots = new ArrayList<>();
        File primary = Environment.getExternalStorageDirectory();
        if (primary != null) roots.add(primary);

        if (Build.VERSION.SDK_INT >= 30 && context != null) {
            try {
                StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
                if (sm != null) {
                    for (StorageVolume vol : sm.getStorageVolumes()) {
                        File d = vol.getDirectory();
                        if (d != null && !roots.contains(d)) {
                            roots.add(d);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return roots;
    }

    public static List<File> getCommonScreenshotDirs(Context context) {
        Set<File> dirs = new LinkedHashSet<>();
        String[] rels = new String[]{
                "Pictures/Screenshots",
                "DCIM/Screenshots",
                "Pictures/ScreenShot",
                "DCIM/ScreenShot",
                "Screenshots",
                "ScreenCapture",
                "Pictures/ScreenCapture",
                "ColorOS/Screenshots",
                "Oppo/Screenshots",
                "OnePlus/Screenshots"
        };

        for (File root : getStorageRoots(context)) {
            for (String r : rels) {
                dirs.add(new File(root, r));
            }
        }
        return new ArrayList<>(dirs);
    }

    public static List<File> getMessengerDirs(Context context) {
        Set<File> dirs = new LinkedHashSet<>();
        String[] rels = new String[]{
                "Pictures/Messenger",
                "DCIM/Messenger",
                "Pictures/Facebook Messenger",
                "Messenger"
        };
        for (File root : getStorageRoots(context)) {
            for (String r : rels) {
                dirs.add(new File(root, r));
            }
        }
        return new ArrayList<>(dirs);
    }

    public static List<File> getBrowserDownloadDirs(Context context) {
        Set<File> dirs = new LinkedHashSet<>();
        String[] rels = new String[]{
                "Download",
                "Downloads",
                "Download/Chrome",
                "Download/Browser",
                "Download/Samsung Internet",
                "Download/Firefox",
                "Download/Edge",
                "Download/Brave",
                "Download/Opera",
                "Pictures/Browser",
                "Pictures/Chrome"
        };
        for (File root : getStorageRoots(context)) {
            for (String r : rels) {
                dirs.add(new File(root, r));
            }
        }
        return new ArrayList<>(dirs);
    }

    public static List<File> getWatchedCaptureDirs(Context context, FoldShotPrefs prefs) {
        Set<File> dirs = new LinkedHashSet<>(getCommonScreenshotDirs(context));
        if (prefs != null) {
            if (prefs.isTriggerOnMessengerEnabled()) {
                dirs.addAll(getMessengerDirs(context));
            }
            if (prefs.isTriggerOnBrowserDownloadsEnabled()) {
                dirs.addAll(getBrowserDownloadDirs(context));
            }
        }
        return new ArrayList<>(dirs);
    }

    public static ScreenshotData findLatestScreenshotFileOnDisk(Context context, long minModifiedSec) {
        FoldShotPrefs prefs = new FoldShotPrefs(context);
        return findLatestScreenshotFileOnDisk(
                context,
                minModifiedSec,
                prefs.isTriggerOnMessengerEnabled(),
                prefs.isTriggerOnBrowserDownloadsEnabled()
        );
    }

    public static ScreenshotData findLatestScreenshotFileOnDisk(
            Context context,
            long minModifiedSec,
            boolean allowMessenger,
            boolean allowBrowserDownloads
    ) {
        File newestFile = null;
        long newestModMs = 0L;

        List<File> scanDirs = new ArrayList<>(getCommonScreenshotDirs(context));
        if (allowMessenger) {
            scanDirs.addAll(getMessengerDirs(context));
        }
        if (allowBrowserDownloads) {
            scanDirs.addAll(getBrowserDownloadDirs(context));
        }

        for (File dir : scanDirs) {
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || f.length() < 1024L) continue;
                String name = f.getName();
                if (name.startsWith(".") || name.startsWith("FoldShot_") || isOwnSavedOutput(null, name)) continue;
                String lower = name.toLowerCase(Locale.US);
                if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".webp")) {
                    continue;
                }
                long modMs = f.lastModified();
                if (modMs > newestModMs) {
                    newestModMs = modMs;
                    newestFile = f;
                }
            }
        }

        if (newestFile != null) {
            long modSec = newestModMs / 1000L;
            if (modSec >= minModifiedSec) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(newestFile.getAbsolutePath(), opts);
                Uri rawUri = Uri.fromFile(newestFile);
                Uri resolved = resolveContentUriForSource(context, rawUri);
                return new ScreenshotData(
                        resolved != null ? resolved : rawUri,
                        newestFile.getAbsolutePath(),
                        newestFile.getName(),
                        modSec,
                        Math.max(1, opts.outWidth),
                        Math.max(1, opts.outHeight)
                );
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    public static ScreenshotData queryLatestScreenshot(Context context, Uri hintUri) {
        ContentResolver resolver = context.getContentResolver();
        List<int[]> displayDims = getAllDisplayDimensions(context);
        long nowSec = System.currentTimeMillis() / 1000L;
        FoldShotPrefs prefs = new FoldShotPrefs(context);
        boolean allowMessenger = prefs.isTriggerOnMessengerEnabled();
        boolean allowBrowserDownloads = prefs.isTriggerOnBrowserDownloadsEnabled();

        List<String> projList = new ArrayList<>();
        projList.add(MediaStore.Images.Media._ID);
        projList.add(MediaStore.Images.Media.DISPLAY_NAME);
        projList.add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
        projList.add(MediaStore.Images.Media.DATE_ADDED);
        projList.add(MediaStore.Images.Media.DATE_MODIFIED);
        projList.add(MediaStore.Images.Media.WIDTH);
        projList.add(MediaStore.Images.Media.HEIGHT);
        projList.add(MediaStore.Images.Media.RELATIVE_PATH);
        projList.add(MediaStore.Images.Media.DATA);
        projList.add(MediaStore.Images.Media.IS_PENDING);
        if (Build.VERSION.SDK_INT >= 30) {
            projList.add(MediaStore.MediaColumns.IS_TRASHED);
        }
        String[] projection = projList.toArray(new String[0]);
        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC, " + MediaStore.Images.Media._ID + " DESC";

        if (hintUri != null && hintUri.getLastPathSegment() != null && hintUri.getLastPathSegment().matches("\\d+")) {
            try (Cursor c = resolver.query(hintUri, projection, null, null, null)) {
                ScreenshotData fromHint = extractScreenshotFromCursor(c, displayDims, nowSec, allowMessenger, allowBrowserDownloads);
                if (fromHint != null) return fromHint;
            } catch (Exception ignored) {
            }
        }

        try (Cursor cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
        )) {
            ScreenshotData data = extractScreenshotFromCursor(cursor, displayDims, nowSec, allowMessenger, allowBrowserDownloads);
            if (data != null) return data;
        } catch (Exception ignored) {
        }

        return findLatestScreenshotFileOnDisk(context, nowSec - 15L, allowMessenger, allowBrowserDownloads);
    }

    private static String extractParentDirLower(String dataPath) {
        if (dataPath == null || dataPath.isEmpty()) return "";
        int lastSlash = dataPath.lastIndexOf('/');
        if (lastSlash <= 0) return "";
        return dataPath.substring(0, lastSlash + 1).toLowerCase(Locale.US);
    }

    private static boolean isMessengerFolder(String lowerPath, String lowerBucket, String lowerDirData) {
        return lowerPath.contains("/messenger")
                || lowerPath.startsWith("messenger")
                || lowerBucket.equals("messenger")
                || lowerBucket.equals("facebook messenger")
                || lowerDirData.contains("/messenger/")
                || lowerDirData.contains("/facebook messenger/");
    }

    private static boolean isBrowserDownloadFolder(String lowerPath, String lowerBucket, String lowerDirData) {
        if (lowerPath.equals("download/")
                || lowerPath.equals("downloads/")
                || lowerPath.equals("download")
                || lowerPath.equals("downloads")) {
            return true;
        }
        String[] browserPaths = new String[]{
                "download/chrome",
                "download/browser",
                "download/samsung internet",
                "download/firefox",
                "download/edge",
                "download/brave",
                "download/opera",
                "pictures/chrome",
                "pictures/browser"
        };
        for (String bp : browserPaths) {
            if (lowerPath.contains(bp) || lowerDirData.contains("/" + bp + "/")) {
                return true;
            }
        }
        if (lowerPath.isEmpty() && (lowerDirData.endsWith("/download/") || lowerDirData.endsWith("/downloads/"))) {
            return true;
        }
        return (lowerBucket.equals("download") || lowerBucket.equals("downloads") || lowerBucket.equals("chrome"))
                && !lowerPath.contains("quick share")
                && !lowerPath.contains("nearby")
                && !lowerPath.contains("bluetooth");
    }

    private static boolean isAlwaysBlockedNonScreenshotLocation(String lowerPath, String lowerBucket, String lowerDirData, boolean isMessengerDir) {
        String combined = lowerPath + " | " + lowerBucket + " | " + lowerDirData;
        String[] blockedTokens = new String[]{
                "quick share",
                "quickshare",
                "nearby share",
                "nearby_share",
                "bluetooth",
                "shareit",
                "xender",
                "sendanywhere",
                "localsend",
                "whatsapp",
                "telegram",
                "instagram",
                "snapchat",
                "tiktok",
                "discord",
                "reddit",
                "twitter",
                "viber",
                "wechat",
                "kakaotalk",
                "signal",
                "slack",
                "skype",
                "teams",
                "zoom",
                "threads",
                "pinterest",
                "tumblr",
                "gmail",
                "outlook",
                "dropbox",
                "onedrive",
                "snapseed",
                "lightroom",
                "canva",
                "picsart",
                "vsco",
                "wallpaper",
                ".trash",
                "trashed",
                "recycle",
                ".thumbnails",
                "/android/data/",
                "/android/obb/",
                "/android/media/"
        };
        for (String tok : blockedTokens) {
            if (combined.contains(tok)) {
                return true;
            }
        }
        if (!isMessengerDir && combined.contains("facebook")) {
            return true;
        }
        return false;
    }

    private static boolean hasScreenshotMarker(String s) {
        return s.contains("screenshot")
                || s.contains("screencap")
                || s.contains("screen_cap")
                || s.contains("captura")
                || s.contains("schirmfoto")
                || s.contains("tangkapan")
                || s.contains("snimok")
                || s.contains("스크린샷")
                || s.contains("スクリーンショット")
                || s.contains("截屏");
    }

    @SuppressWarnings("deprecation")
    private static ScreenshotData extractScreenshotFromCursor(
            Cursor cursor,
            List<int[]> displayDims,
            long nowSec,
            boolean allowMessenger,
            boolean allowBrowserDownloads
    ) {
        if (cursor == null) return null;
        int idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID);
        int nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
        int bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
        int dateAddCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
        int dateModCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED);
        int wCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH);
        int hCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT);
        int pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH);
        int dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
        int pendingCol = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING);
        int trashedCol = Build.VERSION.SDK_INT >= 30
                ? cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                : -1;

        int fallbackW = !displayDims.isEmpty() ? displayDims.get(0)[0] : 1080;
        int fallbackH = !displayDims.isEmpty() ? displayDims.get(0)[1] : 2400;

        int checked = 0;
        while (cursor.moveToNext() && checked < 35) {
            checked++;
            if (pendingCol >= 0 && cursor.getInt(pendingCol) == 1) continue;
            if (trashedCol >= 0 && cursor.getInt(trashedCol) == 1) continue;

            String name = nameCol >= 0 ? cursor.getString(nameCol) : null;
            String bucket = bucketCol >= 0 ? cursor.getString(bucketCol) : null;
            String relPath = pathCol >= 0 ? cursor.getString(pathCol) : null;
            String dataPath = dataCol >= 0 ? cursor.getString(dataCol) : null;

            if (name != null && (name.startsWith("FoldShot_") || name.startsWith(".") || isOwnSavedOutput(null, name))) continue;

            String lowerName = name != null ? name.toLowerCase(Locale.US) : "";
            if (lowerName.endsWith(".crdownload") || lowerName.endsWith(".tmp") || lowerName.endsWith(".part")) {
                continue;
            }
            String lowerBucket = bucket != null ? bucket.toLowerCase(Locale.US) : "";
            String lowerPath = relPath != null ? relPath.toLowerCase(Locale.US) : "";
            String lowerDirData = extractParentDirLower(dataPath);

            boolean isMessengerDir = isMessengerFolder(lowerPath, lowerBucket, lowerDirData);
            boolean isBrowserDir = isBrowserDownloadFolder(lowerPath, lowerBucket, lowerDirData);

            // Never trigger on Quick Share, Nearby Share, Bluetooth, other messaging/social apps,
            // editor/wallpaper directories, or trashed/hidden directories.
            if (isAlwaysBlockedNonScreenshotLocation(lowerPath, lowerBucket, lowerDirData, isMessengerDir)) continue;

            // Block download directories unless explicitly enabled for Browser / Chrome downloads
            if ((lowerPath.contains("download") || lowerBucket.contains("download") || lowerDirData.contains("/download"))
                    && !(allowBrowserDownloads && isBrowserDir)) {
                continue;
            }
            if (isMessengerDir && !allowMessenger) {
                continue;
            }

            // Skip obvious camera/other-app filenames when not in Messenger or Browser Downloads
            if (!isMessengerDir && !isBrowserDir) {
                if (lowerName.startsWith("received_") || lowerName.startsWith("fb_img_")
                        || lowerName.startsWith("pxl_") || lowerName.startsWith("dsc")
                        || lowerName.startsWith("mvimg_") || lowerName.contains("-wa0")) {
                    continue;
                }
            }

            long rawAdd = dateAddCol >= 0 ? cursor.getLong(dateAddCol) : 0L;
            long rawMod = dateModCol >= 0 ? cursor.getLong(dateModCol) : 0L;
            long addedSec = normalizeTimestampSeconds(rawAdd);
            long modSec = Math.max(normalizeTimestampSeconds(rawMod), addedSec);
            if (modSec <= 0L) modSec = nowSec;

            // If DATE_ADDED is old (> 45s ago) and DATE_MODIFIED bumped (e.g. Gallery edit/restore), do not auto-open
            if (addedSec > 0L && (nowSec - addedSec) > 45L && (modSec - addedSec) > 45L) {
                continue;
            }

            int imgW = wCol >= 0 ? cursor.getInt(wCol) : 0;
            int imgH = hCol >= 0 ? cursor.getInt(hCol) : 0;

            boolean explicitScreenshotName = hasScreenshotMarker(lowerName) || lowerName.contains("screen-");
            boolean isScreenshotDir = !isMessengerDir && !isBrowserDir && (
                    hasScreenshotMarker(lowerPath)
                            || hasScreenshotMarker(lowerBucket)
                            || hasScreenshotMarker(lowerDirData)
            );

            // Some OPPO / Realme ColorOS devices store "Screenshot_*.jpg" directly inside DCIM/Camera!
            boolean isCameraDir = lowerPath.contains("dcim/camera")
                    || lowerBucket.equals("camera")
                    || lowerDirData.contains("/dcim/camera/");

            boolean allowedByScreenshot = isScreenshotDir || (isCameraDir && explicitScreenshotName);
            boolean allowedByMessenger = allowMessenger && isMessengerDir;
            boolean allowedByBrowser = allowBrowserDownloads && isBrowserDir;

            if (!allowedByScreenshot && !allowedByMessenger && !allowedByBrowser) {
                continue;
            }

            long id = idCol >= 0 ? cursor.getLong(idCol) : -1L;
            Uri contentUri = id >= 0
                    ? ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

            return new ScreenshotData(
                    contentUri,
                    dataPath,
                    name != null ? name : "Screenshot.png",
                    modSec,
                    imgW > 0 ? imgW : fallbackW,
                    imgH > 0 ? imgH : fallbackH
            );
        }
        return null;
    }

    private static int computeInSampleSize(int rawW, int rawH, int maxSide) {
        int inSampleSize = 1;
        int longest = Math.max(rawW, rawH);
        while ((longest / inSampleSize) > maxSide) {
            inSampleSize *= 2;
        }
        return Math.max(1, inSampleSize);
    }

    private static int readExifOrientation(Context context, Uri uri) {
        if (uri == null || Uri.EMPTY.equals(uri)) return ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = openUriStream(context, uri)) {
            if (in == null) return ExifInterface.ORIENTATION_NORMAL;
            ExifInterface exif = new ExifInterface(in);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap applyExifOrientation(Bitmap bmp, int orientation) {
        if (bmp == null) return null;
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.setRotate(90f);
                m.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.setRotate(-90f);
                m.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.setRotate(270f);
                break;
            default:
                return bmp;
        }
        try {
            Bitmap transformed = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (transformed != bmp) {
                bmp.recycle();
            }
            return transformed;
        } catch (OutOfMemoryError oom) {
            return bmp;
        }
    }

    private static RectF mapDisplayNormCropToRawNorm(RectF dispCrop, int orientation) {
        float l = Math.max(0f, Math.min(1f, dispCrop.left));
        float t = Math.max(0f, Math.min(1f, dispCrop.top));
        float r = Math.max(l, Math.min(1f, dispCrop.right));
        float b = Math.max(t, Math.min(1f, dispCrop.bottom));

        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                return new RectF(1f - r, t, 1f - l, b);
            case ExifInterface.ORIENTATION_ROTATE_180:
                return new RectF(1f - r, 1f - b, 1f - l, 1f - t);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return new RectF(l, 1f - b, r, 1f - t);
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return new RectF(t, l, b, r);
            case ExifInterface.ORIENTATION_ROTATE_90:
                return new RectF(t, 1f - r, b, 1f - l);
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return new RectF(1f - b, 1f - r, 1f - t, 1f - l);
            case ExifInterface.ORIENTATION_ROTATE_270:
                return new RectF(1f - b, l, 1f - t, r);
            default:
                return new RectF(l, t, r, b);
        }
    }

    /**
     * Loads GPU-safe preview bitmap across high-DPI devices and Mali/MediaTek GPUs (Helio G99 on Infinix X6851)
     * without hitting OutOfMemoryError or OpenGL max texture limits.
     */
    public static Bitmap loadBitmap(Context context, Uri uri) {
        if (uri == null || Uri.EMPTY.equals(uri)) return null;
        try {
            int orientation = readExifOrientation(context, uri);

            BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
            boundsOpts.inJustDecodeBounds = true;
            try (InputStream in = openUriStream(context, uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, boundsOpts);
            }

            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = computeInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, MAX_DECODE_SIDE_PX);
            decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = openUriStream(context, uri)) {
                if (in == null) return null;
                Bitmap decoded = BitmapFactory.decodeStream(in, null, decodeOpts);
                return applyExifOrientation(decoded, orientation);
            }
        } catch (OutOfMemoryError oom) {
            System.gc();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decodes the actual full-resolution image (inSampleSize = 1) directly from sourceUri
     * so saving, moving, copying, or sharing photos downloaded from other apps never loses resolution.
     */
    public static Bitmap loadFullResolutionBitmap(Context context, Uri uri) {
        if (uri == null || Uri.EMPTY.equals(uri)) return null;
        int orientation = readExifOrientation(context, uri);
        try {
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = 1;
            decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = openUriStream(context, uri)) {
                if (in == null) return null;
                Bitmap full = BitmapFactory.decodeStream(in, null, decodeOpts);
                if (full != null) {
                    return applyExifOrientation(full, orientation);
                }
            }
        } catch (OutOfMemoryError oom) {
            System.gc();
        } catch (Exception ignored) {
        }
        return null;
    }

    public static boolean isFullCrop(RectF normCrop) {
        return normCrop == null
                || (normCrop.left <= 0.005f && normCrop.top <= 0.005f
                && normCrop.right >= 0.995f && normCrop.bottom >= 0.995f);
    }

    /**
     * Crops from the full-resolution original file (using BitmapRegionDecoder for very large photos
     * or full-res Bitmap decoding), falling back to previewBitmap only when sourceUri is synthetic/empty.
     */
    @SuppressWarnings("deprecation")
    public static Bitmap cropFullResolution(
            Context context,
            Uri sourceUri,
            Bitmap previewFallback,
            RectF normCrop,
            boolean isStitched
    ) {
        boolean isFull = isFullCrop(normCrop);

        if (isStitched || sourceUri == null || Uri.EMPTY.equals(sourceUri)) {
            return cropBitmap(previewFallback, normCrop);
        }

        if (isFull) {
            Bitmap full = loadFullResolutionBitmap(context, sourceUri);
            if (full != null) return full;
            return cropBitmap(previewFallback, normCrop);
        }

        int orientation = readExifOrientation(context, sourceUri);
        try {
            BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
            boundsOpts.inJustDecodeBounds = true;
            try (InputStream in = openUriStream(context, sourceUri)) {
                if (in != null) {
                    BitmapFactory.decodeStream(in, null, boundsOpts);
                }
            }

            int rawW = boundsOpts.outWidth;
            int rawH = boundsOpts.outHeight;
            if (rawW > 0 && rawH > 0) {
                RectF rawNorm = mapDisplayNormCropToRawNorm(normCrop, orientation);
                int rx = Math.max(0, Math.round(rawNorm.left * rawW));
                int ry = Math.max(0, Math.round(rawNorm.top * rawH));
                int rw = Math.max(1, Math.min(rawW - rx, Math.round((rawNorm.right - rawNorm.left) * rawW)));
                int rh = Math.max(1, Math.min(rawH - ry, Math.round((rawNorm.bottom - rawNorm.top) * rawH)));

                try (InputStream in = openUriStream(context, sourceUri)) {
                    if (in != null) {
                        BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(in, false);
                        if (decoder != null) {
                            BitmapFactory.Options regionOpts = new BitmapFactory.Options();
                            regionOpts.inSampleSize = 1;
                            regionOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                            Bitmap region = decoder.decodeRegion(new Rect(rx, ry, rx + rw, ry + rh), regionOpts);
                            decoder.recycle();
                            if (region != null) {
                                return applyExifOrientation(region, orientation);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        Bitmap full = loadFullResolutionBitmap(context, sourceUri);
        if (full != null) {
            return cropBitmap(full, normCrop);
        }
        return cropBitmap(previewFallback, normCrop);
    }

    private static InputStream openUriStream(Context context, Uri uri) throws Exception {
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return new FileInputStream(new File(uri.getPath()));
        }
        return context.getContentResolver().openInputStream(uri);
    }

    public static Bitmap cropBitmap(Bitmap source, RectF normCrop) {
        if (source == null) return null;
        if (isFullCrop(normCrop)) return source;

        int x = Math.max(0, Math.round(normCrop.left * source.getWidth()));
        int y = Math.max(0, Math.round(normCrop.top * source.getHeight()));
        int w = Math.max(1, Math.min(source.getWidth() - x, Math.round((normCrop.right - normCrop.left) * source.getWidth())));
        int h = Math.max(1, Math.min(source.getHeight() - y, Math.round((normCrop.bottom - normCrop.top) * source.getHeight())));
        return Bitmap.createBitmap(source, x, y, w, h);
    }

    public static Bitmap stitchBitmapsVertically(Bitmap baseBitmap, Bitmap nextFrame) {
        if (baseBitmap == null) return nextFrame;
        if (nextFrame == null) return baseBitmap;

        int targetW = baseBitmap.getWidth();
        int frameW = nextFrame.getWidth();
        int frameH = nextFrame.getHeight();

        if (frameW != targetW) {
            int scaledH = Math.round(((float) targetW / frameW) * frameH);
            nextFrame = Bitmap.createScaledBitmap(nextFrame, targetW, scaledH, true);
            frameH = nextFrame.getHeight();
        }

        int baseKeepH = Math.round(baseBitmap.getHeight() * 0.92f);
        int sliceTop = Math.round(frameH * 0.14f);
        int sliceBottom = Math.round(frameH * 0.92f);
        int sliceH = Math.max(1, sliceBottom - sliceTop);

        int totalH = Math.min(MAX_STITCH_HEIGHT, baseKeepH + sliceH);
        int clampedBaseKeep = Math.min(baseKeepH, totalH);
        int remainingSlice = Math.max(0, totalH - clampedBaseKeep);

        Bitmap result = Bitmap.createBitmap(targetW, totalH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);

        canvas.drawBitmap(
                baseBitmap,
                new Rect(0, 0, targetW, clampedBaseKeep),
                new Rect(0, 0, targetW, clampedBaseKeep),
                null
        );
        if (remainingSlice > 0) {
            canvas.drawBitmap(
                    nextFrame,
                    new Rect(0, sliceTop, targetW, Math.min(sliceBottom, sliceTop + remainingSlice)),
                    new Rect(0, clampedBaseKeep, targetW, totalH),
                    null
            );
        }
        return result;
    }

    private static final Handler CLIP_CLEAR_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService PRIVACY_EXECUTOR = Executors.newSingleThreadExecutor();
    private static Runnable pendingClipClearRunnable = null;

    public static String buildOutputBaseName(FoldShotPrefs prefs, String defaultPrefix) {
        if (prefs != null && prefs.isAnonymousFilenameEnabled()) {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            long ms = System.currentTimeMillis() % 1000L;
            return "IMG_" + ts + "_" + String.format(Locale.US, "%03d", ms);
        }
        return defaultPrefix + "_" + System.currentTimeMillis();
    }

    public static boolean stripExifMetadataFromFile(File imageFile) {
        if (imageFile == null || !imageFile.exists()) return false;
        try {
            ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
            String[] sensitiveTags = new String[]{
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_TIMESTAMP,
                    ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_GPS_PROCESSING_METHOD,
                    ExifInterface.TAG_GPS_AREA_INFORMATION,
                    ExifInterface.TAG_GPS_SPEED,
                    ExifInterface.TAG_GPS_DEST_BEARING,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_SOFTWARE,
                    ExifInterface.TAG_ARTIST,
                    ExifInterface.TAG_COPYRIGHT,
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    ExifInterface.TAG_USER_COMMENT,
                    "CameraOwnerName",
                    "BodySerialNumber",
                    "LensMake",
                    "LensModel",
                    "LensSerialNumber",
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_SUBSEC_TIME,
                    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED
            };
            for (String tag : sensitiveTags) {
                exif.setAttribute(tag, null);
            }
            exif.saveAttributes();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static byte[] readCleanSourceBytes(Context context, Uri sourceUri, String ext, boolean stripMetadata) {
        if (sourceUri == null || Uri.EMPTY.equals(sourceUri)) return null;
        if (stripMetadata && ".jpg".equalsIgnoreCase(ext)) {
            File tmp = null;
            try {
                tmp = File.createTempFile("fs_scrub_", ".jpg", context.getCacheDir());
                try (InputStream in = openUriStream(context, sourceUri);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) return null;
                    byte[] buf = new byte[16384];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                    out.flush();
                }
                stripExifMetadataFromFile(tmp);
                try (FileInputStream fis = new FileInputStream(tmp)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int len;
                    while ((len = fis.read(buf)) != -1) {
                        bos.write(buf, 0, len);
                    }
                    return bos.toByteArray();
                }
            } catch (Exception ignored) {
            } finally {
                if (tmp != null && tmp.exists()) {
                    tmp.delete();
                }
            }
        }
        try (InputStream in = openUriStream(context, sourceUri)) {
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int len;
            while ((len = in.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void clearClipboardAndCache(Context context) {
        if (context == null) return;
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                if (Build.VERSION.SDK_INT >= 28) {
                    clipboard.clearPrimaryClip();
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                }
            }
        } catch (Exception ignored) {
        }
        try {
            File clipDir = new File(context.getCacheDir(), "clipboard");
            File[] files = clipDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static void scheduleClipboardAutoClear(Context context) {
        FoldShotPrefs prefs = new FoldShotPrefs(context);
        int clearSec = prefs.getClipboardAutoClearSec();
        final Context appCtx = context.getApplicationContext();
        synchronized (MediaStoreHelper.class) {
            if (pendingClipClearRunnable != null) {
                CLIP_CLEAR_HANDLER.removeCallbacks(pendingClipClearRunnable);
                pendingClipClearRunnable = null;
            }
            if (clearSec > 0) {
                pendingClipClearRunnable = () -> clearClipboardAndCache(appCtx);
                CLIP_CLEAR_HANDLER.postDelayed(pendingClipClearRunnable, clearSec * 1000L);
            }
        }
    }

    public static Uri copyBitmapToClipboard(Context context, Bitmap cropped) {
        try {
            FoldShotPrefs prefs = new FoldShotPrefs(context);
            File clipDir = new File(context.getCacheDir(), "clipboard");
            if (!clipDir.exists()) clipDir.mkdirs();
            File[] oldFiles = clipDir.listFiles();
            if (oldFiles != null) {
                for (File f : oldFiles) f.delete();
            }

            File outFile = new File(clipDir, buildOutputBaseName(prefs, "FoldShot") + ".png");
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                cropped.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.flush();
            }

            Uri clipUri = ClipboardFileProvider.getUriForFile(outFile);
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newUri(context.getContentResolver(), "FoldShot Crop", clipUri);
            clipboard.setPrimaryClip(clip);
            scheduleClipboardAutoClear(context);
            return clipUri;
        } catch (Exception e) {
            return null;
        }
    }

    public static int runAutoExpireCleanup(Context context, boolean force) {
        if (context == null) return 0;
        final Context appCtx = context.getApplicationContext();
        final FoldShotPrefs prefs = new FoldShotPrefs(appCtx);
        final int ttlHours = prefs.getAutoExpireTtlHours();
        final String targetFolder = prefs.getAutoExpireFolderName();
        if (ttlHours <= 0 || targetFolder == null || targetFolder.trim().isEmpty()) {
            return 0;
        }
        long nowMs = System.currentTimeMillis();
        if (!force && (nowMs - prefs.getLastAutoExpireRunMs()) < 15L * 60L * 1000L) {
            return 0;
        }
        prefs.setLastAutoExpireRunMs(nowMs);

        if (!force) {
            PRIVACY_EXECUTOR.execute(() -> performAutoExpireCleanupSync(appCtx, targetFolder.trim(), ttlHours));
            return 0;
        }
        return performAutoExpireCleanupSync(appCtx, targetFolder.trim(), ttlHours);
    }

    private static int performAutoExpireCleanupSync(Context context, String targetFolder, int ttlHours) {
        int removed = 0;
        long cutoffSec = (System.currentTimeMillis() / 1000L) - (ttlHours * 3600L);
        ContentResolver resolver = context.getContentResolver();
        String[] proj = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_ADDED
        };
        try (Cursor c = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj,
                null,
                null,
                null
        )) {
            if (c != null) {
                int idCol = c.getColumnIndex(MediaStore.Images.Media._ID);
                int bucketCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                int modCol = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED);
                int addCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
                while (c.moveToNext()) {
                    String bucket = bucketCol >= 0 ? c.getString(bucketCol) : null;
                    if (bucket == null || !bucket.trim().equalsIgnoreCase(targetFolder)) continue;
                    long modSec = modCol >= 0 ? normalizeTimestampSeconds(c.getLong(modCol)) : 0L;
                    long addSec = addCol >= 0 ? normalizeTimestampSeconds(c.getLong(addCol)) : 0L;
                    long itemSec = Math.max(modSec, addSec);
                    if (itemSec <= 0L || itemSec >= cutoffSec) continue;
                    long id = idCol >= 0 ? c.getLong(idCol) : -1L;
                    if (id < 0) continue;
                    Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    try {
                        if (resolver.delete(uri, null, null) > 0) {
                            removed++;
                        }
                    } catch (SecurityException ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return removed;
    }

    private static String sanitizeAlbumName(String albumName) {
        if (albumName == null) return "Saved";
        String clean = albumName.trim().replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        int slash = clean.lastIndexOf('/');
        if (slash >= 0 && slash < clean.length() - 1) {
            clean = clean.substring(slash + 1);
        }
        clean = clean.trim();
        return clean.isEmpty() ? "Saved" : clean;
    }

    private static String normalizeRelPath(String relPath) {
        if (relPath == null) return "";
        String clean = relPath.trim();
        if (!clean.isEmpty() && !clean.endsWith("/")) {
            clean = clean + "/";
        }
        return clean;
    }

    @SuppressWarnings("deprecation")
    public static Uri resolveContentUriForSource(Context context, Uri sourceUri) {
        if (sourceUri == null || Uri.EMPTY.equals(sourceUri)) return sourceUri;
        if ("content".equalsIgnoreCase(sourceUri.getScheme())) {
            return sourceUri;
        }
        if ("file".equalsIgnoreCase(sourceUri.getScheme()) && sourceUri.getPath() != null && context != null) {
            String path = sourceUri.getPath();
            File f = new File(path);
            String fileName = f.getName();
            String parentName = f.getParentFile() != null ? f.getParentFile().getName().toLowerCase(Locale.US) : "";
            ContentResolver resolver = context.getContentResolver();
            String[] proj = new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.RELATIVE_PATH
            };
            try (Cursor c = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    proj,
                    MediaStore.Images.Media.DATA + "=?",
                    new String[]{path},
                    MediaStore.Images.Media._ID + " DESC"
            )) {
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                }
            } catch (Exception ignored) {
            }

            if (fileName != null && !fileName.isEmpty()) {
                try (Cursor c = resolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        proj,
                        MediaStore.Images.Media.DISPLAY_NAME + "=?",
                        new String[]{fileName},
                        MediaStore.Images.Media.DATE_MODIFIED + " DESC, " + MediaStore.Images.Media._ID + " DESC"
                )) {
                    if (c != null) {
                        int idCol = c.getColumnIndex(MediaStore.Images.Media._ID);
                        int dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA);
                        int relCol = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH);
                        Uri firstMatch = null;
                        while (c.moveToNext()) {
                            long id = idCol >= 0 ? c.getLong(idCol) : -1L;
                            if (id < 0) continue;
                            Uri candidate = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                            if (firstMatch == null) firstMatch = candidate;
                            String rowData = dataCol >= 0 ? c.getString(dataCol) : null;
                            String rowRel = relCol >= 0 ? c.getString(relCol) : null;
                            if (parentName.isEmpty()
                                    || (rowData != null && rowData.toLowerCase(Locale.US).contains("/" + parentName + "/"))
                                    || (rowRel != null && rowRel.toLowerCase(Locale.US).contains(parentName))) {
                                return candidate;
                            }
                        }
                        if (firstMatch != null) return firstMatch;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return sourceUri;
    }

    private static boolean folderContainsFoldShotFiles(Context context, String relPath) {
        if (context == null || relPath == null || relPath.isEmpty()) return false;
        ContentResolver resolver = context.getContentResolver();
        try (Cursor c = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH},
                MediaStore.Images.Media.RELATIVE_PATH + "=?",
                new String[]{relPath},
                null
        )) {
            if (c != null) {
                int nameCol = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
                while (c.moveToNext()) {
                    String name = nameCol >= 0 ? c.getString(nameCol) : null;
                    if (name != null && (name.startsWith("FoldShot_") || isOwnSavedOutput(null, name))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static boolean deleteScreenshotSilently(Context context, Activity optionalActivity, Uri sourceUri) {
        if (sourceUri == null || Uri.EMPTY.equals(sourceUri) || context == null) return true;

        Uri resolvedUri = resolveContentUriForSource(context, sourceUri);

        if ("file".equalsIgnoreCase(resolvedUri.getScheme()) && resolvedUri.getPath() != null) {
            final String filePath = resolvedUri.getPath();
            try {
                File f = new File(filePath);
                if (!f.exists() || f.delete()) {
                    return true;
                }
            } catch (Exception ignored) {
            }

            final Context appCtx = context.getApplicationContext();
            try {
                MediaScannerConnection.scanFile(
                        appCtx,
                        new String[]{filePath},
                        null,
                        (scannedPath, scannedUri) -> {
                            if (scannedUri != null) {
                                CLIP_CLEAR_HANDLER.post(() -> deleteContentUriWithPromptFallback(appCtx, null, scannedUri));
                            }
                        }
                );
            } catch (Exception ignored) {
            }
            return false;
        }

        return deleteContentUriWithPromptFallback(context, optionalActivity, resolvedUri);
    }

    private static boolean deleteContentUriWithPromptFallback(Context context, Activity optionalActivity, Uri contentUri) {
        if (contentUri == null || Uri.EMPTY.equals(contentUri)) return true;
        if (!"content".equalsIgnoreCase(contentUri.getScheme())) return false;
        ContentResolver resolver = context.getContentResolver();

        try {
            int deleted = resolver.delete(contentUri, null, null);
            if (deleted > 0) return true;
        } catch (SecurityException secEx) {
            if (Build.VERSION.SDK_INT == 29 && secEx instanceof RecoverableSecurityException && optionalActivity != null) {
                try {
                    RecoverableSecurityException rse = (RecoverableSecurityException) secEx;
                    optionalActivity.startIntentSenderForResult(
                            rse.getUserAction().getActionIntent().getIntentSender(),
                            REQ_DELETE_SCREENSHOT,
                            null, 0, 0, 0
                    );
                    return false;
                } catch (IntentSender.SendIntentException ignored) {
                }
            }
        } catch (IllegalArgumentException ignored) {
            return true;
        } catch (Exception ignored) {
        }

        if (Build.VERSION.SDK_INT >= 30) {
            if (optionalActivity != null) {
                try {
                    PendingIntent pi = MediaStore.createDeleteRequest(resolver, Collections.singletonList(contentUri));
                    optionalActivity.startIntentSenderForResult(
                            pi.getIntentSender(),
                            REQ_DELETE_SCREENSHOT,
                            null, 0, 0, 0
                    );
                    return false;
                } catch (IntentSender.SendIntentException ignored) {
                }
            } else {
                ScreenshotEditorActivity.launchDeletePrompt(context, contentUri);
                return false;
            }
        } else if (Build.VERSION.SDK_INT == 29 && optionalActivity == null) {
            ScreenshotEditorActivity.launchDeletePrompt(context, contentUri);
        }
        return false;
    }

    public static String resolveRelativePathForAlbum(Context context, String albumName) {
        String clean = sanitizeAlbumName(albumName);
        String dcimDefault = Environment.DIRECTORY_DCIM + "/" + clean + "/";
        List<GalleryAlbumInfo> existing = queryDeviceGalleryAlbums(context);
        String nonDcimCandidate = null;
        for (GalleryAlbumInfo info : existing) {
            if (info.bucketName == null || !info.bucketName.equalsIgnoreCase(clean)) continue;
            String p = normalizeRelPath(info.relativePath);
            if (p.isEmpty()) continue;
            if (p.toLowerCase(Locale.US).startsWith("dcim/")) {
                return p;
            }
            if (!p.equalsIgnoreCase("Pictures/" + clean + "/")) {
                if (nonDcimCandidate == null) nonDcimCandidate = p;
            } else if (info.itemCount > 15 && !folderContainsFoldShotFiles(context, p)) {
                if (nonDcimCandidate == null) nonDcimCandidate = p;
            }
        }
        return nonDcimCandidate != null ? nonDcimCandidate : dcimDefault;
    }

    public static String[] detectSourceMimeAndExt(Context context, Uri sourceUri) {
        String mimeType = null;
        String displayName = null;
        ContentResolver resolver = context.getContentResolver();
        try {
            if ("content".equalsIgnoreCase(sourceUri.getScheme())) {
                mimeType = resolver.getType(sourceUri);
                try (Cursor c = resolver.query(
                        sourceUri,
                        new String[]{MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME},
                        null, null, null
                )) {
                    if (c != null && c.moveToFirst()) {
                        int mIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
                        int nIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                        if (mIdx >= 0 && (mimeType == null || mimeType.isEmpty() || mimeType.endsWith("/*"))) {
                            mimeType = c.getString(mIdx);
                        }
                        if (nIdx >= 0) {
                            displayName = c.getString(nIdx);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        String candidateMime = mimeType != null ? mimeType.toLowerCase(Locale.US) : "";
        if (candidateMime.contains("jpeg") || candidateMime.contains("jpg")) {
            return new String[]{"image/jpeg", ".jpg"};
        }
        if (candidateMime.contains("webp")) {
            return new String[]{"image/webp", ".webp"};
        }
        if (candidateMime.contains("heic") || candidateMime.contains("heif")) {
            return new String[]{"image/heic", ".heic"};
        }
        if (candidateMime.contains("png")) {
            return new String[]{"image/png", ".png"};
        }

        String nameOrPath = displayName != null ? displayName : sourceUri.getPath();
        if (nameOrPath != null) {
            String lp = nameOrPath.toLowerCase(Locale.US);
            if (lp.endsWith(".jpg") || lp.endsWith(".jpeg")) {
                return new String[]{"image/jpeg", ".jpg"};
            }
            if (lp.endsWith(".webp")) {
                return new String[]{"image/webp", ".webp"};
            }
            if (lp.endsWith(".heic") || lp.endsWith(".heif")) {
                return new String[]{"image/heic", ".heic"};
            }
            if (lp.endsWith(".png")) {
                return new String[]{"image/png", ".png"};
            }
        }

        // Sniff header bytes when metadata is absent
        try (InputStream in = openUriStream(context, sourceUri)) {
            if (in != null) {
                byte[] hdr = new byte[12];
                int n = in.read(hdr);
                if (n >= 3 && (hdr[0] & 0xFF) == 0xFF && (hdr[1] & 0xFF) == 0xD8 && (hdr[2] & 0xFF) == 0xFF) {
                    return new String[]{"image/jpeg", ".jpg"};
                }
                if (n >= 12 && hdr[0] == 'R' && hdr[1] == 'I' && hdr[2] == 'F' && hdr[3] == 'F'
                        && hdr[8] == 'W' && hdr[9] == 'E' && hdr[10] == 'B' && hdr[11] == 'P') {
                    return new String[]{"image/webp", ".webp"};
                }
            }
        } catch (Exception ignored) {
        }
        return new String[]{"image/png", ".png"};
    }

    private static boolean copyOriginalStreamToMediaStore(
            Context context,
            Uri sourceUri,
            String relFolder,
            String baseDisplayName
    ) {
        if (sourceUri == null || Uri.EMPTY.equals(sourceUri)) return false;
        ContentResolver resolver = context.getContentResolver();
        FoldShotPrefs prefs = new FoldShotPrefs(context);
        boolean stripMetadata = prefs.isStripMetadataEnabled();
        String[] mimeAndExt = detectSourceMimeAndExt(context, sourceUri);
        String mimeType = mimeAndExt[0];
        String ext = mimeAndExt[1];

        Uri inserted = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, baseDisplayName + ext);
            values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, relFolder);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (inserted == null) return false;

            if (stripMetadata && ".jpg".equalsIgnoreCase(ext)) {
                byte[] scrubbed = readCleanSourceBytes(context, sourceUri, ext, true);
                if (scrubbed == null || scrubbed.length == 0) {
                    resolver.delete(inserted, null, null);
                    return false;
                }
                try (OutputStream out = resolver.openOutputStream(inserted)) {
                    if (out == null) {
                        resolver.delete(inserted, null, null);
                        return false;
                    }
                    out.write(scrubbed);
                    out.flush();
                }
            } else {
                try (InputStream in = openUriStream(context, sourceUri);
                     OutputStream out = resolver.openOutputStream(inserted)) {
                    if (in == null || out == null) {
                        resolver.delete(inserted, null, null);
                        return false;
                    }
                    byte[] buf = new byte[16384];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                    out.flush();
                }
            }

            recordOwnSavedOutput(inserted, baseDisplayName + ext);
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(inserted, done, null, null);
            invalidateAlbumCache();
            return true;
        } catch (Exception e) {
            if (inserted != null) {
                try {
                    resolver.delete(inserted, null, null);
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }

    public static boolean moveBitmapToFavoriteAlbum(Context context, Activity optionalActivity, Uri sourceUri, Bitmap cropped, String albumName, boolean isFullCrop) {
        ContentResolver resolver = context.getContentResolver();
        FoldShotPrefs prefs = new FoldShotPrefs(context);
        Uri resolvedSource = resolveContentUriForSource(context, sourceUri);
        String relFolder = resolveRelativePathForAlbum(context, albumName);
        String baseName = buildOutputBaseName(prefs, "FoldShot");

        if (isFullCrop && resolvedSource != null && !Uri.EMPTY.equals(resolvedSource)) {
            boolean needRewriteForPrivacy = prefs.isAnonymousFilenameEnabled()
                    || (prefs.isStripMetadataEnabled() && ".jpg".equalsIgnoreCase(detectSourceMimeAndExt(context, resolvedSource)[1]));
            if (!needRewriteForPrivacy && "content".equalsIgnoreCase(resolvedSource.getScheme())) {
                try {
                    ContentValues moveVals = new ContentValues();
                    moveVals.put(MediaStore.MediaColumns.RELATIVE_PATH, relFolder);
                    if (resolver.update(resolvedSource, moveVals, null, null) > 0) {
                        recordOwnSavedOutput(resolvedSource, null);
                        invalidateAlbumCache();
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
            if (copyOriginalStreamToMediaStore(context, resolvedSource, relFolder, baseName)) {
                deleteScreenshotSilently(context, optionalActivity, resolvedSource);
                return true;
            }
            if (cropped == null) {
                cropped = loadFullResolutionBitmap(context, resolvedSource);
            }
        }

        if (cropped == null) return false;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, baseName + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, relFolder);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (inserted == null) return false;

            try (OutputStream out = resolver.openOutputStream(inserted)) {
                if (out != null) {
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }

            recordOwnSavedOutput(inserted, baseName + ".png");
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(inserted, done, null, null);
            invalidateAlbumCache();

            if (resolvedSource != null && !Uri.EMPTY.equals(resolvedSource)) {
                deleteScreenshotSilently(context, optionalActivity, resolvedSource);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveScreenshotRelativePath(Context context, Uri sourceUri) {
        if (sourceUri != null && !Uri.EMPTY.equals(sourceUri) && "content".equalsIgnoreCase(sourceUri.getScheme())) {
            try (Cursor c = context.getContentResolver().query(
                    sourceUri,
                    new String[]{MediaStore.Images.Media.RELATIVE_PATH},
                    null,
                    null,
                    null
            )) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH);
                    if (idx >= 0) {
                        String rel = c.getString(idx);
                        if (rel != null && !rel.trim().isEmpty()) {
                            String clean = rel.trim();
                            if (!clean.endsWith("/")) clean = clean + "/";
                            if (hasScreenshotMarker(clean.toLowerCase(Locale.US))) {
                                return clean;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (isSamsungDevice() || isXiaomiDevice() || isVivoDevice()) {
            return "DCIM/Screenshots/";
        }
        return "Pictures/Screenshots/";
    }

    public static boolean saveCroppedScreenshot(Context context, Activity optionalActivity, Uri sourceUri, Bitmap cropped, boolean isFullCrop) {
        ContentResolver resolver = context.getContentResolver();
        FoldShotPrefs prefs = new FoldShotPrefs(context);
        Uri resolvedSource = resolveContentUriForSource(context, sourceUri);
        String relFolder = resolveScreenshotRelativePath(context, resolvedSource);
        String baseName = buildOutputBaseName(prefs, "FoldShot_Screenshot");

        if (isFullCrop && resolvedSource != null && !Uri.EMPTY.equals(resolvedSource)) {
            boolean needRewriteForPrivacy = prefs.isAnonymousFilenameEnabled()
                    || (prefs.isStripMetadataEnabled() && ".jpg".equalsIgnoreCase(detectSourceMimeAndExt(context, resolvedSource)[1]));
            boolean alreadyInScreenshots = false;
            if ("content".equalsIgnoreCase(resolvedSource.getScheme())) {
                try (Cursor c = resolver.query(resolvedSource, new String[]{MediaStore.Images.Media.RELATIVE_PATH}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int idx = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH);
                        if (idx >= 0) {
                            String rel = c.getString(idx);
                            alreadyInScreenshots = rel != null && hasScreenshotMarker(rel.toLowerCase(Locale.US));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (alreadyInScreenshots && !needRewriteForPrivacy) {
                return true;
            }
            if (!alreadyInScreenshots && !needRewriteForPrivacy && "content".equalsIgnoreCase(resolvedSource.getScheme())) {
                try {
                    ContentValues moveVals = new ContentValues();
                    moveVals.put(MediaStore.MediaColumns.RELATIVE_PATH, relFolder);
                    if (resolver.update(resolvedSource, moveVals, null, null) > 0) {
                        recordOwnSavedOutput(resolvedSource, null);
                        invalidateAlbumCache();
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
            if (copyOriginalStreamToMediaStore(context, resolvedSource, relFolder, baseName)) {
                deleteScreenshotSilently(context, optionalActivity, resolvedSource);
                return true;
            }
            if (cropped == null) {
                cropped = loadFullResolutionBitmap(context, resolvedSource);
            }
        }

        if (cropped == null) return false;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, baseName + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, relFolder);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (inserted == null) return false;

            try (OutputStream out = resolver.openOutputStream(inserted)) {
                if (out != null) {
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }

            recordOwnSavedOutput(inserted, baseName + ".png");
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(inserted, done, null, null);
            invalidateAlbumCache();

            if (resolvedSource != null && !Uri.EMPTY.equals(resolvedSource)) {
                deleteScreenshotSilently(context, optionalActivity, resolvedSource);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void shareBitmap(Context context, Bitmap cropped) {
        Uri uri = copyBitmapToClipboard(context, cropped);
        if (uri == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/png");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent chooser = Intent.createChooser(share, "Share Screenshot");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
    }
}
