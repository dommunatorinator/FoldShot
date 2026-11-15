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
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    public static List<GalleryAlbumInfo> queryDeviceGalleryAlbums(Context context) {
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
                    GalleryAlbumInfo existing = map.get(key);
                    if (existing == null) {
                        String folder = (relPath != null && !relPath.isEmpty())
                                ? relPath
                                : ("Pictures/" + key + "/");
                        map.put(key, new GalleryAlbumInfo(key, folder, 1));
                    } else {
                        existing.itemCount++;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(map.values());
    }

    /**
     * Collects all potential screenshot folders across internal storage AND any removable/secondary StorageVolumes.
     */
    @SuppressWarnings("deprecation")
    public static List<File> getCommonScreenshotDirs(Context context) {
        Set<File> dirs = new LinkedHashSet<>();
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

        for (File root : roots) {
            for (String r : rels) {
                dirs.add(new File(root, r));
            }
        }
        return new ArrayList<>(dirs);
    }

    public static ScreenshotData findLatestScreenshotFileOnDisk(Context context, long minModifiedSec) {
        File newestFile = null;
        long newestModMs = 0L;

        for (File dir : getCommonScreenshotDirs(context)) {
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || f.length() < 1024L) continue;
                String name = f.getName();
                if (name.startsWith(".") || name.startsWith("FoldShot_")) continue;
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
                return new ScreenshotData(
                        Uri.fromFile(newestFile),
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

        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.IS_PENDING
        };
        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC, " + MediaStore.Images.Media._ID + " DESC";

        if (hintUri != null && hintUri.getLastPathSegment() != null && hintUri.getLastPathSegment().matches("\\d+")) {
            try (Cursor c = resolver.query(hintUri, projection, null, null, null)) {
                ScreenshotData fromHint = extractScreenshotFromCursor(c, displayDims, nowSec, true);
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
            ScreenshotData data = extractScreenshotFromCursor(cursor, displayDims, nowSec, false);
            if (data != null) return data;
        } catch (Exception ignored) {
        }

        return findLatestScreenshotFileOnDisk(context, nowSec - 15L);
    }

    @SuppressWarnings("deprecation")
    private static ScreenshotData extractScreenshotFromCursor(Cursor cursor, List<int[]> displayDims, long nowSec, boolean isDirectHintUri) {
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

        int fallbackW = !displayDims.isEmpty() ? displayDims.get(0)[0] : 1080;
        int fallbackH = !displayDims.isEmpty() ? displayDims.get(0)[1] : 2400;

        int checked = 0;
        while (cursor.moveToNext() && checked < 35) {
            checked++;
            if (pendingCol >= 0 && cursor.getInt(pendingCol) == 1) continue;

            String name = nameCol >= 0 ? cursor.getString(nameCol) : null;
            String bucket = bucketCol >= 0 ? cursor.getString(bucketCol) : null;
            String relPath = pathCol >= 0 ? cursor.getString(pathCol) : null;
            String dataPath = dataCol >= 0 ? cursor.getString(dataCol) : null;

            if (name != null && name.startsWith("FoldShot_")) continue;

            String lowerName = name != null ? name.toLowerCase(Locale.US) : "";
            String lowerBucket = bucket != null ? bucket.toLowerCase(Locale.US) : "";
            String lowerPath = relPath != null ? relPath.toLowerCase(Locale.US) : "";
            String lowerData = dataPath != null ? dataPath.toLowerCase(Locale.US) : "";

            long rawAdd = dateAddCol >= 0 ? cursor.getLong(dateAddCol) : 0L;
            long rawMod = dateModCol >= 0 ? cursor.getLong(dateModCol) : 0L;
            long modSec = Math.max(normalizeTimestampSeconds(rawMod), normalizeTimestampSeconds(rawAdd));
            if (modSec <= 0L) modSec = nowSec;

            int imgW = wCol >= 0 ? cursor.getInt(wCol) : 0;
            int imgH = hCol >= 0 ? cursor.getInt(hCol) : 0;

            boolean explicitScreenshotName = lowerName.contains("screenshot")
                    || lowerName.contains("screencap")
                    || lowerName.contains("screen_cap")
                    || lowerName.contains("screen-")
                    || lowerName.contains("captura")
                    || lowerName.contains("schirmfoto")
                    || lowerName.contains("tangkapan")
                    || lowerName.contains("snimok");

            boolean pathMatch = lowerPath.contains("screenshot")
                    || lowerBucket.contains("screenshot")
                    || lowerData.contains("screenshot")
                    || lowerData.contains("screencap");

            // Some OPPO / Realme ColorOS devices store "Screenshot_*.jpg" directly inside DCIM/Camera!
            boolean isCameraDir = lowerPath.contains("dcim/camera") || lowerBucket.equals("camera") || lowerData.contains("/dcim/camera/");
            boolean isRegularCameraPhoto = isCameraDir && !explicitScreenshotName;
            if (isRegularCameraPhoto) continue;

            boolean dimensionMatch = false;
            if ((nowSec - modSec) <= 14L && imgW > 0 && imgH > 0) {
                for (int[] wh : displayDims) {
                    int sw = wh[0];
                    int sh = wh[1];
                    if ((imgW == sw && imgH >= sh * 0.75f) || (imgW == sh && imgH == sw)) {
                        dimensionMatch = true;
                        break;
                    }
                }
            }

            if (!explicitScreenshotName && !pathMatch && !dimensionMatch && !isDirectHintUri) continue;

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

    /**
     * Loads bitmap safely across high-DPI devices and Mali/MediaTek GPUs (Helio G99 on Infinix X6851)
     * without hitting OutOfMemoryError or OpenGL max texture limits.
     */
    public static Bitmap loadBitmap(Context context, Uri uri) {
        if (uri == null || Uri.EMPTY.equals(uri)) return null;
        try {
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
                return BitmapFactory.decodeStream(in, null, decodeOpts);
            }
        } catch (OutOfMemoryError oom) {
            System.gc();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static InputStream openUriStream(Context context, Uri uri) throws Exception {
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return new FileInputStream(new File(uri.getPath()));
        }
        return context.getContentResolver().openInputStream(uri);
    }

    public static Bitmap cropBitmap(Bitmap source, RectF normCrop) {
        if (source == null) return null;
        boolean isFull = normCrop.left <= 0.005f && normCrop.top <= 0.005f
                && normCrop.right >= 0.995f && normCrop.bottom >= 0.995f;
        if (isFull) return source;

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

    public static Uri copyBitmapToClipboard(Context context, Bitmap cropped) {
        try {
            File clipDir = new File(context.getCacheDir(), "clipboard");
            if (!clipDir.exists()) clipDir.mkdirs();
            File[] oldFiles = clipDir.listFiles();
            if (oldFiles != null) {
                for (File f : oldFiles) f.delete();
            }

            File outFile = new File(clipDir, "FoldShot_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                cropped.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.flush();
            }

            Uri clipUri = ClipboardFileProvider.getUriForFile(outFile);
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newUri(context.getContentResolver(), "FoldShot Crop", clipUri);
            clipboard.setPrimaryClip(clip);
            return clipUri;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean deleteScreenshotSilently(Context context, Activity optionalActivity, Uri sourceUri) {
        if (sourceUri == null || Uri.EMPTY.equals(sourceUri)) return true;
        if ("file".equalsIgnoreCase(sourceUri.getScheme()) && sourceUri.getPath() != null) {
            try {
                File f = new File(sourceUri.getPath());
                if (f.exists() && f.delete()) return true;
            } catch (Exception ignored) {
            }
        }
        ContentResolver resolver = context.getContentResolver();

        try {
            int deleted = resolver.delete(sourceUri, null, null);
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
        }

        if (Build.VERSION.SDK_INT >= 30 && optionalActivity != null) {
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(resolver, Collections.singletonList(sourceUri));
                optionalActivity.startIntentSenderForResult(
                        pi.getIntentSender(),
                        REQ_DELETE_SCREENSHOT,
                        null, 0, 0, 0
                );
                return false;
            } catch (IntentSender.SendIntentException ignored) {
            }
        }
        return false;
    }

    private static String resolveRelativePathForAlbum(Context context, String albumName) {
        String clean = albumName != null ? albumName.trim() : "Saved";
        List<GalleryAlbumInfo> existing = queryDeviceGalleryAlbums(context);
        for (GalleryAlbumInfo info : existing) {
            if (info.bucketName.equalsIgnoreCase(clean) && info.relativePath != null && !info.relativePath.isEmpty()) {
                String p = info.relativePath;
                if (!p.endsWith("/")) p = p + "/";
                return p;
            }
        }
        return "Pictures/" + clean + "/";
    }

    public static boolean moveBitmapToFavoriteAlbum(Context context, Activity optionalActivity, Uri sourceUri, Bitmap cropped, String albumName, boolean isFullCrop) {
        ContentResolver resolver = context.getContentResolver();
        String relFolder = resolveRelativePathForAlbum(context, albumName);

        if (isFullCrop && sourceUri != null && !Uri.EMPTY.equals(sourceUri) && "content".equalsIgnoreCase(sourceUri.getScheme())) {
            try {
                ContentValues moveVals = new ContentValues();
                moveVals.put(MediaStore.MediaColumns.RELATIVE_PATH, relFolder);
                if (resolver.update(sourceUri, moveVals, null, null) > 0) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "FoldShot_" + System.currentTimeMillis() + ".png");
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

            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(inserted, done, null, null);

            if (sourceUri != null && !Uri.EMPTY.equals(sourceUri)) {
                deleteScreenshotSilently(context, optionalActivity, sourceUri);
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
