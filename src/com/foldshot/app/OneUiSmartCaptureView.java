package com.foldshot.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Full-Height Smart Capture Overlay with Thumb-Reachable Preview Area & 120Hz Touch-to-Crop Engine.
 *
 * - Overlay backdrop spans from immediately below the status bar all the way to the physical bottom edge of the screen.
 * - Screenshot preview area (imgBounds) is scaled down inside the safe thumb zone (default 82%, adjustable 75% / 82% / 90%)
 *   so all 4 corner handles and edges are effortless to reach with one hand.
 * - Includes small Expand Folders button on the left of the 3 pinned albums to reveal all Gallery albums in 1 tap.
 * - 100% custom vector iconography (zero emojis).
 */
public class OneUiSmartCaptureView extends View {

    public interface Listener {
        void onCopyAndDelete(RectF normalizedCrop);
        void onSaveScreenshot(RectF normalizedCrop);
        void onMoveToAlbum(int slotIndex, String albumName, RectF normalizedCrop);
        void onPickAlbumForSlot(int slotIndex);
        void onTriggerScrollCapture();
        void onShare(RectF normalizedCrop);
        void onOpenSettings();
        void onCloseOverlay();
        default void onDeviceConfigChanged() {}
    }

    private static class FolderChoice {
        final String name;
        final int itemCount;
        final int color;

        FolderChoice(String name, int itemCount, int color) {
            this.name = name;
            this.itemCount = itemCount;
            this.color = color;
        }
    }

    private final FoldShotPrefs prefs;
    private final Listener listener;
    private final WindowManager windowManager;
    private Bitmap screenshotBitmap;
    private float dp;

    // True when ScreenshotInterceptService already positions the window at y = statusBarHeightPx
    private boolean windowStartsBelowStatusBar = false;

    // Normalized crop box [0..1]
    private final RectF crop = new RectF(0f, 0f, 1f, 1f);
    private final RectF imgBounds = new RectF();

    // Hit rects for floating Smart Capture controls
    private final RectF cornerModeBtnRect = new RectF();
    private final RectF settingsBtnRect = new RectF();
    private final RectF resetBtnRect = new RectF();
    private final RectF saveScreenshotBtnRect = new RectF();
    private final RectF closeBtnRect = new RectF();
    private final RectF[] menuOptionRects = new RectF[]{new RectF(), new RectF(), new RectF(), new RectF()};
    private final RectF[] scaleOptionRects = new RectF[]{new RectF(), new RectF(), new RectF()};
    private final RectF[] albumPillRects = new RectF[]{new RectF(), new RectF(), new RectF()};
    private final RectF expandFoldersBtnRect = new RectF();
    private final RectF editAlbumsBtnRect = new RectF();
    private final RectF dragDockHandleRect = new RectF();
    private final RectF scrollCaptureBtnRect = new RectF();
    private final RectF copyDeleteBtnRect = new RectF();
    private final RectF shareBtnRect = new RectF();

    // Expandable "More Folders" drawer rects & state
    private final RectF folderDrawerRect = new RectF();
    private final RectF folderPrevPageRect = new RectF();
    private final RectF folderNextPageRect = new RectF();
    private final RectF folderManageLinkRect = new RectF();
    private final RectF[] drawerFolderPillRects = new RectF[]{
            new RectF(), new RectF(), new RectF(),
            new RectF(), new RectF(), new RectF(),
            new RectF(), new RectF(), new RectF()
    };
    private final List<FolderChoice> cachedExtraFolders = new ArrayList<>();
    private boolean showMoreFoldersDrawer = false;
    private int folderDrawerPage = 0;

    private boolean showCornerPopover = false;
    private boolean isDraggingCrop = false;
    private boolean isDraggingDockHeight = false;
    private boolean hasMovedDuringTouch = false;
    private int activeHandle = 0; // 0=new, 1=tl, 2=tr, 3=bl, 4=br
    private float downScreenX = 0f, downScreenY = 0f;
    private float anchorNormX = 0.5f, anchorNormY = 0.5f;
    private float touchScreenX = 0f, touchScreenY = 0f;
    private float dragStartY = 0f;
    private float initialClearanceDp = 18f;
    private float entranceProgress = 0f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Typeface mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface boldTypeface = Typeface.create("sans-serif", Typeface.BOLD);

    public OneUiSmartCaptureView(Context context, FoldShotPrefs prefs, Listener listener) {
        super(context);
        this.prefs = prefs;
        this.listener = listener;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        updateDensityFromContext();

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(200L);
        anim.setInterpolator(new DecelerateInterpolator(1.8f));
        anim.addUpdateListener(a -> {
            entranceProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @SuppressWarnings("deprecation")
    private void updateDensityFromContext() {
        float candidate = 0f;
        try {
            if (getContext() != null && getContext().getResources() != null) {
                candidate = getContext().getResources().getDisplayMetrics().density;
            }
            if (getDisplay() != null) {
                DisplayMetrics dm = new DisplayMetrics();
                getDisplay().getRealMetrics(dm);
                if (dm.density > 0.5f) {
                    candidate = dm.density;
                }
            } else if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                DisplayMetrics dm = new DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(dm);
                if (dm.density > 0.5f) {
                    candidate = dm.density;
                }
            }
        } catch (Exception ignored) {
        }
        if (candidate > 0.5f) {
            this.dp = candidate;
        } else if (this.dp <= 0.5f) {
            this.dp = 2.75f;
        }
    }

    public void refreshDisplayDensity() {
        updateDensityFromContext();
        invalidate();
    }

    public void setWindowStartsBelowStatusBar(boolean startsBelowStatusBar) {
        this.windowStartsBelowStatusBar = startsBelowStatusBar;
        invalidate();
    }

    public boolean isWindowStartsBelowStatusBar() {
        return windowStartsBelowStatusBar;
    }

    public void setScreenshotBitmap(Bitmap bmp) {
        this.screenshotBitmap = bmp;
        invalidate();
    }

    public RectF getNormalizedCrop() {
        return new RectF(crop);
    }

    public void resetCrop() {
        crop.set(0f, 0f, 1f, 1f);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateDensityFromContext();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateDensityFromContext();
        invalidate();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateDensityFromContext();
        listener.onDeviceConfigChanged();
        invalidate();
    }

    private float getTopOffsetUnderStatusBarPx() {
        if (windowStartsBelowStatusBar) {
            return 0f;
        }
        MediaStoreHelper.ScreenGeometry geo = MediaStoreHelper.getScreenGeometry(getContext(), windowManager);
        return geo.statusBarHeightPx;
    }

    private float getBottomNavInsetPx() {
        MediaStoreHelper.ScreenGeometry geo = MediaStoreHelper.getScreenGeometry(getContext(), windowManager);
        return Math.min(geo.navBarBottomInsetPx, 48f * dp);
    }

    /**
     * Scales the screenshot preview area (imgBounds) down inside the full-height overlay
     * using prefs.getPreviewScale() (default 0.82f) and positions it slightly toward the lower
     * thumb zone so all 4 corners and edges are comfortable to reach with one hand.
     */
    private void computeImgBounds() {
        float w = getWidth();
        float h = getHeight();
        float topOffset = getTopOffsetUnderStatusBarPx();
        float navBottomInset = getBottomNavInsetPx();

        float safeTop = topOffset + 54f * dp;
        float safeBottom = Math.max(safeTop + 100f * dp, h - navBottomInset - 108f * dp);
        float maxAvailW = Math.max(1f, w - 32f * dp);
        float maxAvailH = Math.max(1f, safeBottom - safeTop);

        float scaleFactor = prefs.getPreviewScale();
        float availW = maxAvailW * scaleFactor;
        float availH = maxAvailH * scaleFactor;

        if (screenshotBitmap != null) {
            float imgAspect = (float) screenshotBitmap.getWidth() / Math.max(1f, screenshotBitmap.getHeight());
            float viewAspect = availW / availH;
            float drawW, drawH;

            if (imgAspect > viewAspect) {
                drawW = availW;
                drawH = drawW / imgAspect;
            } else {
                drawH = availH;
                drawW = drawH * imgAspect;
            }

            float left = (w - drawW) / 2f;
            // Bias slightly downward (0.56f) toward the natural thumb reach zone
            float top = safeTop + (maxAvailH - drawH) * 0.56f;
            imgBounds.set(left, top, left + drawW, top + drawH);
        } else {
            float left = (w - availW) / 2f;
            float top = safeTop + (maxAvailH - availH) * 0.56f;
            imgBounds.set(left, top, left + availW, top + availH);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float topOffset = getTopOffsetUnderStatusBarPx();
        float navBottomInset = getBottomNavInsetPx();
        computeImgBounds();

        // 1. Full-Height Backdrop from immediately under the status bar all the way to the bottom of the screen (h)
        int bgAlpha = Math.round(230 * entranceProgress);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(bgAlpha, 8, 8, 11));
        canvas.drawRect(0f, topOffset, w, h, paint);

        // 2. Thumb-Reachable Screenshot Preview Card & Crop Frame
        if (screenshotBitmap != null) {
            canvas.save();
            float scale = 0.96f + 0.04f * entranceProgress;
            canvas.scale(scale, scale, imgBounds.centerX(), imgBounds.centerY());

            // Subtle elevated card shadow behind the scaled screenshot preview
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(Color.argb(Math.round(140 * entranceProgress), 0, 0, 0));
            RectF cardShadow = new RectF(imgBounds.left - 2f * dp, imgBounds.top + 4f * dp, imgBounds.right + 2f * dp, imgBounds.bottom + 6f * dp);
            canvas.drawRoundRect(cardShadow, 14f * dp, 14f * dp, shadowPaint);

            canvas.drawBitmap(screenshotBitmap, null, imgBounds, paint);

            float cx = imgBounds.left + crop.left * imgBounds.width();
            float cy = imgBounds.top + crop.top * imgBounds.height();
            float cr = imgBounds.left + crop.right * imgBounds.width();
            float cb = imgBounds.top + crop.bottom * imgBounds.height();

            // 3. Dimmed Scrim outside Crop Selection
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xAD000000);
            canvas.drawRect(imgBounds.left, imgBounds.top, imgBounds.right, cy, paint);
            canvas.drawRect(imgBounds.left, cb, imgBounds.right, imgBounds.bottom, paint);
            canvas.drawRect(imgBounds.left, cy, cx, cb, paint);
            canvas.drawRect(cr, cy, imgBounds.right, cb, paint);

            // 4. Subtle Rule-of-Thirds Grid (visible while adjusting crop or when cropped)
            boolean isCropped = crop.left > 0.01f || crop.top > 0.01f || crop.right < 0.99f || crop.bottom < 0.99f;
            if (isDraggingCrop || isCropped) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(0.8f * dp);
                paint.setColor(0x3DFFFFFF);
                for (int i = 1; i <= 2; i++) {
                    float gx = cx + ((cr - cx) / 3f) * i;
                    float gy = cy + ((cb - cy) / 3f) * i;
                    canvas.drawLine(gx, cy, gx, cb, paint);
                    canvas.drawLine(cx, gy, cr, gy, paint);
                }
            }

            // 5. Crisp One UI 7 Crop Border & Rounded L-Corner Handles
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xE0FFFFFF);
            paint.setStrokeWidth(1.5f * dp);
            canvas.drawRect(cx, cy, cr, cb, paint);

            float hLen = Math.min(22f * dp, Math.min(cr - cx, cb - cy) / 2.3f);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(4.0f * dp);
            paint.setStrokeCap(Paint.Cap.ROUND);
            // Top-Left
            canvas.drawLine(cx, cy, cx + hLen, cy, paint);
            canvas.drawLine(cx, cy, cx, cy + hLen, paint);
            // Top-Right
            canvas.drawLine(cr, cy, cr - hLen, cy, paint);
            canvas.drawLine(cr, cy, cr, cy + hLen, paint);
            // Bottom-Left
            canvas.drawLine(cx, cb, cx + hLen, cb, paint);
            canvas.drawLine(cx, cb, cx, cb - hLen, paint);
            // Bottom-Right
            canvas.drawLine(cr, cb, cr - hLen, cb, paint);
            canvas.drawLine(cr, cb, cr, cb - hLen, paint);
            canvas.restore();
        }

        int hudAlpha = Math.round(((isDraggingCrop && hasMovedDuringTouch) ? 55 : 255) * entranceProgress);

        // 6. Floating Top Bar (Just below the status bar): Left = Mode & Scale Pill, Right = Reset, Settings, Close
        float topBarY = topOffset + 10f * dp;
        cornerModeBtnRect.set(14f * dp, topBarY, 118f * dp, topBarY + 36f * dp);
        drawOneUiSurface(canvas, cornerModeBtnRect, 18f * dp, hudAlpha);

        drawSparkleIcon(canvas, cornerModeBtnRect.left + 17f * dp, cornerModeBtnRect.centerY(), 5.5f * dp, Color.argb(hudAlpha, 56, 122, 255));

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(mediumTypeface);
        paint.setColor(Color.argb(hudAlpha, 245, 245, 247));
        paint.setTextSize(12f * dp);
        String modeLabel = getCropModeShortLabel(prefs.getCropMode());
        canvas.drawText(modeLabel, cornerModeBtnRect.left + 29f * dp, cornerModeBtnRect.centerY() + 4.2f * dp, paint);

        drawChevronDown(canvas, cornerModeBtnRect.right - 15f * dp, cornerModeBtnRect.centerY(), 3.5f * dp, Color.argb(hudAlpha, 160, 160, 170));

        // Top-Right Circular Icon Buttons (Settings, Reset Crop, Save Screenshot, Close X)
        settingsBtnRect.set(w - 182f * dp, topBarY, w - 146f * dp, topBarY + 36f * dp);
        drawOneUiSurface(canvas, settingsBtnRect, 18f * dp, hudAlpha);
        drawSlidersSettingsIcon(canvas, settingsBtnRect.centerX(), settingsBtnRect.centerY(), 6.5f * dp, Color.argb(hudAlpha, 240, 240, 245));

        resetBtnRect.set(w - 138f * dp, topBarY, w - 102f * dp, topBarY + 36f * dp);
        drawOneUiSurface(canvas, resetBtnRect, 18f * dp, hudAlpha);
        drawResetArrowIcon(canvas, resetBtnRect.centerX(), resetBtnRect.centerY(), 6.5f * dp, Color.argb(hudAlpha, 240, 240, 245));

        saveScreenshotBtnRect.set(w - 94f * dp, topBarY, w - 58f * dp, topBarY + 36f * dp);
        drawOneUiSurface(canvas, saveScreenshotBtnRect, 18f * dp, hudAlpha);
        drawSaveScreenshotIcon(canvas, saveScreenshotBtnRect.centerX(), saveScreenshotBtnRect.centerY(), 6.5f * dp, Color.argb(hudAlpha, 240, 240, 245));

        closeBtnRect.set(w - 50f * dp, topBarY, w - 14f * dp, topBarY + 36f * dp);
        drawOneUiSurface(canvas, closeBtnRect, 18f * dp, hudAlpha);
        drawCloseIcon(canvas, closeBtnRect.centerX(), closeBtnRect.centerY(), 5.5f * dp, Color.argb(hudAlpha, 240, 240, 245));

        // 7. Bottom Native Smart Capture Floating Dock (near bottom of screen, draggable vertically)
        float clearancePx = prefs.getDockBottomClearanceDp() * dp;
        float mainBarBottom = Math.min(h - 10f * dp, h - navBottomInset - clearancePx + 12f * dp);
        float mainBarTop = mainBarBottom - 50f * dp;
        float albumsBarTop = mainBarTop - 44f * dp;

        // Row A: Slim Favorite Albums Capsule (Grip Handle + Small Expand Folders Btn + 3 Favorite Slots + Add/Edit)
        String[] albums = prefs.resolveTopThreeAlbums();
        float albumsBarW = Math.min(w - 20f * dp, 386f * dp);
        float albumsBarLeft = (w - albumsBarW) / 2f;
        RectF albumsBarRect = new RectF(albumsBarLeft, albumsBarTop, albumsBarLeft + albumsBarW, albumsBarTop + 36f * dp);
        drawOneUiSurface(canvas, albumsBarRect, 18f * dp, hudAlpha);

        dragDockHandleRect.set(albumsBarLeft + 3f * dp, albumsBarTop + 4f * dp, albumsBarLeft + 25f * dp, albumsBarTop + 32f * dp);
        drawGripLinesIcon(canvas, dragDockHandleRect.centerX(), dragDockHandleRect.centerY(), 4.5f * dp, Color.argb(hudAlpha, 142, 142, 147));

        // Small button strictly on the LEFT of the 3 folders to expand into more folder selections
        expandFoldersBtnRect.set(albumsBarLeft + 26f * dp, albumsBarTop + 4f * dp, albumsBarLeft + 52f * dp, albumsBarTop + 32f * dp);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(showMoreFoldersDrawer
                ? Color.argb(Math.min(hudAlpha, 235), 56, 122, 255)
                : Color.argb(Math.min(hudAlpha, 38), 255, 255, 255));
        canvas.drawRoundRect(expandFoldersBtnRect, 12f * dp, 12f * dp, paint);
        drawExpandFoldersIcon(
                canvas,
                expandFoldersBtnRect.centerX(),
                expandFoldersBtnRect.centerY(),
                4.5f * dp,
                showMoreFoldersDrawer,
                showMoreFoldersDrawer ? Color.argb(hudAlpha, 255, 255, 255) : Color.argb(hudAlpha, 228, 228, 234)
        );

        float slotW = (albumsBarW - 88f * dp) / 3f;
        int[] folderColors = new int[]{0xFF387AFF, 0xFF2AD181, 0xFFFFAB2E};
        int visibleSlotCount = 0;
        for (int i = 0; i < 3; i++) {
            float sl = albumsBarLeft + 56f * dp + i * slotW;
            String rawLabel = albums[i] != null ? albums[i].trim() : "";
            if (rawLabel.isEmpty() || !prefs.isFolderUsable(rawLabel)) {
                albumPillRects[i].setEmpty();
                continue;
            }
            visibleSlotCount++;
            albumPillRects[i].set(sl, albumsBarTop + 4f * dp, sl + slotW - 4f * dp, albumsBarTop + 32f * dp);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.min(hudAlpha, 32), 255, 255, 255));
            canvas.drawRoundRect(albumPillRects[i], 14f * dp, 14f * dp, paint);

            int fc = folderColors[i];
            drawMiniFolderIcon(
                    canvas,
                    albumPillRects[i].left + 12f * dp,
                    albumPillRects[i].centerY(),
                    4.8f * dp,
                    Color.argb(hudAlpha, Color.red(fc), Color.green(fc), Color.blue(fc))
            );

            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(mediumTypeface);
            paint.setColor(Color.argb(hudAlpha, 245, 245, 247));
            paint.setTextSize(10.8f * dp);
            String label = rawLabel;
            if (label.length() > 7) label = label.substring(0, 6) + "…";
            canvas.drawText(label, albumPillRects[i].left + 21f * dp, albumPillRects[i].centerY() + 3.8f * dp, paint);
        }
        if (visibleSlotCount == 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(mediumTypeface);
            paint.setColor(Color.argb(hudAlpha, 142, 142, 147));
            paint.setTextSize(11f * dp);
            canvas.drawText("No folders visible • Tap + to choose", albumsBarLeft + 64f * dp, albumsBarTop + 21.5f * dp, paint);
        }

        editAlbumsBtnRect.set(albumsBarRect.right - 30f * dp, albumsBarTop + 4f * dp, albumsBarRect.right - 4f * dp, albumsBarTop + 32f * dp);
        drawPlusIcon(canvas, editAlbumsBtnRect.centerX(), editAlbumsBtnRect.centerY(), 4.8f * dp, Color.argb(hudAlpha, 180, 180, 190));

        // Row B: Main Smart Capture Capsule
        float mainBarW = Math.min(w - 28f * dp, 288f * dp);
        float mainBarLeft = (w - mainBarW) / 2f;
        RectF mainBarRect = new RectF(mainBarLeft, mainBarTop, mainBarLeft + mainBarW, mainBarBottom);
        drawOneUiSurface(canvas, mainBarRect, 25f * dp, hudAlpha);

        scrollCaptureBtnRect.set(mainBarLeft + 8f * dp, mainBarTop + 5f * dp, mainBarLeft + 48f * dp, mainBarBottom - 5f * dp);
        drawSamsungScrollCaptureIcon(canvas, scrollCaptureBtnRect.centerX(), scrollCaptureBtnRect.centerY(), 10f * dp, hudAlpha);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(Math.min(hudAlpha, 34), 255, 255, 255));
        canvas.drawRect(mainBarLeft + 54f * dp, mainBarTop + 14f * dp, mainBarLeft + 55f * dp, mainBarBottom - 14f * dp, paint);

        copyDeleteBtnRect.set(mainBarLeft + 62f * dp, mainBarTop + 6f * dp, mainBarLeft + mainBarW - 58f * dp, mainBarBottom - 6f * dp);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(Math.min(hudAlpha, 255), 39, 39, 45));
        canvas.drawRoundRect(copyDeleteBtnRect, 19f * dp, 19f * dp, paint);

        float iconCenterY = copyDeleteBtnRect.centerY();
        drawCopyIcon(canvas, copyDeleteBtnRect.left + 18f * dp, iconCenterY, 5.8f * dp, Color.argb(hudAlpha, 245, 245, 247));
        drawTrashIcon(canvas, copyDeleteBtnRect.left + 32f * dp, iconCenterY, 5.5f * dp, Color.argb(hudAlpha, 255, 107, 107));

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(mediumTypeface);
        paint.setColor(Color.argb(hudAlpha, 245, 245, 247));
        paint.setTextSize(12f * dp);
        canvas.drawText("Copy & delete", copyDeleteBtnRect.left + 44f * dp, iconCenterY + 4.2f * dp, paint);

        paint.setColor(Color.argb(Math.min(hudAlpha, 34), 255, 255, 255));
        canvas.drawRect(mainBarLeft + mainBarW - 51f * dp, mainBarTop + 14f * dp, mainBarLeft + mainBarW - 50f * dp, mainBarBottom - 14f * dp, paint);

        shareBtnRect.set(mainBarLeft + mainBarW - 46f * dp, mainBarTop + 5f * dp, mainBarLeft + mainBarW - 6f * dp, mainBarBottom - 5f * dp);
        drawShareIcon(canvas, shareBtnRect.centerX(), shareBtnRect.centerY(), 8f * dp, hudAlpha);

        // 8. Floating Magnifier Loupe while dragging crop
        if (isDraggingCrop && hasMovedDuringTouch && prefs.isLoupeEnabled()) {
            float r = 42f * dp;
            float lx = Math.max(r + 14f * dp, Math.min(w - r - 14f * dp, touchScreenX));
            float ly = Math.max(topBarY + r + 10f * dp, touchScreenY - 70f * dp);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF17171A);
            canvas.drawCircle(lx, ly, r, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.4f * dp);
            paint.setColor(0xFF387AFF);
            canvas.drawLine(lx - r * 0.55f, ly, lx + r * 0.55f, ly, paint);
            canvas.drawLine(lx, ly - r * 0.55f, lx, ly + r * 0.55f, paint);

            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(2.8f * dp);
            canvas.drawCircle(lx, ly, r, paint);
        }

        // 9. Top-Left Corner Dropdown Menu (Crop Mode + Live Preview Scale Switcher)
        if (showCornerPopover) {
            RectF popRect = new RectF(14f * dp, topBarY + 42f * dp, 240f * dp, topBarY + 276f * dp);
            drawOneUiSurface(canvas, popRect, 20f * dp, 255);

            String[] modes = new String[]{"auto", "point-drag", "center-expand", "pinch-spread"};
            String[] titles = new String[]{
                    "Auto (1F Drag & 2F Spread)",
                    "Corner Point & Drag",
                    "Center-Out Expand",
                    "2-Finger Spread Only"
            };
            String activeMode = prefs.getCropMode();

            for (int i = 0; i < 4; i++) {
                float oy = popRect.top + 8f * dp + i * 38f * dp;
                menuOptionRects[i].set(popRect.left + 8f * dp, oy, popRect.right - 8f * dp, oy + 34f * dp);
                boolean selected = modes[i].equals(activeMode);
                if (selected) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0x26387AFF);
                    canvas.drawRoundRect(menuOptionRects[i], 12f * dp, 12f * dp, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(selected ? boldTypeface : mediumTypeface);
                paint.setColor(selected ? 0xFF5C95FF : 0xFFE4E4E7);
                paint.setTextSize(11.5f * dp);
                canvas.drawText(titles[i], menuOptionRects[i].left + 12f * dp, menuOptionRects[i].centerY() + 4f * dp, paint);
            }

            // Preview Scale Row inside popover ("Preview Size: 75% | 82% | 90%")
            float divY = popRect.top + 164f * dp;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF26262B);
            canvas.drawRect(popRect.left + 12f * dp, divY, popRect.right - 12f * dp, divY + 1f * dp, paint);

            paint.setColor(0xFF8E8E93);
            paint.setTypeface(mediumTypeface);
            paint.setTextSize(10.5f * dp);
            canvas.drawText("Preview Area Scale (Reachability)", popRect.left + 14f * dp, divY + 18f * dp, paint);

            float[] scales = new float[]{0.74f, 0.82f, 0.90f};
            String[] sLabels = new String[]{"75%", "82%", "90%"};
            float curScale = prefs.getPreviewScale();
            float pillW = (popRect.width() - 28f * dp) / 3f;
            float pillTop = divY + 26f * dp;

            for (int i = 0; i < 3; i++) {
                float pl = popRect.left + 10f * dp + i * (pillW + 4f * dp);
                scaleOptionRects[i].set(pl, pillTop, pl + pillW, pillTop + 30f * dp);
                boolean sel = Math.abs(curScale - scales[i]) < 0.04f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(sel ? 0xFF387AFF : 0xFF252529);
                canvas.drawRoundRect(scaleOptionRects[i], 10f * dp, 10f * dp, paint);

                paint.setColor(sel ? Color.WHITE : 0xFFA1A1AA);
                paint.setTypeface(sel ? boldTypeface : mediumTypeface);
                paint.setTextSize(11f * dp);
                float tw = paint.measureText(sLabels[i]);
                canvas.drawText(sLabels[i], scaleOptionRects[i].centerX() - tw / 2f, scaleOptionRects[i].centerY() + 4f * dp, paint);
            }
        }

        // 10. Expandable "More Folders" Floating Drawer above Row A (toggled by expandFoldersBtnRect)
        if (showMoreFoldersDrawer) {
            if (cachedExtraFolders.isEmpty()) {
                refreshExtraFolderChoices();
            }
            int total = cachedExtraFolders.size();
            int perPage = 9;
            int totalPages = Math.max(1, (total + perPage - 1) / perPage);
            if (folderDrawerPage >= totalPages) folderDrawerPage = 0;

            int startIdx = folderDrawerPage * perPage;
            int pageItems = Math.max(0, Math.min(perPage, total - startIdx));
            int rows = Math.max(1, (pageItems + 2) / 3);

            float headerH = 38f * dp;
            float rowH = 42f * dp;
            float footerH = totalPages > 1 ? 34f * dp : 12f * dp;
            float desiredH = headerH + rows * rowH + footerH;
            float maxAvailDrawerH = Math.max(120f * dp, albumsBarTop - (topBarY + 46f * dp));
            float drawerH = Math.min(desiredH, maxAvailDrawerH);

            folderDrawerRect.set(
                    albumsBarLeft,
                    albumsBarTop - 8f * dp - drawerH,
                    albumsBarLeft + albumsBarW,
                    albumsBarTop - 8f * dp
            );
            drawOneUiSurface(canvas, folderDrawerRect, 20f * dp, 255);

            // Header: "More Folders" (or "Visible Folders") + "Manage" badge
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(boldTypeface);
            paint.setColor(0xFFF5F5F7);
            paint.setTextSize(12f * dp);
            canvas.drawText(
                    prefs.isFolderAllowlistEnabled() ? "Visible Folders" : "More Folders",
                    folderDrawerRect.left + 16f * dp,
                    folderDrawerRect.top + 24f * dp,
                    paint
            );

            folderManageLinkRect.set(
                    folderDrawerRect.right - 98f * dp,
                    folderDrawerRect.top + 8f * dp,
                    folderDrawerRect.right - 12f * dp,
                    folderDrawerRect.top + 30f * dp
            );
            paint.setColor(0x26387AFF);
            canvas.drawRoundRect(folderManageLinkRect, 11f * dp, 11f * dp, paint);
            paint.setTypeface(mediumTypeface);
            paint.setColor(0xFF5C95FF);
            paint.setTextSize(10.5f * dp);
            String manageLbl = prefs.isFolderAllowlistEnabled() ? "Select Folders" : "Edit Top 3";
            float mlw = paint.measureText(manageLbl);
            canvas.drawText(manageLbl, folderManageLinkRect.centerX() - mlw / 2f, folderManageLinkRect.centerY() + 3.6f * dp, paint);

            // Grid of 3 columns x up to 3 rows
            float gridLeft = folderDrawerRect.left + 12f * dp;
            float gridTop = folderDrawerRect.top + headerH;
            float colGap = 6f * dp;
            float colW = (folderDrawerRect.width() - 24f * dp - colGap * 2f) / 3f;

            for (int i = 0; i < 9; i++) {
                drawerFolderPillRects[i].setEmpty();
            }

            if (pageItems == 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(mediumTypeface);
                paint.setColor(0xFF8E8E93);
                paint.setTextSize(11.2f * dp);
                String emptyMsg = prefs.getAllowedFolders().isEmpty()
                        ? "No folders enabled. Tap 'Select Folders' above."
                        : "All selected folders are already shown below.";
                canvas.drawText(emptyMsg, gridLeft + 4f * dp, gridTop + 20f * dp, paint);
            } else {
                for (int i = 0; i < pageItems; i++) {
                    FolderChoice fc = cachedExtraFolders.get(startIdx + i);
                    int r = i / 3;
                    int c = i % 3;
                    float pl = gridLeft + c * (colW + colGap);
                    float pt = gridTop + r * rowH;
                    float pb = pt + 34f * dp;
                    if (pb > folderDrawerRect.bottom - footerH + 2f * dp) break;

                    drawerFolderPillRects[i].set(pl, pt, pl + colW, pb);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xFF25252A);
                    canvas.drawRoundRect(drawerFolderPillRects[i], 13f * dp, 13f * dp, paint);

                    drawMiniFolderIcon(canvas, pl + 13f * dp, drawerFolderPillRects[i].centerY(), 4.8f * dp, fc.color);

                    paint.setStyle(Paint.Style.FILL);
                    paint.setTypeface(mediumTypeface);
                    paint.setColor(0xFFF5F5F7);
                    paint.setTextSize(10.8f * dp);
                    String lbl = fc.name;
                    if (lbl.length() > 9) lbl = lbl.substring(0, 8) + "…";
                    canvas.drawText(lbl, pl + 22f * dp, drawerFolderPillRects[i].centerY() + 3.6f * dp, paint);
                }
            }

            // Optional Pagination Footer (< Page X/Y >)
            folderPrevPageRect.setEmpty();
            folderNextPageRect.setEmpty();
            if (totalPages > 1) {
                float fy = folderDrawerRect.bottom - 28f * dp;
                folderPrevPageRect.set(folderDrawerRect.left + 14f * dp, fy, folderDrawerRect.left + 74f * dp, fy + 22f * dp);
                folderNextPageRect.set(folderDrawerRect.right - 74f * dp, fy, folderDrawerRect.right - 14f * dp, fy + 22f * dp);

                paint.setColor(0xFF26262C);
                canvas.drawRoundRect(folderPrevPageRect, 11f * dp, 11f * dp, paint);
                canvas.drawRoundRect(folderNextPageRect, 11f * dp, 11f * dp, paint);

                paint.setColor(0xFFE4E4E7);
                paint.setTextSize(10.5f * dp);
                paint.setTypeface(mediumTypeface);
                String prevTxt = "‹ Prev";
                String nextTxt = "Next ›";
                canvas.drawText(prevTxt, folderPrevPageRect.centerX() - paint.measureText(prevTxt) / 2f, folderPrevPageRect.centerY() + 3.6f * dp, paint);
                canvas.drawText(nextTxt, folderNextPageRect.centerX() - paint.measureText(nextTxt) / 2f, folderNextPageRect.centerY() + 3.6f * dp, paint);

                String pageInfo = (folderDrawerPage + 1) + " / " + totalPages;
                paint.setColor(0xFF8E8E93);
                canvas.drawText(pageInfo, folderDrawerRect.centerX() - paint.measureText(pageInfo) / 2f, fy + 15f * dp, paint);
            }
        }
    }

    private void refreshExtraFolderChoices() {
        cachedExtraFolders.clear();
        String[] top3 = prefs.resolveTopThreeAlbums();
        Set<String> excluded = new LinkedHashSet<>();
        for (String s : top3) {
            if (s != null && !s.trim().isEmpty()) {
                excluded.add(s.trim().toLowerCase(Locale.US));
            }
        }

        boolean allowlistOnly = prefs.isFolderAllowlistEnabled();
        int[] palette = new int[]{0xFF387AFF, 0xFF2AD181, 0xFFFFAB2E, 0xFFA78BFA, 0xFF38BDF8, 0xFFFB7185};
        int colorIdx = 0;

        List<MediaStoreHelper.GalleryAlbumInfo> albums = MediaStoreHelper.queryDeviceGalleryAlbums(getContext());
        for (MediaStoreHelper.GalleryAlbumInfo info : albums) {
            if (info.bucketName == null) continue;
            String trimmed = info.bucketName.trim();
            String key = trimmed.toLowerCase(Locale.US);
            if (excluded.contains(key)) continue;
            if (allowlistOnly && !prefs.isFolderUsable(trimmed)) continue;
            excluded.add(key);
            cachedExtraFolders.add(new FolderChoice(trimmed, info.itemCount, palette[colorIdx % palette.length]));
            colorIdx++;
        }

        if (allowlistOnly) {
            for (String allowed : prefs.getAllowedFolders()) {
                String trimmed = allowed != null ? allowed.trim() : "";
                if (trimmed.isEmpty()) continue;
                String key = trimmed.toLowerCase(Locale.US);
                if (excluded.contains(key)) continue;
                excluded.add(key);
                cachedExtraFolders.add(new FolderChoice(trimmed, 0, palette[colorIdx % palette.length]));
                colorIdx++;
            }
            return;
        }

        String[] suggestions = new String[]{"Receipts", "Work", "Travel", "IDs", "Design", "Shopping", "Finance", "Notes", "Personal"};
        for (String sug : suggestions) {
            if (cachedExtraFolders.size() >= 9) break;
            String key = sug.toLowerCase(Locale.US);
            if (excluded.contains(key)) continue;
            excluded.add(key);
            cachedExtraFolders.add(new FolderChoice(sug, 0, palette[colorIdx % palette.length]));
            colorIdx++;
        }
    }

    private void drawExpandFoldersIcon(Canvas canvas, float cx, float cy, float s, boolean expanded, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(1.7f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        if (expanded) {
            // Downward collapse chevron
            path.reset();
            path.moveTo(cx - s * 0.9f, cy - s * 0.35f);
            path.lineTo(cx, cy + s * 0.55f);
            path.lineTo(cx + s * 0.9f, cy - s * 0.35f);
            canvas.drawPath(path, paint);
        } else {
            // Compact 2x2 More-Folders grid + subtle upward chevron feel
            float gap = s * 0.25f;
            float box = s * 0.72f;
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(cx - gap - box, cy - gap - box, cx - gap, cy - gap), 1.4f * dp, 1.4f * dp, paint);
            canvas.drawRoundRect(new RectF(cx + gap, cy - gap - box, cx + gap + box, cy - gap), 1.4f * dp, 1.4f * dp, paint);
            canvas.drawRoundRect(new RectF(cx - gap - box, cy + gap, cx - gap, cy + gap + box), 1.4f * dp, 1.4f * dp, paint);
            canvas.drawRoundRect(new RectF(cx + gap, cy + gap, cx + gap + box, cy + gap + box), 1.4f * dp, 1.4f * dp, paint);
        }
    }

    private void drawOneUiSurface(Canvas canvas, RectF rect, float radius, int alpha) {
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(Color.argb(Math.min(alpha, 120), 0, 0, 0));
        RectF shadowRect = new RectF(rect.left, rect.top + 3f * dp, rect.right, rect.bottom + 3f * dp);
        canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(Math.min(alpha, 244), 23, 23, 26));
        canvas.drawRoundRect(rect, radius, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.8f * dp);
        paint.setColor(Color.argb(Math.min(alpha, 42), 255, 255, 255));
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    private void drawSamsungScrollCaptureIcon(Canvas canvas, float cx, float cy, float s, int alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.argb(alpha, 245, 245, 247));
        paint.setStrokeWidth(1.8f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        path.reset();
        path.moveTo(cx - s, cy - s * 0.35f);
        path.lineTo(cx - s, cy - s);
        path.lineTo(cx - s * 0.35f, cy - s);

        path.moveTo(cx + s * 0.35f, cy - s);
        path.lineTo(cx + s, cy - s);
        path.lineTo(cx + s, cy - s * 0.35f);

        path.moveTo(cx - s, cy + s * 0.35f);
        path.lineTo(cx - s, cy + s);
        path.lineTo(cx - s * 0.35f, cy + s);

        path.moveTo(cx + s * 0.35f, cy + s);
        path.lineTo(cx + s, cy + s);
        path.lineTo(cx + s, cy + s * 0.35f);

        path.moveTo(cx - s * 0.40f, cy - s * 0.30f);
        path.lineTo(cx, cy + s * 0.08f);
        path.lineTo(cx + s * 0.40f, cy - s * 0.30f);

        path.moveTo(cx - s * 0.40f, cy + s * 0.22f);
        path.lineTo(cx, cy + s * 0.60f);
        path.lineTo(cx + s * 0.40f, cy + s * 0.22f);

        canvas.drawPath(path, paint);
    }

    private void drawCopyIcon(Canvas canvas, float cx, float cy, float s, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(1.6f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        RectF front = new RectF(cx - s * 0.5f, cy - s * 0.4f, cx + s * 0.85f, cy + s * 0.95f);
        canvas.drawRoundRect(front, 2f * dp, 2f * dp, paint);

        path.reset();
        path.moveTo(cx - s * 0.5f, cy + s * 0.4f);
        path.lineTo(cx - s * 0.85f, cy + s * 0.4f);
        path.lineTo(cx - s * 0.85f, cy - s * 0.85f);
        path.lineTo(cx + s * 0.4f, cy - s * 0.85f);
        path.lineTo(cx + s * 0.4f, cy - s * 0.4f);
        canvas.drawPath(path, paint);
    }

    private void drawTrashIcon(Canvas canvas, float cx, float cy, float s, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(1.6f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        canvas.drawLine(cx - s * 0.85f, cy - s * 0.45f, cx + s * 0.85f, cy - s * 0.45f, paint);
        canvas.drawLine(cx - s * 0.35f, cy - s * 0.8f, cx + s * 0.35f, cy - s * 0.8f, paint);
        RectF bin = new RectF(cx - s * 0.62f, cy - s * 0.45f, cx + s * 0.62f, cy + s * 0.88f);
        canvas.drawRoundRect(bin, 1.8f * dp, 1.8f * dp, paint);
    }

    private void drawMiniFolderIcon(Canvas canvas, float cx, float cy, float s, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        path.reset();
        path.moveTo(cx - s, cy - s * 0.7f);
        path.lineTo(cx - s * 0.2f, cy - s * 0.7f);
        path.lineTo(cx + s * 0.1f, cy - s * 0.35f);
        path.lineTo(cx + s, cy - s * 0.35f);
        path.lineTo(cx + s, cy + s * 0.75f);
        path.lineTo(cx - s, cy + s * 0.75f);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawSparkleIcon(Canvas canvas, float cx, float cy, float s, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        path.reset();
        path.moveTo(cx, cy - s);
        path.quadTo(cx, cy, cx + s, cy);
        path.quadTo(cx, cy, cx, cy + s);
        path.quadTo(cx, cy, cx - s, cy);
        path.quadTo(cx, cy, cx, cy - s);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawChevronDown(Canvas canvas, float cx, float cy, float s, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(1.6f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        path.reset();
        path.moveTo(cx - s, cy - s * 0.4f);
        path.lineTo(cx, cy + s * 0.5f);
        path.lineTo(cx + s, cy - s * 0.4f);
        canvas.drawPath(path, paint);
    }

    private void drawCloseIcon(Canvas canvas, float cx, float cy, float s, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(1.8f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - s, cy - s, cx + s, cy + s, paint);
        canvas.drawLine(cx + s, cy - s, cx - s, cy + s, paint);
    }

    private void drawResetArrowIcon(Canvas canvas, float cx, float cy, float s, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(1.8f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        float r = s * 0.82f;
        float ocy = cy + s * 0.08f;
        float topY = ocy - r;
        RectF oval = new RectF(cx - r, ocy - r, cx + r, ocy + r);
        canvas.drawArc(oval, -90f, 285f, false, paint);

        float tipX = cx - s * 0.18f;
        canvas.drawLine(cx, topY, tipX, topY, paint);

        path.reset();
        path.moveTo(tipX + s * 0.48f, topY - s * 0.44f);
        path.lineTo(tipX, topY);
        path.lineTo(tipX + s * 0.48f, topY + s * 0.44f);
        canvas.drawPath(path, paint);
    }

    private void drawSaveScreenshotIcon(Canvas canvas, float cx, float cy, float s, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(1.8f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        // Downward arrow shaft & chevron
        canvas.drawLine(cx, cy - s * 0.82f, cx, cy + s * 0.28f, paint);
        path.reset();
        path.moveTo(cx - s * 0.48f, cy - s * 0.16f);
        path.lineTo(cx, cy + s * 0.32f);
        path.lineTo(cx + s * 0.48f, cy - s * 0.16f);
        canvas.drawPath(path, paint);

        // Bottom tray
        path.reset();
        path.moveTo(cx - s * 0.82f, cy + s * 0.25f);
        path.lineTo(cx - s * 0.82f, cy + s * 0.80f);
        path.lineTo(cx + s * 0.82f, cy + s * 0.80f);
        path.lineTo(cx + s * 0.82f, cy + s * 0.25f);
        canvas.drawPath(path, paint);
    }

    private void drawSlidersSettingsIcon(Canvas canvas, float cx, float cy, float s, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(1.6f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - s, cy - s * 0.55f, cx + s, cy - s * 0.55f, paint);
        canvas.drawLine(cx - s, cy + s * 0.55f, cx + s, cy + s * 0.55f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx - s * 0.3f, cy - s * 0.55f, 2.3f * dp, paint);
        canvas.drawCircle(cx + s * 0.35f, cy + s * 0.55f, 2.3f * dp, paint);
    }

    private void drawGripLinesIcon(Canvas canvas, float cx, float cy, float s, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(1.6f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - s * 0.7f, cy - 2.5f * dp, cx + s * 0.7f, cy - 2.5f * dp, paint);
        canvas.drawLine(cx - s * 0.7f, cy + 2.5f * dp, cx + s * 0.7f, cy + 2.5f * dp, paint);
    }

    private void drawPlusIcon(Canvas canvas, float cx, float cy, float s, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(1.8f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - s, cy, cx + s, cy, paint);
        canvas.drawLine(cx, cy - s, cx, cy + s, paint);
    }

    private void drawShareIcon(Canvas canvas, float cx, float cy, float s, int alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.argb(alpha, 245, 245, 247));
        paint.setStrokeWidth(1.7f * dp);
        canvas.drawLine(cx - s * 0.55f, cy, cx + s * 0.55f, cy - s * 0.6f, paint);
        canvas.drawLine(cx - s * 0.55f, cy, cx + s * 0.55f, cy + s * 0.6f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx - s * 0.55f, cy, 2.4f * dp, paint);
        canvas.drawCircle(cx + s * 0.55f, cy - s * 0.6f, 2.4f * dp, paint);
        canvas.drawCircle(cx + s * 0.55f, cy + s * 0.6f, 2.4f * dp, paint);
    }

    private String getCropModeShortLabel(String mode) {
        if ("point-drag".equals(mode)) return "Corner";
        if ("center-expand".equals(mode)) return "Center";
        if ("pinch-spread".equals(mode)) return "2-Finger";
        return "Auto";
    }

    private float snapVal(float v) {
        if (!prefs.isMagneticSnapEnabled()) return Math.max(0f, Math.min(1f, v));
        float[] targets = new float[]{0f, 0.05f, 0.333f, 0.5f, 0.667f, 0.95f, 1f};
        for (float t : targets) {
            if (Math.abs(v - t) < 0.018f) return t;
        }
        return Math.max(0f, Math.min(1f, v));
    }

    private float toNormX(float sx) {
        return snapVal((sx - imgBounds.left) / Math.max(1f, imgBounds.width()));
    }

    private float toNormY(float sy) {
        return snapVal((sy - imgBounds.top) / Math.max(1f, imgBounds.height()));
    }

    private int detectCornerHandle(float x, float y) {
        float cx = imgBounds.left + crop.left * imgBounds.width();
        float cy = imgBounds.top + crop.top * imgBounds.height();
        float cr = imgBounds.left + crop.right * imgBounds.width();
        float cb = imgBounds.top + crop.bottom * imgBounds.height();
        float r = 36f * dp;
        if (Math.hypot(x - cx, y - cy) <= r) return 1;
        if (Math.hypot(x - cr, y - cy) <= r) return 2;
        if (Math.hypot(x - cx, y - cb) <= r) return 3;
        if (Math.hypot(x - cr, y - cb) <= r) return 4;
        return 0;
    }

    private boolean handleTapReleaseOnControls(float x, float y) {
        if (cornerModeBtnRect.contains(x, y)) {
            showCornerPopover = !showCornerPopover;
            if (showCornerPopover) showMoreFoldersDrawer = false;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
            return true;
        }
        if (expandFoldersBtnRect.contains(x, y)) {
            showMoreFoldersDrawer = !showMoreFoldersDrawer;
            if (showMoreFoldersDrawer) {
                showCornerPopover = false;
                folderDrawerPage = 0;
                refreshExtraFolderChoices();
            }
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
            return true;
        }
        if (closeBtnRect.contains(x, y)) {
            listener.onCloseOverlay();
            return true;
        }
        if (saveScreenshotBtnRect.contains(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onSaveScreenshot(getNormalizedCrop());
            return true;
        }
        if (resetBtnRect.contains(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            resetCrop();
            return true;
        }
        if (settingsBtnRect.contains(x, y) || editAlbumsBtnRect.contains(x, y)) {
            listener.onOpenSettings();
            return true;
        }
        if (copyDeleteBtnRect.contains(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onCopyAndDelete(getNormalizedCrop());
            return true;
        }
        if (scrollCaptureBtnRect.contains(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            listener.onTriggerScrollCapture();
            return true;
        }
        if (shareBtnRect.contains(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            listener.onShare(getNormalizedCrop());
            return true;
        }
        String[] albums = prefs.resolveTopThreeAlbums();
        for (int i = 0; i < 3; i++) {
            if (!albumPillRects[i].isEmpty() && albumPillRects[i].contains(x, y) && prefs.isFolderUsable(albums[i])) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                listener.onMoveToAlbum(i, albums[i], getNormalizedCrop());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            downScreenX = x;
            downScreenY = y;

            if (showCornerPopover) {
                String[] modes = new String[]{"auto", "point-drag", "center-expand", "pinch-spread"};
                for (int i = 0; i < 4; i++) {
                    if (menuOptionRects[i].contains(x, y)) {
                        prefs.setCropMode(modes[i]);
                        showCornerPopover = false;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        invalidate();
                        return true;
                    }
                }
                float[] scales = new float[]{0.74f, 0.82f, 0.90f};
                for (int i = 0; i < 3; i++) {
                    if (scaleOptionRects[i].contains(x, y)) {
                        prefs.setPreviewScale(scales[i]);
                        showCornerPopover = false;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        invalidate();
                        return true;
                    }
                }
                showCornerPopover = false;
                invalidate();
                return true;
            }

            if (showMoreFoldersDrawer) {
                int startIdx = folderDrawerPage * 9;
                for (int i = 0; i < 9; i++) {
                    if (drawerFolderPillRects[i].contains(x, y) && (startIdx + i) < cachedExtraFolders.size()) {
                        FolderChoice chosen = cachedExtraFolders.get(startIdx + i);
                        showMoreFoldersDrawer = false;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        listener.onMoveToAlbum(-1, chosen.name, getNormalizedCrop());
                        return true;
                    }
                }
                int totalPages = Math.max(1, (cachedExtraFolders.size() + 8) / 9);
                if (folderPrevPageRect.contains(x, y)) {
                    folderDrawerPage = (folderDrawerPage - 1 + totalPages) % totalPages;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                    return true;
                }
                if (folderNextPageRect.contains(x, y)) {
                    folderDrawerPage = (folderDrawerPage + 1) % totalPages;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                    return true;
                }
                if (folderManageLinkRect.contains(x, y)) {
                    showMoreFoldersDrawer = false;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    listener.onOpenSettings();
                    return true;
                }
                if (expandFoldersBtnRect.contains(x, y)) {
                    showMoreFoldersDrawer = false;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                    return true;
                }
                String[] albums = prefs.resolveTopThreeAlbums();
                for (int i = 0; i < 3; i++) {
                    if (!albumPillRects[i].isEmpty() && albumPillRects[i].contains(x, y) && prefs.isFolderUsable(albums[i])) {
                        showMoreFoldersDrawer = false;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        listener.onMoveToAlbum(i, albums[i], getNormalizedCrop());
                        return true;
                    }
                }
                showMoreFoldersDrawer = false;
                invalidate();
                return true;
            }

            if (dragDockHandleRect.contains(x, y)) {
                isDraggingDockHeight = true;
                dragStartY = y;
                initialClearanceDp = prefs.getDockBottomClearanceDp();
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                return true;
            }

            isDraggingCrop = true;
            hasMovedDuringTouch = false;
            touchScreenX = x;
            touchScreenY = y;
            activeHandle = detectCornerHandle(x, y);
            anchorNormX = toNormX(x);
            anchorNormY = toNormY(y);
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE && isDraggingDockHeight) {
            float deltaDp = (dragStartY - y) / dp;
            prefs.setDockBottomClearanceDp(initialClearanceDp + deltaDp);
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN || (action == MotionEvent.ACTION_MOVE && isDraggingCrop)) {
            touchScreenX = x;
            touchScreenY = y;
            String mode = prefs.getCropMode();

            if (event.getPointerCount() >= 2 && ("auto".equals(mode) || "pinch-spread".equals(mode))) {
                hasMovedDuringTouch = true;
                float x1 = toNormX(event.getX(0));
                float y1 = toNormY(event.getY(0));
                float x2 = toNormX(event.getX(1));
                float y2 = toNormY(event.getY(1));

                crop.left = Math.min(x1, x2);
                crop.right = Math.max(x1, x2);
                if (crop.right - crop.left < 0.05f) crop.right = Math.min(1f, crop.left + 0.05f);
                crop.top = Math.min(y1, y2);
                crop.bottom = Math.max(y1, y2);
                if (crop.bottom - crop.top < 0.05f) crop.bottom = Math.min(1f, crop.top + 0.05f);
                invalidate();
                return true;
            }

            if (Math.hypot(x - downScreenX, y - downScreenY) > 10f * dp) {
                if (!hasMovedDuringTouch) {
                    hasMovedDuringTouch = true;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
            if (!hasMovedDuringTouch) return true;

            float nx = toNormX(x);
            float ny = toNormY(y);

            if (activeHandle == 1) {
                crop.left = Math.min(nx, crop.right - 0.05f);
                crop.top = Math.min(ny, crop.bottom - 0.05f);
            } else if (activeHandle == 2) {
                crop.right = Math.max(nx, crop.left + 0.05f);
                crop.top = Math.min(ny, crop.bottom - 0.05f);
            } else if (activeHandle == 3) {
                crop.left = Math.min(nx, crop.right - 0.05f);
                crop.bottom = Math.max(ny, crop.top + 0.05f);
            } else if (activeHandle == 4) {
                crop.right = Math.max(nx, crop.left + 0.05f);
                crop.bottom = Math.max(ny, crop.top + 0.05f);
            } else if ("center-expand".equals(mode)) {
                float dx = Math.max(0.04f, Math.abs(nx - anchorNormX));
                float dy = Math.max(0.04f, Math.abs(ny - anchorNormY));
                crop.left = Math.max(0f, anchorNormX - dx);
                crop.right = Math.min(1f, anchorNormX + dx);
                crop.top = Math.max(0f, anchorNormY - dy);
                crop.bottom = Math.min(1f, anchorNormY + dy);
            } else if (!"pinch-spread".equals(mode)) {
                crop.left = Math.min(anchorNormX, nx);
                crop.right = Math.max(anchorNormX, nx);
                if (crop.right - crop.left < 0.05f) crop.right = Math.min(1f, crop.left + 0.05f);
                crop.top = Math.min(anchorNormY, ny);
                crop.bottom = Math.max(anchorNormY, ny);
                if (crop.bottom - crop.top < 0.05f) crop.bottom = Math.min(1f, crop.top + 0.05f);
            }
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean wasTap = !hasMovedDuringTouch && !isDraggingDockHeight && action == MotionEvent.ACTION_UP;
            isDraggingCrop = false;
            isDraggingDockHeight = false;
            hasMovedDuringTouch = false;
            invalidate();
            if (wasTap) {
                handleTapReleaseOnControls(x, y);
            }
            return true;
        }

        return super.onTouchEvent(event);
    }
}
