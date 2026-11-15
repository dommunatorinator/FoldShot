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

    private FoldShotPrefs prefs;
    private OneUiSmartCaptureView captureView;
    private Uri screenshotUri = Uri.EMPTY;
    private Bitmap screenshotBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        captureView = new OneUiSmartCaptureView(this, prefs, this);
        captureView.setWindowStartsBelowStatusBar(false);
        setContentView(captureView);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String rawUri = intent.getStringExtra(EXTRA_SCREENSHOT_URI);
        if (rawUri != null) {
            screenshotUri = Uri.parse(rawUri);
            screenshotBitmap = MediaStoreHelper.loadBitmap(this, screenshotUri);
            captureView.setScreenshotBitmap(screenshotBitmap);
        }
    }

    @Override
    public void onCopyAndDelete(RectF normalizedCrop) {
        Bitmap cropped = MediaStoreHelper.cropFullResolution(
                this, screenshotUri, screenshotBitmap, normalizedCrop, false
        );
        MediaStoreHelper.copyBitmapToClipboard(this, cropped);
        boolean deleted = MediaStoreHelper.deleteScreenshotSilently(this, this, screenshotUri);
        if (deleted) {
            finishAndRemoveTask();
        }
    }

    @Override
    public void onSaveScreenshot(RectF normalizedCrop) {
        boolean isFull = MediaStoreHelper.isFullCrop(normalizedCrop);
        boolean useOriginalStream = isFull && !Uri.EMPTY.equals(screenshotUri);
        Bitmap cropped = useOriginalStream
                ? null
                : MediaStoreHelper.cropFullResolution(
                        this, screenshotUri, screenshotBitmap, normalizedCrop, false
                );
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
        boolean isFull = MediaStoreHelper.isFullCrop(normalizedCrop);
        boolean useOriginalStream = isFull && !Uri.EMPTY.equals(screenshotUri);
        Bitmap cropped = useOriginalStream
                ? null
                : MediaStoreHelper.cropFullResolution(
                        this, screenshotUri, screenshotBitmap, normalizedCrop, false
                );
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
    public void onPickAlbumForSlot(int slotIndex) {
        startActivity(new Intent(this, MainActivity.class));
        finishAndRemoveTask();
    }

    @Override
    public void onTriggerScrollCapture() {
        finishAndRemoveTask();
    }

    @Override
    public void onShare(RectF normalizedCrop) {
        Bitmap cropped = MediaStoreHelper.cropFullResolution(
                this, screenshotUri, screenshotBitmap, normalizedCrop, false
        );
        MediaStoreHelper.shareBitmap(this, cropped);
        finishAndRemoveTask();
    }

    @Override
    public void onOpenSettings() {
        startActivity(new Intent(this, MainActivity.class));
        finishAndRemoveTask();
    }

    @Override
    public void onCloseOverlay() {
        finishAndRemoveTask();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MediaStoreHelper.REQ_DELETE_SCREENSHOT && resultCode == Activity.RESULT_OK) {
            finishAndRemoveTask();
        }
    }
}
