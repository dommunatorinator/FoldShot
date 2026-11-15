package com.foldshot.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Universal One UI 7 / Good Lock Style Settings & Control Center for FoldShot (v2.2.1).
 *
 * Adapts automatically across Samsung, Xiaomi/Redmi/POCO, Infinix/Tecno/itel,
 * OPPO/Realme/OnePlus, Vivo/iQOO, Huawei/Honor, Google Pixel, Motorola, Nothing, Sony, Asus.
 */
public class MainActivity extends Activity {

    private static final String KEY_SCROLL_Y = "saved_scroll_y";

    private FoldShotPrefs prefs;
    private ScrollView scrollView;
    private LinearLayout rootContent;
    private float dp;
    private int savedScrollY = 0;
    private boolean hasRestoredInitialScroll = false;
    private ViewTreeObserver.OnGlobalLayoutListener pendingScrollListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new FoldShotPrefs(this);
        dp = getResources().getDisplayMetrics().density;
        if (savedInstanceState != null) {
            savedScrollY = savedInstanceState.getInt(KEY_SCROLL_Y, 0);
        }

        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);

        requestMediaAndNotificationPermissions();
        startWatcherService(ScreenshotInterceptService.ACTION_RELOAD_OBSERVER);
        buildOneUiSettingsScreen();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (scrollView != null && hasRestoredInitialScroll) {
            savedScrollY = scrollView.getScrollY();
        }
        outState.putInt(KEY_SCROLL_Y, savedScrollY);
    }

    private void requestMediaAndNotificationPermissions() {
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.POST_NOTIFICATIONS
            }, 100);
        } else if (Build.VERSION.SDK_INT == 33) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.POST_NOTIFICATIONS
            }, 100);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, 100);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        startWatcherService(ScreenshotInterceptService.ACTION_RELOAD_OBSERVER);
        buildOneUiSettingsScreen();
    }

    @Override
    protected void onPause() {
        if (scrollView != null && hasRestoredInitialScroll) {
            savedScrollY = scrollView.getScrollY();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startWatcherService(ScreenshotInterceptService.ACTION_RELOAD_OBSERVER);
        buildOneUiSettingsScreen();
    }

    private void startWatcherService(String action) {
        try {
            Intent svc = new Intent(this, ScreenshotInterceptService.class);
            if (action != null) {
                svc.setAction(action);
            }
            startForegroundService(svc);
        } catch (Exception ignored) {
        }
    }

    private void buildOneUiSettingsScreen() {
        dp = getResources().getDisplayMetrics().density;
        if (scrollView == null) {
            scrollView = new ScrollView(this);
            scrollView.setBackgroundColor(0xFF000000);
            scrollView.setFillViewport(true);

            rootContent = new LinearLayout(this);
            rootContent.setOrientation(LinearLayout.VERTICAL);
            scrollView.addView(rootContent);
            setContentView(scrollView);
        } else {
            if (hasRestoredInitialScroll) {
                savedScrollY = scrollView.getScrollY();
            }
            rootContent.removeAllViews();
        }

        int padH = dpInt(20);
        rootContent.setPadding(padH, dpInt(44), padH, dpInt(48));

        // 1. Hero Header
        TextView titleView = new TextView(this);
        titleView.setText("FoldShot");
        titleView.setTextColor(0xFFF5F5F7);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        titleView.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        rootContent.addView(titleView);

        TextView subView = new TextView(this);
        subView.setText("Universal Smart Capture • " + MediaStoreHelper.getDeviceFriendlyName());
        subView.setTextColor(0xFF8E8E93);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subView.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subLp.topMargin = dpInt(6);
        subLp.bottomMargin = dpInt(20);
        rootContent.addView(subView, subLp);

        // Primary Pill Action: Preview Full-Height Smart Capture Overlay Now
        TextView testBtn = new TextView(this);
        testBtn.setText("Preview Full-Height Overlay");
        testBtn.setTextColor(Color.WHITE);
        testBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        testBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        testBtn.setGravity(Gravity.CENTER);
        testBtn.setPadding(dpInt(20), dpInt(14), dpInt(20), dpInt(14));
        testBtn.setBackground(roundedRect(0xFF387AFF, 26f, 0, 0));
        testBtn.setOnClickListener(v -> {
            if (!MediaStoreHelper.hasAutoOpenOverlayPermission(this)) {
                Toast.makeText(this, "Please enable 'Appear on top / Display over other apps' first", Toast.LENGTH_SHORT).show();
                MediaStoreHelper.requestAutoOpenOverlayPermission(this);
                return;
            }
            startWatcherService(ScreenshotInterceptService.ACTION_TEST_OVERLAY);
            moveTaskToBack(true);
        });
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        testLp.bottomMargin = dpInt(26);
        rootContent.addView(testBtn, testLp);

        // 2. Section: SYSTEM PERMISSIONS & DEVICE INTEGRATION
        addSectionHeader("SYSTEM INTEGRATION");
        LinearLayout permCard = createOneUiCard();

        MediaStoreHelper.PhotoAccessState photoState = MediaStoreHelper.getPhotoAccessState(this);
        String photoBadge = photoState == MediaStoreHelper.PhotoAccessState.ALL
                ? "Active"
                : (photoState == MediaStoreHelper.PhotoAccessState.LIMITED ? "Limited" : "Enable");
        String photoSubtitle = photoState == MediaStoreHelper.PhotoAccessState.ALL
                ? "All screenshots readable across system folders & MediaStore"
                : (photoState == MediaStoreHelper.PhotoAccessState.LIMITED
                ? "Tap & choose 'Allow all' so FoldShot sees new screenshots automatically"
                : "Allow photo access so FoldShot can detect and crop screenshots");

        permCard.addView(createPermissionRow(
                "Photo & screenshot access",
                photoSubtitle,
                photoBadge,
                photoState == MediaStoreHelper.PhotoAccessState.ALL,
                v -> {
                    if (photoState == MediaStoreHelper.PhotoAccessState.LIMITED) {
                        openAppPermissionSettings();
                    } else {
                        requestMediaAndNotificationPermissions();
                    }
                }
        ));
        permCard.addView(createDivider());

        boolean hasOverlay = MediaStoreHelper.hasAutoOpenOverlayPermission(this);
        boolean hasSilentDelete = MediaStoreHelper.hasSilentDeletePermission(this);
        boolean hasUnrestrictedBg = MediaStoreHelper.hasUnrestrictedBattery(this);

        permCard.addView(createPermissionRow(
                "Auto-open on screenshot",
                "Opens FoldShot right below the status bar to the bottom of the screen",
                hasOverlay ? "Active" : "Enable",
                hasOverlay,
                v -> MediaStoreHelper.requestAutoOpenOverlayPermission(this)
        ));
        permCard.addView(createDivider());

        permCard.addView(createPermissionRow(
                "Silent delete & move",
                "Deletes and moves screenshots without 'Allow to delete' popups (Media management)",
                hasSilentDelete ? "Active" : "Enable",
                hasSilentDelete,
                v -> MediaStoreHelper.requestSilentDeletePermission(this)
        ));
        permCard.addView(createDivider());

        permCard.addView(createPermissionRow(
                MediaStoreHelper.getOemHelperTitle(),
                MediaStoreHelper.getOemHelperSubtitle(),
                MediaStoreHelper.isSamsungDevice() ? "Open" : (hasUnrestrictedBg ? "Active" : "Configure"),
                !MediaStoreHelper.isSamsungDevice() && hasUnrestrictedBg,
                v -> MediaStoreHelper.openOemBackgroundSettings(this)
        ));
        rootContent.addView(permCard);

        // 2b. Section: AUTO-OPEN TRIGGERS (Optional Extra Sources — OFF by default)
        addSectionHeader("AUTO-OPEN TRIGGERS");
        LinearLayout triggerCard = createOneUiCard();

        triggerCard.addView(createSwitchRow(
                "Activate on Messenger images",
                "Off by default • Auto-open FoldShot when saving images from Messenger",
                prefs.isTriggerOnMessengerEnabled(),
                checked -> {
                    prefs.setTriggerOnMessengerEnabled(checked);
                    startWatcherService(ScreenshotInterceptService.ACTION_RELOAD_OBSERVER);
                }
        ));
        triggerCard.addView(createDivider());

        triggerCard.addView(createSwitchRow(
                "Activate on Browser / Chrome downloads",
                "Off by default • Auto-open FoldShot when downloading images in Chrome or browsers",
                prefs.isTriggerOnBrowserDownloadsEnabled(),
                checked -> {
                    prefs.setTriggerOnBrowserDownloadsEnabled(checked);
                    startWatcherService(ScreenshotInterceptService.ACTION_RELOAD_OBSERVER);
                }
        ));
        rootContent.addView(triggerCard);

        // 3. Section: VISIBLE & USABLE FOLDERS
        addSectionHeader("VISIBLE & USABLE FOLDERS");
        LinearLayout filterCard = createOneUiCard();
        final boolean allowlistEnabled = prefs.isFolderAllowlistEnabled();
        final List<String> allowedFolders = prefs.getAllowedFolders();

        filterCard.addView(createSwitchRow(
                "Restrict to selected folders only",
                allowlistEnabled
                        ? "Only selected folders appear in FoldShot & can receive screenshots"
                        : "Off — all Gallery albums on your phone are visible",
                allowlistEnabled,
                checked -> {
                    prefs.setFolderAllowlistEnabled(checked);
                    if (checked && prefs.getAllowedFolders().isEmpty()) {
                        String[] cur = prefs.resolveTopThreeAlbums();
                        List<String> seed = new ArrayList<>();
                        for (String s : cur) {
                            if (s != null && !s.trim().isEmpty()) seed.add(s.trim());
                        }
                        if (!seed.isEmpty()) {
                            prefs.setAllowedFolders(seed);
                        }
                        buildOneUiSettingsScreen();
                        showSelectVisibleFoldersDialog();
                        return;
                    }
                    buildOneUiSettingsScreen();
                }
        ));
        filterCard.addView(createDivider());

        String filterSummary;
        if (!allowlistEnabled) {
            filterSummary = "All Gallery folders visible (tap to pick specific folders only)";
        } else if (allowedFolders.isEmpty()) {
            filterSummary = "No folders selected — tap Choose to pick visible folders";
        } else {
            StringBuilder sb = new StringBuilder();
            int showCount = Math.min(4, allowedFolders.size());
            for (int i = 0; i < showCount; i++) {
                if (i > 0) sb.append(", ");
                sb.append(allowedFolders.get(i));
            }
            if (allowedFolders.size() > showCount) {
                sb.append(" +").append(allowedFolders.size() - showCount).append(" more");
            }
            filterSummary = sb.toString();
        }
        String filterBadge = allowlistEnabled ? (allowedFolders.size() + " Active") : "Choose";
        filterCard.addView(createPermissionRow(
                "Select visible & usable folders",
                filterSummary,
                filterBadge,
                allowlistEnabled && !allowedFolders.isEmpty(),
                v -> showSelectVisibleFoldersDialog()
        ));
        rootContent.addView(filterCard);

        // 4. Section: FAVORITE QUICK-MOVE ALBUMS (MAX 3)
        addSectionHeader("FAVORITE QUICK-MOVE ALBUMS (MAX 3)");
        LinearLayout albumsCard = createOneUiCard();
        String[] activeAlbums = prefs.resolveTopThreeAlbums();
        int[] badgeColors = new int[]{0xFF387AFF, 0xFF2AD181, 0xFFFFAB2E};

        for (int i = 0; i < 3; i++) {
            final int slot = i;
            albumsCard.addView(createAlbumSlotRow(slot, activeAlbums[i], badgeColors[i]));
            if (i < 2) albumsCard.addView(createDivider());
        }

        List<MediaStoreHelper.GalleryAlbumInfo> discovered = MediaStoreHelper.queryDeviceGalleryAlbums(this);
        List<String> chipNames = new ArrayList<>();
        List<Integer> chipCounts = new ArrayList<>();
        Set<String> seenChips = new LinkedHashSet<>();

        for (MediaStoreHelper.GalleryAlbumInfo info : discovered) {
            if (info.bucketName == null) continue;
            String trimmed = info.bucketName.trim();
            if (trimmed.isEmpty() || !prefs.isFolderUsable(trimmed)) continue;
            String key = trimmed.toLowerCase(Locale.US);
            if (seenChips.add(key)) {
                chipNames.add(trimmed);
                chipCounts.add(info.itemCount);
            }
        }
        if (allowlistEnabled) {
            for (String allowed : allowedFolders) {
                String trimmed = allowed != null ? allowed.trim() : "";
                if (trimmed.isEmpty()) continue;
                String key = trimmed.toLowerCase(Locale.US);
                if (seenChips.add(key)) {
                    chipNames.add(trimmed);
                    chipCounts.add(0);
                }
            }
        }

        if (!chipNames.isEmpty()) {
            albumsCard.addView(createDivider());
            LinearLayout chipSection = new LinearLayout(this);
            chipSection.setOrientation(LinearLayout.VERTICAL);
            chipSection.setPadding(dpInt(18), dpInt(14), dpInt(18), dpInt(14));

            TextView chipTitle = new TextView(this);
            chipTitle.setText(allowlistEnabled
                    ? "Tap an enabled folder to assign to a Top 3 slot:"
                    : "Tap a Gallery album on your phone to assign to a slot:");
            chipTitle.setTextColor(0xFF8E8E93);
            chipTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chipSection.addView(chipTitle);

            HorizontalScrollView chipScroll = new HorizontalScrollView(this);
            chipScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout chipRow = new LinearLayout(this);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            chipRow.setPadding(0, dpInt(10), 0, dpInt(2));

            int maxChips = Math.min(12, chipNames.size());
            for (int i = 0; i < maxChips; i++) {
                final String folderName = chipNames.get(i);
                int count = chipCounts.get(i);
                TextView chip = new TextView(this);
                chip.setText(count > 0 ? (folderName + " (" + count + ")") : folderName);
                chip.setTextColor(0xFFE4E4E7);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                chip.setPadding(dpInt(12), dpInt(7), dpInt(12), dpInt(7));
                chip.setBackground(roundedRect(0xFF252529, 16f, 1, 0xFF323238));
                chip.setOnClickListener(v -> promptAssignAlbumToSlot(folderName));

                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                clp.rightMargin = dpInt(8);
                chipRow.addView(chip, clp);
            }
            chipScroll.addView(chipRow);
            chipSection.addView(chipScroll);
            albumsCard.addView(chipSection);
        }
        rootContent.addView(albumsCard);

        // 4. Section: TOUCH-TO-CROP ENGINE & TOOLBAR POSITION
        addSectionHeader("TOUCH-TO-CROP ENGINE & LAYOUT");
        LinearLayout cropCard = createOneUiCard();

        LinearLayout modeBox = new LinearLayout(this);
        modeBox.setOrientation(LinearLayout.VERTICAL);
        modeBox.setPadding(dpInt(18), dpInt(16), dpInt(18), dpInt(16));

        TextView modeTitle = new TextView(this);
        modeTitle.setText("Default Touch-to-Crop Mode");
        modeTitle.setTextColor(0xFFF5F5F7);
        modeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        modeTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        modeBox.addView(modeTitle);

        TextView modeSub = new TextView(this);
        modeSub.setText("Switch anytime from the top-left 'Auto ▾' pill, or add 'FoldShot Crop' to Quick Settings");
        modeSub.setTextColor(0xFF8E8E93);
        modeSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        modeBox.addView(modeSub);

        LinearLayout segRow = new LinearLayout(this);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        segLp.topMargin = dpInt(12);

        String[] modeKeys = new String[]{"auto", "point-drag", "center-expand", "pinch-spread"};
        String[] modeNames = new String[]{"Auto", "Corner", "Center", "2-Finger"};
        String currentMode = prefs.getCropMode();

        for (int i = 0; i < 4; i++) {
            final String key = modeKeys[i];
            boolean selected = key.equals(currentMode);
            TextView pill = new TextView(this);
            pill.setText(modeNames[i]);
            pill.setGravity(Gravity.CENTER);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            pill.setTextColor(selected ? Color.WHITE : 0xFFA1A1AA);
            pill.setPadding(0, dpInt(9), 0, dpInt(9));
            pill.setBackground(roundedRect(selected ? 0xFF387AFF : 0xFF252529, 16f, 0, 0));
            pill.setOnClickListener(v -> {
                prefs.setCropMode(key);
                buildOneUiSettingsScreen();
            });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 3) plp.rightMargin = dpInt(6);
            segRow.addView(pill, plp);
        }
        modeBox.addView(segRow, segLp);
        cropCard.addView(modeBox);
        cropCard.addView(createDivider());

        cropCard.addView(createSwitchRow(
                "Precision magnifier loupe",
                "Shows a zoomed crosshair above your finger while dragging crop handles",
                prefs.isLoupeEnabled(),
                checked -> prefs.setLoupeEnabled(checked)
        ));
        cropCard.addView(createDivider());

        cropCard.addView(createSwitchRow(
                "Magnetic edge & card snap",
                "Gently snaps selection edges to rule-of-thirds and screen boundaries",
                prefs.isMagneticSnapEnabled(),
                checked -> prefs.setMagneticSnapEnabled(checked)
        ));
        cropCard.addView(createDivider());

        // Preview Area Scale Preset Row (Thumb Reachability)
        LinearLayout scaleBox = new LinearLayout(this);
        scaleBox.setOrientation(LinearLayout.VERTICAL);
        scaleBox.setPadding(dpInt(18), dpInt(16), dpInt(18), dpInt(16));

        TextView scaleTitle = new TextView(this);
        scaleTitle.setText("Screenshot Preview Area Scale");
        scaleTitle.setTextColor(0xFFF5F5F7);
        scaleTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        scaleTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        scaleBox.addView(scaleTitle);

        TextView scaleSub = new TextView(this);
        scaleSub.setText("Scales the screenshot canvas inside the full-height overlay so top corners are easy to reach");
        scaleSub.setTextColor(0xFF8E8E93);
        scaleSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        scaleBox.addView(scaleSub);

        LinearLayout scaleRow = new LinearLayout(this);
        scaleRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams scaleRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        scaleRowLp.topMargin = dpInt(10);

        float curScale = prefs.getPreviewScale();
        float[] scales = new float[]{0.74f, 0.82f, 0.90f};
        String[] sLabels = new String[]{"Compact (75%)", "Balanced (82%)", "Large (90%)"};
        for (int i = 0; i < 3; i++) {
            final float targetScale = scales[i];
            boolean sel = Math.abs(curScale - targetScale) < 0.04f;
            TextView sb = new TextView(this);
            sb.setText(sLabels[i]);
            sb.setGravity(Gravity.CENTER);
            sb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            sb.setTextColor(sel ? Color.WHITE : 0xFFA1A1AA);
            sb.setPadding(dpInt(6), dpInt(9), dpInt(6), dpInt(9));
            sb.setBackground(roundedRect(sel ? 0xFF387AFF : 0xFF252529, 16f, 0, 0));
            sb.setOnClickListener(v -> {
                prefs.setPreviewScale(targetScale);
                buildOneUiSettingsScreen();
            });
            LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 2) sblp.rightMargin = dpInt(6);
            scaleRow.addView(sb, sblp);
        }
        scaleBox.addView(scaleRow, scaleRowLp);
        cropCard.addView(scaleBox);
        cropCard.addView(createDivider());

        // Dock Height Preset Row
        LinearLayout dockBox = new LinearLayout(this);
        dockBox.setOrientation(LinearLayout.VERTICAL);
        dockBox.setPadding(dpInt(18), dpInt(16), dpInt(18), dpInt(16));

        TextView dockTitle = new TextView(this);
        dockTitle.setText("Floating Toolbar Position");
        dockTitle.setTextColor(0xFFF5F5F7);
        dockTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        dockTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dockBox.addView(dockTitle);

        LinearLayout dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dockRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        dockRowLp.topMargin = dpInt(10);

        float curClearance = prefs.getDockBottomClearanceDp();
        float[] heights = new float[]{18f, 130f, 230f};
        String[] hLabels = new String[]{"Bottom (Clean)", "Above System Bar", "Mid-Screen"};
        for (int i = 0; i < 3; i++) {
            final float targetH = heights[i];
            boolean sel = Math.abs(curClearance - targetH) < 35f;
            TextView b = new TextView(this);
            b.setText(hLabels[i]);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            b.setTextColor(sel ? Color.WHITE : 0xFFA1A1AA);
            b.setPadding(dpInt(6), dpInt(9), dpInt(6), dpInt(9));
            b.setBackground(roundedRect(sel ? 0xFF387AFF : 0xFF252529, 16f, 0, 0));
            b.setOnClickListener(v -> {
                prefs.setDockBottomClearanceDp(targetH);
                buildOneUiSettingsScreen();
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 2) blp.rightMargin = dpInt(6);
            dockRow.addView(b, blp);
        }
        dockBox.addView(dockRow, dockRowLp);
        cropCard.addView(dockBox);

        rootContent.addView(cropCard);

        final int targetScrollY = savedScrollY;
        final ViewTreeObserver vto = scrollView.getViewTreeObserver();
        if (pendingScrollListener != null && vto.isAlive()) {
            vto.removeOnGlobalLayoutListener(pendingScrollListener);
        }
        pendingScrollListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewTreeObserver observer = scrollView.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnGlobalLayoutListener(this);
                }
                pendingScrollListener = null;
                scrollView.scrollTo(0, targetScrollY);
                hasRestoredInitialScroll = true;
            }
        };
        vto.addOnGlobalLayoutListener(pendingScrollListener);
        scrollView.post(() -> {
            scrollView.scrollTo(0, targetScrollY);
            hasRestoredInitialScroll = true;
        });
    }

    private void addSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(0xFF8E8E93);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.leftMargin = dpInt(14);
        lp.topMargin = dpInt(18);
        lp.bottomMargin = dpInt(8);
        rootContent.addView(tv, lp);
    }

    private LinearLayout createOneUiCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedRect(0xFF17171A, 26f, 1, 0xFF242429));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dpInt(6);
        card.setLayoutParams(lp);
        return card;
    }

    private View createDivider() {
        View v = new View(this);
        v.setBackgroundColor(0xFF26262B);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
        );
        lp.leftMargin = dpInt(18);
        lp.rightMargin = dpInt(18);
        v.setLayoutParams(lp);
        return v;
    }

    private View createPermissionRow(String title, String subtitle, String badgeText, boolean isActive, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpInt(18), dpInt(16), dpInt(18), dpInt(16));
        row.setOnClickListener(onClick);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dpInt(12);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFF5F5F7);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(0xFF8E8E93);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        textCol.addView(s);

        row.addView(textCol, colLp);

        TextView badge = new TextView(this);
        badge.setText(badgeText);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badge.setTextColor(isActive ? 0xFF2AD181 : Color.WHITE);
        badge.setPadding(dpInt(14), dpInt(7), dpInt(14), dpInt(7));
        badge.setBackground(roundedRect(isActive ? 0x1F2AD181 : 0xFF387AFF, 18f, 0, 0));
        row.addView(badge);

        return row;
    }

    private View createAlbumSlotRow(final int slotIndex, final String albumName, int dotColor) {
        final boolean hasAlbum = albumName != null && !albumName.trim().isEmpty();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpInt(18), dpInt(15), dpInt(18), dpInt(15));
        row.setOnClickListener(v -> {
            if (prefs.isFolderAllowlistEnabled() && !hasAlbum) {
                showSelectVisibleFoldersDialog();
            } else {
                showEditSingleSlotDialog(slotIndex, hasAlbum ? albumName : "");
            }
        });

        View dot = new View(this);
        dot.setBackground(roundedRect(hasAlbum ? dotColor : 0xFF3A3A40, 8f, 0, 0));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dpInt(12), dpInt(12));
        dotLp.rightMargin = dpInt(14);
        row.addView(dot, dotLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView t = new TextView(this);
        t.setText(hasAlbum ? albumName : "(Hidden slot)");
        t.setTextColor(hasAlbum ? 0xFFF5F5F7 : 0xFF8E8E93);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(t);

        TextView sub = new TextView(this);
        sub.setText(hasAlbum
                ? ("Slot " + (slotIndex + 1) + " • Quick-moves screenshot to " + albumName)
                : ("Slot " + (slotIndex + 1) + " • Hidden while fewer than " + (slotIndex + 1) + " folders enabled"));
        sub.setTextColor(0xFF8E8E93);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        textCol.addView(sub);
        row.addView(textCol, colLp);

        TextView editBtn = new TextView(this);
        editBtn.setText(hasAlbum ? "Rename" : "Select");
        editBtn.setTextColor(0xFF5C95FF);
        editBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        editBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        editBtn.setPadding(dpInt(12), dpInt(6), dpInt(12), dpInt(6));
        editBtn.setBackground(roundedRect(0xFF252529, 14f, 0, 0));
        row.addView(editBtn);

        return row;
    }

    private interface BoolCallback {
        void onChanged(boolean checked);
    }

    private View createSwitchRow(String title, String subtitle, boolean initialValue, final BoolCallback callback) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpInt(18), dpInt(15), dpInt(18), dpInt(15));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dpInt(12);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFF5F5F7);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(0xFF8E8E93);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        textCol.addView(s);
        row.addView(textCol, colLp);

        final Switch sw = new Switch(this);
        sw.setChecked(initialValue);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> callback.onChanged(isChecked));
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
        row.addView(sw);

        return row;
    }

    private void showEditSingleSlotDialog(final int slotIndex, String currentName) {
        final EditText input = new EditText(this);
        input.setText(currentName);
        input.setSingleLine(true);
        LinearLayout wrap = new LinearLayout(this);
        int p = dpInt(20);
        wrap.setPadding(p, p / 2, p, p / 2);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Favorite Album Slot " + (slotIndex + 1))
                .setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    String entered = input.getText().toString().trim();
                    if (!entered.isEmpty() && prefs.isFolderAllowlistEnabled()) {
                        prefs.addAllowedFolder(entered);
                    }
                    prefs.setPinnedAlbum(slotIndex, entered);
                    buildOneUiSettingsScreen();
                })
                .setNeutralButton("Auto-Detect", (d, w) -> {
                    prefs.setPinnedAlbum(slotIndex, null);
                    buildOneUiSettingsScreen();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptAssignAlbumToSlot(final String bucketName) {
        String[] resolved = prefs.resolveTopThreeAlbums();
        String[] slots = new String[]{
                "Assign to Slot 1 (" + (resolved[0].isEmpty() ? "empty" : resolved[0]) + ")",
                "Assign to Slot 2 (" + (resolved[1].isEmpty() ? "empty" : resolved[1]) + ")",
                "Assign to Slot 3 (" + (resolved[2].isEmpty() ? "empty" : resolved[2]) + ")"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Pin \"" + bucketName + "\" to Favorite Bar")
                .setItems(slots, (d, which) -> {
                    if (prefs.isFolderAllowlistEnabled()) {
                        prefs.addAllowedFolder(bucketName);
                    }
                    prefs.setPinnedAlbum(which, bucketName);
                    buildOneUiSettingsScreen();
                    Toast.makeText(this, "Pinned " + bucketName + " to Slot " + (which + 1), Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showSelectVisibleFoldersDialog() {
        List<String> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String existingAllowed : prefs.getAllowedFolders()) {
            String trimmed = existingAllowed != null ? existingAllowed.trim() : "";
            if (!trimmed.isEmpty() && seen.add(trimmed.toLowerCase(Locale.US))) {
                candidates.add(trimmed);
            }
        }
        for (MediaStoreHelper.GalleryAlbumInfo info : MediaStoreHelper.queryDeviceGalleryAlbums(this)) {
            if (info.bucketName == null) continue;
            String trimmed = info.bucketName.trim();
            if (!trimmed.isEmpty() && seen.add(trimmed.toLowerCase(Locale.US))) {
                candidates.add(trimmed);
            }
        }
        String[] commonDefaults = new String[]{"Receipts", "Work", "Saved", "Watch Later", "Read Later", "Notes", "IDs", "Shopping"};
        for (String sug : commonDefaults) {
            if (seen.add(sug.toLowerCase(Locale.US))) {
                candidates.add(sug);
            }
        }

        final String[] items = candidates.toArray(new String[0]);
        final boolean[] checked = new boolean[items.length];
        List<String> currentAllowed = prefs.getAllowedFolders();

        for (int i = 0; i < items.length; i++) {
            for (String allowed : currentAllowed) {
                if (items[i].equalsIgnoreCase(allowed)) {
                    checked[i] = true;
                    break;
                }
            }
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Visible & Usable Folders")
                .setMultiChoiceItems(items, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Apply", (d, w) -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < items.length; i++) {
                        if (checked[i]) {
                            chosen.add(items[i]);
                        }
                    }
                    prefs.setAllowedFolders(chosen);
                    prefs.setFolderAllowlistEnabled(true);
                    prefs.pruneDisallowedPinnedAlbums();
                    buildOneUiSettingsScreen();
                })
                .setNeutralButton("+ Add Folder", (d, w) -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < items.length; i++) {
                        if (checked[i]) chosen.add(items[i]);
                    }
                    prefs.setAllowedFolders(chosen);
                    showAddCustomVisibleFolderDialog();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddCustomVisibleFolderDialog() {
        final EditText input = new EditText(this);
        input.setHint("e.g., Watch Later, Read Later, CPA Study");
        input.setSingleLine(true);
        LinearLayout wrap = new LinearLayout(this);
        int p = dpInt(20);
        wrap.setPadding(p, p / 2, p, p / 2);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Add Allowed Folder")
                .setView(wrap)
                .setPositiveButton("Add", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        prefs.addAllowedFolder(name);
                        prefs.setFolderAllowlistEnabled(true);
                    }
                    buildOneUiSettingsScreen();
                    showSelectVisibleFoldersDialog();
                })
                .setNegativeButton("Back", (d, w) -> showSelectVisibleFoldersDialog())
                .show();
    }

    private void openAppPermissionSettings() {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
            Toast.makeText(this, "Go to Permissions > Photos and videos > Allow all", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }

    private GradientDrawable roundedRect(int fillColor, float radiusDp, int strokeWidthPx, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusDp * dp);
        if (strokeWidthPx > 0) {
            gd.setStroke(strokeWidthPx, strokeColor);
        }
        return gd;
    }

    private int dpInt(float val) {
        return Math.round(val * dp);
    }
}
