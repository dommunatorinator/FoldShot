package com.foldshot.app;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Universal Android Quick Settings Tile ("FoldShot Crop").
 * Works across every OEM (Samsung, Xiaomi, Infinix, OPPO, Vivo, Huawei, Pixel, Motorola, Nothing):
 * Pulling down Quick Settings and tapping FoldShot immediately wakes the background listener
 * and opens the crop overlay on the latest screenshot.
 */
public class FoldShotTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("FoldShot Crop");
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        try {
            Intent svc = new Intent(this, ScreenshotInterceptService.class);
            svc.setAction(ScreenshotInterceptService.ACTION_TEST_OVERLAY);
            startForegroundService(svc);
        } catch (Exception e) {
            try {
                Intent act = new Intent(this, MainActivity.class);
                act.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityAndCollapse(act);
            } catch (Exception ignored) {
            }
        }
    }
}
