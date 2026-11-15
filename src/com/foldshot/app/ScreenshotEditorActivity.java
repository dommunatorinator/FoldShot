package com.foldshot.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Translucent Full-Height Overlay Activity that auto-closes immediately via finishAndRemoveTask()
 * once Copy & Delete or Move to Favorite Album completes.
 */
public class ScreenshotEditorActivity extends Activity implements OneUiSmartCaptureView.Listener {

    public static final String EXTRA_SCREENSHOT_URI = "extra_screenshot_uri";
    public static final String EXTRA_DELETE_ONLY_URI = "extra_delete_only_uri";

    public static void launchDeletePrompt(android.content.Context context, Uri targetUri) {
        if (context == null || targetUri == null || Uri.EMPTY.equals(targetUri)) return;
        try {
            Intent intent = new Intent(context, ScreenshotEditorActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra(EXTRA_DELETE_ONLY_URI, targetUri.toString());
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private FoldShotPrefs prefs;
    private OneUiSmartCaptureView captureView;
    private Uri screenshotUri = Uri.EMPTY;
    private Bitmap screenshotBitmap;

    private void finishSilently() {
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    private void ensureCaptureView() {
        if (captureView == null) {
            captureView = new OneUiSmartCaptureView(this, prefs, this);
            captureView.setWindowStartsBelowStatusBar(false);
            setContentView(captureView);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            getWindow().setAttributes(lp);
        } else if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        prefs = new FoldShotPrefs(this);
        if (prefs.isSecureOverlayEnabled()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            finishSilently();
            return;
        }
        String deleteOnlyRaw = intent.getStringExtra(EXTRA_DELETE_ONLY_URI);
        if (deleteOnlyRaw != null && !deleteOnlyRaw.isEmpty()) {
            if (captureView != null) {
                captureView.setVisibility(android.view.View.GONE);
            }
            Uri target = Uri.parse(deleteOnlyRaw);
            int status = MediaStoreHelper.deleteScreenshotWithStatus(this, this, target);
            if (status != MediaStoreHelper.DELETE_STATUS_PROMPT_SHOWN) {
                finishSilently();
            }
            return;
        }
        String rawUri = intent.getStringExtra(EXTRA_SCREENSHOT_URI);
        if (rawUri == null || rawUri.isEmpty()) {
            finishSilently();
            return;
        }
        ensureCaptureView();
        captureView.setVisibility(android.view.View.VISIBLE);
        screenshotUri = Uri.parse(rawUri);
        screenshotBitmap = MediaStoreHelper.loadBitmap(this, screenshotUri);
        captureView.setScreenshotBitmap(screenshotBitmap);
    }

    private Bitmap cropAndApplyMarkups(RectF normalizedCrop) {
        Bitmap cropped = MediaStoreHelper.cropFullResolution(
                this, screenshotUri, screenshotBitmap, normalizedCrop, false
        );
        if (captureView != null && captureView.hasMarkups()) {
            cropped = captureView.renderMarkedCropBitmap(cropped, normalizedCrop);
        }
        return cropped;
    }

    @Override
    public void onCopyAndDelete(RectF normalizedCrop) {
        Bitmap cropped = cropAndApplyMarkups(normalizedCrop);
        MediaStoreHelper.copyBitmapToClipboard(this, cropped);
        int status = MediaStoreHelper.deleteScreenshotWithStatus(this, this, screenshotUri);
        if (status != MediaStoreHelper.DELETE_STATUS_PROMPT_SHOWN) {
            finishSilently();
        }
    }

    @Override
    public void onSaveScreenshot(RectF normalizedCrop) {
        boolean hasMarkups = captureView != null && captureView.hasMarkups();
        boolean isFull = !hasMarkups && MediaStoreHelper.isFullCrop(normalizedCrop);
        boolean useOriginalStream = isFull && !Uri.EMPTY.equals(screenshotUri);
        Bitmap cropped = useOriginalStream ? null : cropAndApplyMarkups(normalizedCrop);
        boolean ok = MediaStoreHelper.saveCroppedScreenshot(this, this, screenshotUri, cropped, isFull);
        if (!ok && cropped == null) {
            ok = MediaStoreHelper.saveCroppedScreenshot(this, this, screenshotUri, screenshotBitmap, false);
        }
        if (ok) {
            Toast.makeText(this, "Saved to Screenshots", Toast.LENGTH_SHORT).show();
            finishAndRemoveTask();
        }
    }

    @Override
    public void onMoveToAlbum(int slotIndex, String albumName, RectF normalizedCrop) {
        if (prefs != null && !prefs.isFolderUsable(albumName)) {
            Toast.makeText(this, "Folder not enabled in Visible Folders", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean hasMarkups = captureView != null && captureView.hasMarkups();
        boolean isFull = !hasMarkups && MediaStoreHelper.isFullCrop(normalizedCrop);
        boolean useOriginalStream = isFull && !Uri.EMPTY.equals(screenshotUri);
        Bitmap cropped = useOriginalStream ? null : cropAndApplyMarkups(normalizedCrop);
        boolean ok = MediaStoreHelper.moveBitmapToFavoriteAlbum(this, this, screenshotUri, cropped, albumName, isFull);
        if (!ok && cropped == null) {
            ok = MediaStoreHelper.moveBitmapToFavoriteAlbum(this, this, screenshotUri, screenshotBitmap, albumName, false);
        }
        if (ok) {
            Toast.makeText(this, "Moved to " + albumName, Toast.LENGTH_SHORT).show();
            finishAndRemoveTask();
        }
    }

    @Override
    public void onSaveToHiddenFolder(RectF normalizedCrop) {
        boolean hasMarkups = captureView != null && captureView.hasMarkups();
        boolean isFull = !hasMarkups && MediaStoreHelper.isFullCrop(normalizedCrop);
        boolean useOriginalStream = isFull && !Uri.EMPTY.equals(screenshotUri);
        Bitmap cropped = useOriginalStream ? null : cropAndApplyMarkups(normalizedCrop);
        boolean ok = HiddenVaultManager.saveToVault(
                this,
                useOriginalStream ? screenshotUri : null,
                cropped,
                isFull,
                prefs != null ? prefs.isStripMetadataEnabled() : true
        );
        if (ok) {
            Toast.makeText(this, "Saved privately", Toast.LENGTH_SHORT).show();
            int status = MediaStoreHelper.deleteScreenshotWithStatus(this, this, screenshotUri);
            if (status != MediaStoreHelper.DELETE_STATUS_PROMPT_SHOWN) {
                finishSilently();
            }
        } else {
            Toast.makeText(this, "Could not save privately", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPickAlbumForSlot(int slotIndex) {
        startActivity(new Intent(this, MainActivity.class));
        finishSilently();
    }

    @Override
    public void onTriggerScrollCapture() {
        finishSilently();
    }

    @Override
    public void onShare(RectF normalizedCrop) {
        Bitmap cropped = cropAndApplyMarkups(normalizedCrop);
        MediaStoreHelper.shareBitmap(this, cropped);
        finishSilently();
    }

    @Override
    public void onOpenSettings() {
        startActivity(new Intent(this, MainActivity.class));
        finishSilently();
    }

    @Override
    public void onCloseOverlay() {
        finishSilently();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MediaStoreHelper.REQ_DELETE_SCREENSHOT) {
            finishSilently();
        }
    }
}
