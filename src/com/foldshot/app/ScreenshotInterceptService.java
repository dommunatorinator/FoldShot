package com.foldshot.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.display.DisplayManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Universal Play-Protect-Safe Background Screenshot Utility Service (v2.0.0):
 * - Encompasses Samsung One UI, Xiaomi MIUI/HyperOS, Infinix/Tecno XOS, OPPO/Realme/OnePlus ColorOS,
 *   Vivo/iQOO FuntouchOS/OriginOS, Huawei/Honor EMUI/MagicOS, Pixel, Motorola, Nothing, Sony, Asus.
 * - Triple-Engine Screenshot Detection:
 *   1) MediaStore ContentObserver (with direct hintUri & 4.2s IS_PENDING retry window)
 *   2) Modern FileObserver(File, mask) across all internal & external StorageVolume screenshot folders
 *   3) Interactive screen heartbeat check (catches screenshots even when OEM OS suppresses ContentObserver)
 */
public class ScreenshotInterceptService extends Service {

    public static final String ACTION_TEST_OVERLAY = "com.foldshot.app.ACTION_TEST_OVERLAY";
    public static final String ACTION_RELOAD_OBSERVER = "com.foldshot.app.ACTION_RELOAD_OBSERVER";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager displayManager;
    private int activeDisplayId = Display.DEFAULT_DISPLAY;
    private Context overlayUiContext;
    private WindowManager windowManager;
    private PowerManager powerManager;
    private OneUiSmartCaptureView activeOverlayView;
    private WindowManager.LayoutParams activeLayoutParams;

    private final List<FileObserver> dirObservers = new ArrayList<>();
    private Uri latestHintUri = null;
    private String lastHandledKey = null;
    private long lastHandledModSec = 0L;
    private long serviceStartSec = 0L;

    // Holds previous screenshot bitmap when user taps Scroll Capture to stitch multiple shots
    private Bitmap pendingStitchBitmap = null;
    private Uri pendingStitchFirstUri = null;

    private final Runnable checkMediaStoreRunnable = new Runnable() {
        @Override
        public void run() {
            inspectLatestScreenshot(latestHintUri);
        }
    };

    private final Runnable interactiveHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (powerManager == null || powerManager.isInteractive()) {
                    inspectLatestScreenshot(null);
                }
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, 1400L);
        }
    };

    private final ContentObserver mediaObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            scheduleInspect(uri);
        }
    };

    private void scheduleInspect(Uri hintUri) {
        if (hintUri != null) {
            latestHintUri = hintUri;
        }
        handler.removeCallbacks(checkMediaStoreRunnable);
        long[] delays = new long[]{120L, 400L, 800L, 1400L, 2200L, 3200L, 4200L};
        for (long d : delays) {
            handler.postDelayed(checkMediaStoreRunnable, d);
        }
    }

    private void inspectLatestScreenshot(Uri hintUri) {
        MediaStoreHelper.ScreenshotData latest = MediaStoreHelper.queryLatestScreenshot(getApplicationContext(), hintUri);
        if (latest == null) return;

        long nowSec = System.currentTimeMillis() / 1000L;
        boolean isRecent = (nowSec - latest.dateModifiedSec) <= 18L && latest.dateModifiedSec >= (serviceStartSec - 3L);
        if (!isRecent) return;

        String currentKey = (latest.filePath != null && !latest.filePath.isEmpty())
                ? latest.filePath
                : (latest.uri.toString() + "|" + latest.displayName);

        boolean isSameFile = currentKey.equals(lastHandledKey) || latest.uri.toString().equals(lastHandledKey);
        boolean isUpdatedScrollCapture = isSameFile && (latest.dateModifiedSec > lastHandledModSec);

        if (isSameFile && !isUpdatedScrollCapture) return;

        Bitmap newBmp = MediaStoreHelper.loadBitmap(getApplicationContext(), latest.uri);
        if (newBmp == null) return;

        lastHandledKey = currentKey;
        lastHandledModSec = latest.dateModifiedSec;

        if (pendingStitchBitmap != null && !isSameFile) {
            Bitmap combined = MediaStoreHelper.stitchBitmapsVertically(pendingStitchBitmap, newBmp);
            if (pendingStitchFirstUri != null && !pendingStitchFirstUri.equals(latest.uri)) {
                MediaStoreHelper.deleteScreenshotSilently(getApplicationContext(), null, pendingStitchFirstUri);
            }
            pendingStitchBitmap = null;
            pendingStitchFirstUri = null;
            showAutoOpenHud(latest.uri, combined, true);
            Toast.makeText(getApplicationContext(), "Long Screenshot stitched!", Toast.LENGTH_SHORT).show();
            return;
        }

        showAutoOpenHud(latest.uri, newBmp, false);
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            handleDisplayTopologyUpdate();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            handleDisplayTopologyUpdate();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            handleDisplayTopologyUpdate();
        }
    };

    private void handleDisplayTopologyUpdate() {
        Display active = MediaStoreHelper.findActiveDisplay(this);
        int nextId = active != null ? active.getDisplayId() : Display.DEFAULT_DISPLAY;
        boolean displaySwitched = (nextId != activeDisplayId);
        if (displaySwitched) {
            initUiWindowContext();
        }
        if (activeOverlayView != null && windowManager != null) {
            try {
                activeOverlayView.refreshDisplayDensity();
                activeLayoutParams = createUnderStatusBarFullBottomLayoutParams(!activeOverlayView.isWindowStartsBelowStatusBar());
                windowManager.updateViewLayout(activeOverlayView, activeLayoutParams);
            } catch (Exception ignored) {
            }
        }
    }

    private void initUiWindowContext() {
        overlayUiContext = this;
        try {
            Display display = MediaStoreHelper.findActiveDisplay(this);
            if (display != null) {
                activeDisplayId = display.getDisplayId();
                Context dispCtx = createDisplayContext(display);
                if (Build.VERSION.SDK_INT >= 30) {
                    overlayUiContext = dispCtx.createWindowContext(
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            null
                    );
                } else {
                    overlayUiContext = dispCtx;
                }
            }
        } catch (Exception ignored) {
            overlayUiContext = this;
        }
        windowManager = (WindowManager) overlayUiContext.getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
    }

    private WindowManager.LayoutParams createUnderStatusBarFullBottomLayoutParams(boolean safeFallback) {
        MediaStoreHelper.ScreenGeometry geo = MediaStoreHelper.getScreenGeometry(overlayUiContext, windowManager);
        int overlayHeight = Math.max(400, geo.screenHeightPx - geo.statusBarHeightPx);

        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (!safeFallback) {
            flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                safeFallback ? WindowManager.LayoutParams.MATCH_PARENT : geo.screenWidthPx,
                safeFallback ? WindowManager.LayoutParams.MATCH_PARENT : overlayHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 0;
        lp.y = safeFallback ? 0 : geo.statusBarHeightPx;

        if (!safeFallback) {
            if (Build.VERSION.SDK_INT >= 30) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                lp.setFitInsetsTypes(0);
            } else if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }
        return lp;
    }

    private void showAutoOpenHud(final Uri screenshotUri, Bitmap initialBitmap) {
        showAutoOpenHud(screenshotUri, initialBitmap, false);
    }

    private void showAutoOpenHud(final Uri screenshotUri, Bitmap initialBitmap, boolean initiallyStitched) {
        final Bitmap[] currentBmp = new Bitmap[]{initialBitmap};
        final boolean[] isStitched = new boolean[]{initiallyStitched};

        if (Settings.canDrawOverlays(this)) {
            removeActiveOverlay();
            initUiWindowContext();

            final FoldShotPrefs prefs = new FoldShotPrefs(this);
            activeOverlayView = new OneUiSmartCaptureView(overlayUiContext, prefs, new OneUiSmartCaptureView.Listener() {
                @Override
                public void onCopyAndDelete(RectF normalizedCrop) {
                    Bitmap cropped = MediaStoreHelper.cropBitmap(currentBmp[0], normalizedCrop);
                    MediaStoreHelper.copyBitmapToClipboard(getApplicationContext(), cropped);
                    MediaStoreHelper.deleteScreenshotSilently(getApplicationContext(), null, screenshotUri);
                    removeActiveOverlay();
                }

                @Override
                public void onSaveScreenshot(RectF normalizedCrop) {
                    boolean isFull = !isStitched[0]
                            && normalizedCrop.left <= 0.01f && normalizedCrop.top <= 0.01f
                            && normalizedCrop.right >= 0.99f && normalizedCrop.bottom >= 0.99f;
                    Bitmap cropped = MediaStoreHelper.cropBitmap(currentBmp[0], normalizedCrop);
                    MediaStoreHelper.saveCroppedScreenshot(
                            getApplicationContext(), null, screenshotUri, cropped, isFull
                    );
                    Toast.makeText(getApplicationContext(), "Saved to Screenshots", Toast.LENGTH_SHORT).show();
                    removeActiveOverlay();
                }

                @Override
                public void onMoveToAlbum(int slotIndex, String albumName, RectF normalizedCrop) {
                    if (!prefs.isFolderUsable(albumName)) {
                        Toast.makeText(getApplicationContext(), "Folder not enabled in Visible Folders", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean isFull = !isStitched[0]
                            && normalizedCrop.left <= 0.01f && normalizedCrop.top <= 0.01f
                            && normalizedCrop.right >= 0.99f && normalizedCrop.bottom >= 0.99f;
                    Bitmap cropped = MediaStoreHelper.cropBitmap(currentBmp[0], normalizedCrop);
                    MediaStoreHelper.moveBitmapToFavoriteAlbum(
                            getApplicationContext(), null, screenshotUri, cropped, albumName, isFull
                    );
                    Toast.makeText(getApplicationContext(), "Moved to " + albumName, Toast.LENGTH_SHORT).show();
                    removeActiveOverlay();
                }

                @Override
                public void onPickAlbumForSlot(int slotIndex) {
                    removeActiveOverlay();
                    Intent i = new Intent(getApplicationContext(), MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }

                @Override
                public void onTriggerScrollCapture() {
                    if (Uri.EMPTY.equals(screenshotUri)) {
                        Bitmap nextSlice = createSampleOneUiBitmap(2);
                        currentBmp[0] = MediaStoreHelper.stitchBitmapsVertically(currentBmp[0], nextSlice);
                        isStitched[0] = true;
                        activeOverlayView.setScreenshotBitmap(currentBmp[0]);
                        activeOverlayView.resetCrop();
                        Toast.makeText(getApplicationContext(), "Long Screenshot Stitched!", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    pendingStitchBitmap = currentBmp[0];
                    pendingStitchFirstUri = screenshotUri;
                    removeActiveOverlay();
                    Toast.makeText(
                            getApplicationContext(),
                            "Scroll down & take next screenshot (or tap system Scroll Capture) to auto-stitch!",
                            Toast.LENGTH_LONG
                    ).show();
                }

                @Override
                public void onShare(RectF normalizedCrop) {
                    Bitmap cropped = MediaStoreHelper.cropBitmap(currentBmp[0], normalizedCrop);
                    removeActiveOverlay();
                    MediaStoreHelper.shareBitmap(getApplicationContext(), cropped);
                }

                @Override
                public void onOpenSettings() {
                    removeActiveOverlay();
                    Intent i = new Intent(getApplicationContext(), MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }

                @Override
                public void onCloseOverlay() {
                    removeActiveOverlay();
                }

                @Override
                public void onDeviceConfigChanged() {
                    handleDisplayTopologyUpdate();
                }
            });
            activeOverlayView.setScreenshotBitmap(currentBmp[0]);

            try {
                activeOverlayView.setWindowStartsBelowStatusBar(true);
                activeLayoutParams = createUnderStatusBarFullBottomLayoutParams(false);
                windowManager.addView(activeOverlayView, activeLayoutParams);
                return;
            } catch (Exception firstErr) {
                try {
                    activeOverlayView.setWindowStartsBelowStatusBar(false);
                    activeLayoutParams = createUnderStatusBarFullBottomLayoutParams(true);
                    windowManager.addView(activeOverlayView, activeLayoutParams);
                    return;
                } catch (Exception ignored) {
                }
            }
        }

        Intent editorIntent = new Intent(getApplicationContext(), ScreenshotEditorActivity.class);
        editorIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        editorIntent.putExtra(ScreenshotEditorActivity.EXTRA_SCREENSHOT_URI, screenshotUri.toString());
        startActivity(editorIntent);
    }

    private void removeActiveOverlay() {
        if (activeOverlayView != null && windowManager != null) {
            try {
                windowManager.removeView(activeOverlayView);
            } catch (Exception ignored) {
            }
            activeOverlayView = null;
            activeLayoutParams = null;
        }
    }

    private void registerAllScreenshotWatchers() {
        try {
            getContentResolver().unregisterContentObserver(mediaObserver);
        } catch (Exception ignored) {
        }
        try {
            getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    mediaObserver
            );
        } catch (Exception ignored) {
        }

        for (FileObserver fo : dirObservers) {
            try {
                fo.stopWatching();
            } catch (Exception ignored) {
            }
        }
        dirObservers.clear();

        int mask = FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE;
        for (final File dir : MediaStoreHelper.getCommonScreenshotDirs(getApplicationContext())) {
            try {
                if (!dir.exists() || !dir.isDirectory()) continue;
                FileObserver obs = new FileObserver(dir, mask) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path == null || path.startsWith(".") || path.startsWith("FoldShot_")) return;
                        final File f = new File(dir, path);
                        try {
                            MediaScannerConnection.scanFile(
                                    getApplicationContext(),
                                    new String[]{f.getAbsolutePath()},
                                    null,
                                    (scannedPath, uri) -> handler.post(() -> scheduleInspect(uri))
                            );
                        } catch (Exception ignored) {
                        }
                        handler.post(() -> scheduleInspect(null));
                    }
                };
                obs.startWatching();
                dirObservers.add(obs);
            } catch (Exception ignored) {
            }
        }

        handler.removeCallbacks(interactiveHeartbeatRunnable);
        handler.postDelayed(interactiveHeartbeatRunnable, 1400L);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceStartSec = System.currentTimeMillis() / 1000L;
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) {
            try {
                displayManager.registerDisplayListener(displayListener, handler);
            } catch (Exception ignored) {
            }
        }
        initUiWindowContext();
        startForegroundNotification();
        registerAllScreenshotWatchers();
    }

    private void startForegroundNotification() {
        try {
            String channelId = "foldshot_zfold7_listener";
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "FoldShot Smart Capture Utility",
                    NotificationManager.IMPORTANCE_MIN
            );
            nm.createNotificationChannel(channel);

            Intent openIntent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new Notification.Builder(this, channelId)
                    .setContentTitle("FoldShot Smart Capture Active")
                    .setContentText("Auto-opens on screenshot • Tap to open Control Center")
                    .setSmallIcon(android.R.drawable.ic_menu_crop)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1001, notification);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();

        if (intent != null && ACTION_RELOAD_OBSERVER.equals(intent.getAction())) {
            registerAllScreenshotWatchers();
            return START_STICKY;
        }

        if (intent != null && ACTION_TEST_OVERLAY.equals(intent.getAction())) {
            registerAllScreenshotWatchers();
            MediaStoreHelper.ScreenshotData latest = MediaStoreHelper.queryLatestScreenshot(getApplicationContext(), null);
            if (latest != null) {
                Bitmap b = MediaStoreHelper.loadBitmap(getApplicationContext(), latest.uri);
                if (b != null) {
                    showAutoOpenHud(latest.uri, b);
                    return START_STICKY;
                }
            }
            showAutoOpenHud(Uri.EMPTY, createSampleOneUiBitmap(1));
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        registerAllScreenshotWatchers();
    }

    private Bitmap createSampleOneUiBitmap(int variant) {
        MediaStoreHelper.ScreenGeometry geo = MediaStoreHelper.getScreenGeometry(overlayUiContext, windowManager);
        int w = Math.max(720, geo.screenWidthPx);
        int h = Math.max(1280, geo.screenHeightPx - geo.statusBarHeightPx);
        float scale = w / 1080f;

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF101117, 0xFF171923, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        p.setShader(null);

        float pad = 54f * scale;
        p.setColor(0xFF1E202B);
        canvas.drawRoundRect(new RectF(pad, 90f * scale, w - pad, 490f * scale), 38f * scale, 38f * scale, p);
        p.setColor(0xFF8E8E93);
        p.setTextSize(28f * scale);
        canvas.drawText(variant == 1 ? "EXPRESS CHECKOUT • RECEIPT" : "CONTINUED SCROLL SECTION • PAGE 2", pad + 44f * scale, 175f * scale, p);
        p.setColor(0xFFF5F5F7);
        p.setTextSize(44f * scale);
        p.setFakeBoldText(true);
        canvas.drawText(variant == 1 ? "$142.80 — Order Confirmed" : "Order #99412 • Express Delivery", pad + 44f * scale, 265f * scale, p);
        p.setFakeBoldText(false);
        p.setColor(0xFF8E8E93);
        p.setTextSize(30f * scale);
        canvas.drawText("Drag 1 finger or spread 2 fingers anywhere to crop", pad + 44f * scale, 355f * scale, p);

        p.setColor(0xFF1E202B);
        canvas.drawRoundRect(new RectF(pad, 540f * scale, w * 0.485f, 980f * scale), 38f * scale, 38f * scale, p);
        p.setColor(0xFF387AFF);
        p.setTextSize(28f * scale);
        p.setFakeBoldText(true);
        canvas.drawText("BOARDING PASS", pad + 38f * scale, 625f * scale, p);
        p.setColor(0xFFF5F5F7);
        p.setTextSize(42f * scale);
        canvas.drawText("SFO → ICN", pad + 38f * scale, 720f * scale, p);
        p.setFakeBoldText(false);

        p.setColor(0xFF1E202B);
        canvas.drawRoundRect(new RectF(w * 0.515f, 540f * scale, w - pad, 980f * scale), 38f * scale, 38f * scale, p);
        p.setColor(0xFF2AD181);
        p.setTextSize(28f * scale);
        p.setFakeBoldText(true);
        canvas.drawText("REACHABLE SCALE", w * 0.515f + 38f * scale, 625f * scale, p);
        p.setFakeBoldText(false);
        p.setColor(0xFFE4E4E7);
        p.setTextSize(29f * scale);
        canvas.drawText("• 82% Thumb Zone", w * 0.515f + 38f * scale, 715f * scale, p);
        canvas.drawText("• All OEMs Ready", w * 0.515f + 38f * scale, 780f * scale, p);

        p.setColor(0xFF1E202B);
        canvas.drawRoundRect(new RectF(pad, 1030f * scale, w - pad, h - 90f * scale), 38f * scale, 38f * scale, p);
        p.setColor(0xFF5C95FF);
        p.setTextSize(28f * scale);
        p.setFakeBoldText(true);
        canvas.drawText("ACTIVE ON " + MediaStoreHelper.getDeviceFriendlyName().toUpperCase(), pad + 44f * scale, 1120f * scale, p);
        p.setFakeBoldText(false);
        p.setColor(0xFFA1A1AA);
        p.setTextSize(30f * scale);
        canvas.drawText("Triple-Engine Watcher + Quick Settings Tile Ready.", pad + 44f * scale, 1195f * scale, p);

        return bmp;
    }

    @Override
    public void onDestroy() {
        removeActiveOverlay();
        if (displayManager != null) {
            try {
                displayManager.unregisterDisplayListener(displayListener);
            } catch (Exception ignored) {
            }
        }
        handler.removeCallbacks(interactiveHeartbeatRunnable);
        for (FileObserver fo : dirObservers) {
            try {
                fo.stopWatching();
            } catch (Exception ignored) {
            }
        }
        dirObservers.clear();
        try {
            getContentResolver().unregisterContentObserver(mediaObserver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
