package com.foldshot.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Universal Play-Protect-Safe Background Screenshot Utility Service (v2.0.0):
 * - Encompasses Samsung One UI, Xiaomi MIUI/HyperOS, Infinix/Tecno XOS, OPPO/Realme/OnePlus ColorOS,
 *   Vivo/iQOO FuntouchOS/OriginOS, Huawei/Honor EMUI/MagicOS, Pixel, Motorola, Nothing, Sony, Asus.
 * - Triple-Engine Screenshot Detection:
 *   1) MediaStore ContentObserver (with direct hintUri & 2.5s IS_PENDING retry window)
 *   2) Modern FileObserver(File, mask) across all internal & external StorageVolume screenshot folders
 *   3) Interactive screen heartbeat check (catches screenshots even when OEM OS suppresses ContentObserver)
 * - Runs all MediaStore queries, disk scans, and Bitmap decoding on a background detectionExecutor.
 */
public class ScreenshotInterceptService extends Service {

    public static final String ACTION_TEST_OVERLAY = "com.foldshot.app.ACTION_TEST_OVERLAY";
    public static final String ACTION_RELOAD_OBSERVER = "com.foldshot.app.ACTION_RELOAD_OBSERVER";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService detectionExecutor = Executors.newSingleThreadExecutor();
    private final Object inspectLock = new Object();
    private volatile boolean serviceDestroyed = false;

    private DisplayManager displayManager;
    private int activeDisplayId = Display.DEFAULT_DISPLAY;
    private Context overlayUiContext;
    private WindowManager windowManager;
    private PowerManager powerManager;
    private OneUiSmartCaptureView activeOverlayView;
    private WindowManager.LayoutParams activeLayoutParams;
    private boolean screenOffReceiverRegistered = false;

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if (new FoldShotPrefs(ScreenshotInterceptService.this).isSecureOverlayEnabled()) {
                    removeActiveOverlay();
                }
            }
        }
    };

    private final List<FileObserver> dirObservers = new ArrayList<>();
    private volatile Uri latestHintUri = null;
    private volatile String lastHandledKey = null;
    private volatile Uri lastHandledUri = null;
    private volatile long lastHandledModSec = 0L;
    private volatile int lastHandledHeight = 0;
    private long serviceStartSec = 0L;
    private boolean hasPendingInspectSchedule = false;
    private boolean inspectionInFlight = false;
    private boolean queuedInspection = false;

    // Holds previous screenshot bitmap when user taps Scroll Capture to stitch multiple shots
    private volatile Bitmap pendingStitchBitmap = null;
    private volatile Uri pendingStitchFirstUri = null;

    private final Runnable checkMediaStoreRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (inspectLock) {
                hasPendingInspectSchedule = false;
            }
            inspectLatestScreenshotAsync(latestHintUri);
        }
    };

    private final Runnable interactiveHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (!serviceDestroyed && (powerManager == null || powerManager.isInteractive())) {
                    inspectLatestScreenshotAsync(null);
                    MediaStoreHelper.runAutoExpireCleanup(getApplicationContext(), false);
                }
            } catch (Exception ignored) {
            }
            if (!serviceDestroyed) {
                handler.postDelayed(this, 1400L);
            }
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
        if (serviceDestroyed) return;
        boolean hadNewHint = (hintUri != null);
        if (hadNewHint) {
            latestHintUri = hintUri;
        }
        synchronized (inspectLock) {
            if (hasPendingInspectSchedule && !hadNewHint) {
                return;
            }
            hasPendingInspectSchedule = true;
        }
        handler.removeCallbacks(checkMediaStoreRunnable);
        long[] delays = new long[]{50L, 180L, 420L, 850L, 1500L, 2500L};
        for (long d : delays) {
            handler.postDelayed(checkMediaStoreRunnable, d);
        }
    }

    private void inspectLatestScreenshotAsync(final Uri hintUri) {
        if (serviceDestroyed) return;
        synchronized (inspectLock) {
            if (inspectionInFlight) {
                queuedInspection = true;
                if (hintUri != null) {
                    latestHintUri = hintUri;
                }
                return;
            }
            inspectionInFlight = true;
        }
        final Uri effectiveHint = hintUri != null ? hintUri : latestHintUri;
        try {
            detectionExecutor.execute(() -> {
                try {
                    inspectLatestScreenshotWorker(effectiveHint);
                } finally {
                    boolean runAgain = false;
                    Uri nextHint = null;
                    synchronized (inspectLock) {
                        inspectionInFlight = false;
                        if (queuedInspection && !serviceDestroyed) {
                            queuedInspection = false;
                            runAgain = true;
                            nextHint = latestHintUri;
                        }
                    }
                    if (runAgain) {
                        inspectLatestScreenshotAsync(nextHint);
                    }
                }
            });
        } catch (Exception ignored) {
            synchronized (inspectLock) {
                inspectionInFlight = false;
            }
        }
    }

    private void inspectLatestScreenshotWorker(Uri hintUri) {
        if (serviceDestroyed) return;
        MediaStoreHelper.ScreenshotData latest = MediaStoreHelper.queryLatestScreenshot(getApplicationContext(), hintUri);
        if (latest == null) return;

        long nowSec = System.currentTimeMillis() / 1000L;
        boolean isRecent = (nowSec - latest.dateModifiedSec) <= 18L && latest.dateModifiedSec >= (serviceStartSec - 3L);
        if (!isRecent) return;

        String currentKey = (latest.filePath != null && !latest.filePath.isEmpty())
                ? latest.filePath
                : (latest.uri.toString() + "|" + latest.displayName);

        boolean isSameFile = currentKey.equals(lastHandledKey)
                || latest.uri.toString().equals(lastHandledKey)
                || (lastHandledUri != null && lastHandledUri.equals(latest.uri));
        boolean isUpdatedScrollCapture = isSameFile
                && (latest.dateModifiedSec > lastHandledModSec)
                && (latest.height > lastHandledHeight);

        if (isSameFile && !isUpdatedScrollCapture) return;

        Bitmap newBmp = MediaStoreHelper.loadBitmap(getApplicationContext(), latest.uri);
        if (newBmp == null || serviceDestroyed) return;

        final Bitmap displayBmp;
        final boolean didStitch;
        synchronized (inspectLock) {
            boolean duplicate = (currentKey.equals(lastHandledKey)
                    || latest.uri.toString().equals(lastHandledKey)
                    || (lastHandledUri != null && lastHandledUri.equals(latest.uri)))
                    && !((latest.dateModifiedSec > lastHandledModSec) && (latest.height > lastHandledHeight));
            if (duplicate) return;

            lastHandledKey = currentKey;
            lastHandledUri = latest.uri;
            lastHandledModSec = latest.dateModifiedSec;
            lastHandledHeight = Math.max(latest.height, newBmp.getHeight());

            if (pendingStitchBitmap != null && !isSameFile) {
                displayBmp = MediaStoreHelper.stitchBitmapsVertically(pendingStitchBitmap, newBmp);
                if (pendingStitchFirstUri != null && !pendingStitchFirstUri.equals(latest.uri)) {
                    MediaStoreHelper.deleteScreenshotSilently(getApplicationContext(), null, pendingStitchFirstUri);
                }
                pendingStitchBitmap = null;
                pendingStitchFirstUri = null;
                didStitch = true;
            } else {
                displayBmp = newBmp;
                didStitch = false;
            }
        }

        final Uri targetUri = latest.uri;
        handler.post(() -> {
            if (serviceDestroyed) return;
            showAutoOpenHud(targetUri, displayBmp, didStitch);
            if (didStitch) {
                Toast.makeText(getApplicationContext(), "Long Screenshot stitched!", Toast.LENGTH_SHORT).show();
            }
        });
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
        if (new FoldShotPrefs(this).isSecureOverlayEnabled()) {
            flags |= WindowManager.LayoutParams.FLAG_SECURE;
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

    private Bitmap cropAndApplyMarkups(OneUiSmartCaptureView view, Uri screenshotUri, Bitmap baseBmp, RectF normalizedCrop, boolean stitched) {
        Bitmap cropped = MediaStoreHelper.cropFullResolution(
                getApplicationContext(), screenshotUri, baseBmp, normalizedCrop, stitched
        );
        if (view != null && view.hasMarkups()) {
            cropped = view.renderMarkedCropBitmap(cropped, normalizedCrop);
        }
        return cropped;
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
                    Bitmap cropped = cropAndApplyMarkups(
                            activeOverlayView, screenshotUri, currentBmp[0], normalizedCrop, isStitched[0]
                    );
                    removeActiveOverlay();
                    MediaStoreHelper.copyBitmapToClipboard(getApplicationContext(), cropped);
                    MediaStoreHelper.deleteScreenshotSilently(getApplicationContext(), null, screenshotUri);
                }

                @Override
                public void onSaveScreenshot(RectF normalizedCrop) {
                    boolean hasMarkups = activeOverlayView != null && activeOverlayView.hasMarkups();
                    boolean isFull = !hasMarkups && !isStitched[0] && MediaStoreHelper.isFullCrop(normalizedCrop);
                    boolean useOriginalStream = isFull && !Uri.EMPTY.equals(screenshotUri);
                    Bitmap cropped = useOriginalStream
                            ? null
                            : cropAndApplyMarkups(
                                    activeOverlayView, screenshotUri, currentBmp[0], normalizedCrop, isStitched[0]
                            );
                    removeActiveOverlay();
                    boolean saved = MediaStoreHelper.saveCroppedScreenshot(
                            getApplicationContext(), null, screenshotUri, cropped, isFull
                    );
                    if (!saved && cropped == null) {
                        MediaStoreHelper.saveCroppedScreenshot(
                                getApplicationContext(), null, screenshotUri, currentBmp[0], false
                        );
                    }
                    Toast.makeText(getApplicationContext(), "Saved to Screenshots", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onMoveToAlbum(int slotIndex, String albumName, RectF normalizedCrop) {
                    if (!prefs.isFolderUsable(albumName)) {
                        Toast.makeText(getApplicationContext(), "Folder not enabled in Visible Folders", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean hasMarkups = activeOverlayView != null && activeOverlayView.hasMarkups();
                    boolean isFull = !hasMarkups && !isStitched[0] && MediaStoreHelper.isFullCrop(normalizedCrop);
                    boolean useOriginalStream = isFull && !Uri.EMPTY.equals(screenshotUri);
                    Bitmap cropped = useOriginalStream
                            ? null
                            : cropAndApplyMarkups(
                                    activeOverlayView, screenshotUri, currentBmp[0], normalizedCrop, isStitched[0]
                            );
                    removeActiveOverlay();
                    boolean moved = MediaStoreHelper.moveBitmapToFavoriteAlbum(
                            getApplicationContext(), null, screenshotUri, cropped, albumName, isFull
                    );
                    if (!moved && cropped == null) {
                        MediaStoreHelper.moveBitmapToFavoriteAlbum(
                                getApplicationContext(), null, screenshotUri, currentBmp[0], albumName, false
                        );
                    }
                    Toast.makeText(getApplicationContext(), "Moved to " + albumName, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onSaveToHiddenFolder(RectF normalizedCrop) {
                    boolean hasMarkups = activeOverlayView != null && activeOverlayView.hasMarkups();
                    boolean isFull = !hasMarkups && !isStitched[0] && MediaStoreHelper.isFullCrop(normalizedCrop);
                    boolean useOriginalStream = isFull && !Uri.EMPTY.equals(screenshotUri);
                    Bitmap cropped = useOriginalStream
                            ? null
                            : cropAndApplyMarkups(
                                    activeOverlayView, screenshotUri, currentBmp[0], normalizedCrop, isStitched[0]
                            );
                    removeActiveOverlay();
                    boolean ok = HiddenVaultManager.saveToVault(
                            getApplicationContext(),
                            useOriginalStream ? screenshotUri : null,
                            cropped,
                            isFull,
                            prefs.isStripMetadataEnabled()
                    );
                    if (ok) {
                        Toast.makeText(getApplicationContext(), "Saved privately", Toast.LENGTH_SHORT).show();
                        MediaStoreHelper.deleteScreenshotSilently(getApplicationContext(), null, screenshotUri);
                    } else {
                        Toast.makeText(getApplicationContext(), "Could not save privately", Toast.LENGTH_SHORT).show();
                    }
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
                    Bitmap cropped = cropAndApplyMarkups(
                            activeOverlayView, screenshotUri, currentBmp[0], normalizedCrop, isStitched[0]
                    );
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
        editorIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
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

        int mask = FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO;
        FoldShotPrefs prefs = new FoldShotPrefs(this);
        for (final File dir : MediaStoreHelper.getWatchedCaptureDirs(getApplicationContext(), prefs)) {
            try {
                if (!dir.exists() || !dir.isDirectory()) continue;
                FileObserver obs = new FileObserver(dir, mask) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path == null || path.startsWith(".") || path.startsWith("FoldShot_") || MediaStoreHelper.isOwnSavedOutput(null, path)) return;
                        String lowerPath = path.toLowerCase(java.util.Locale.US);
                        if (lowerPath.endsWith(".crdownload") || lowerPath.endsWith(".tmp") || lowerPath.endsWith(".part")) {
                            return;
                        }
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
        try {
            IntentFilter screenOffFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenOffReceiver, screenOffFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenOffReceiver, screenOffFilter);
            }
            screenOffReceiverRegistered = true;
        } catch (Exception ignored) {
        }
        initUiWindowContext();
        startForegroundNotification();
        registerAllScreenshotWatchers();
    }

    public static final String CHANNEL_ID = "foldshot_zfold7_listener";
    public static final int NOTIFICATION_ID = 1001;

    public static boolean isTrayNotificationMutedInSystem(Context context) {
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) return false;
            if (!nm.areNotificationsEnabled()) return true;
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
            return ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void startForegroundNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "FoldShot Smart Capture Utility",
                    NotificationManager.IMPORTANCE_MIN
            );
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }

            Intent openIntent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

            String summaryText = "Auto-opens on screenshot • Hide in Settings";
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("FoldShot Smart Capture Active")
                    .setContentText(summaryText)
                    .setStyle(new Notification.BigTextStyle().bigText(
                            "Auto-opens on screenshot • Tap to open Control Center (Hide in Settings)"
                    ))
                    .setSmallIcon(android.R.drawable.ic_menu_crop)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            FoldShotPrefs prefs = new FoldShotPrefs(this);
            if (prefs.isHideTrayNotificationEnabled() && !isTrayNotificationMutedInSystem(this)) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                if (nm != null) {
                    nm.cancel(NOTIFICATION_ID);
                }
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
            detectionExecutor.execute(() -> {
                MediaStoreHelper.ScreenshotData latest = MediaStoreHelper.queryLatestScreenshot(getApplicationContext(), null);
                Uri targetUri = Uri.EMPTY;
                Bitmap b = null;
                if (latest != null) {
                    b = MediaStoreHelper.loadBitmap(getApplicationContext(), latest.uri);
                    if (b != null) {
                        targetUri = latest.uri;
                    }
                }
                final Uri finalUri = targetUri;
                final Bitmap finalBmp = b != null ? b : createSampleOneUiBitmap(1);
                handler.post(() -> {
                    if (!serviceDestroyed) {
                        showAutoOpenHud(finalUri, finalBmp);
                    }
                });
            });
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
        serviceDestroyed = true;
        handler.removeCallbacks(checkMediaStoreRunnable);
        handler.removeCallbacks(interactiveHeartbeatRunnable);
        detectionExecutor.shutdownNow();
        removeActiveOverlay();
        if (screenOffReceiverRegistered) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (Exception ignored) {
            }
            screenOffReceiverRegistered = false;
        }
        if (displayManager != null) {
            try {
                displayManager.unregisterDisplayListener(displayListener);
            } catch (Exception ignored) {
            }
        }
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
