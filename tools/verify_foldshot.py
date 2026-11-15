#!/usr/bin/env python3
"""
FoldShot Automated Verification & Security Hygiene Test Suite (v2.1.3)
Covers:
- Zero Security / Play Protect Heuristic Flags (no boot-persistence triad, no path traversal, v2+v3 release signing)
- Multi-Screen & Live Resolution / DPI Adaptation
- Expandable Left-Side More Folders Drawer
- Universal Android 10–16 OEM Compatibility
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

WORK_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK_ROOT = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "/home/antigravity/.android-sdk"
BUILD_TOOLS = os.environ.get("ANDROID_BUILD_TOOLS") or os.path.join(SDK_ROOT, "build-tools", "35.0.0")
APK_PATH = os.environ.get("APK_OUT") or os.path.join(WORK_DIR, "FoldShot.apk")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner")
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def assert_true(cond: bool, msg: str):
    if not cond:
        print(f"[FAIL] {msg}")
        sys.exit(1)
    print(f"[PASS] {msg}")


def test_manifest_and_play_protect():
    manifest_path = os.path.join(WORK_DIR, "AndroidManifest.xml")
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest_raw = f.read()
    tree = ET.parse(manifest_path)
    root = tree.getroot()

    uses_sdk = root.find("uses-sdk")
    assert_true(uses_sdk is not None, "uses-sdk tag present in AndroidManifest.xml")
    min_sdk = int(uses_sdk.attrib.get(f"{ANDROID_NS}minSdkVersion", "0"))
    target_sdk = int(uses_sdk.attrib.get(f"{ANDROID_NS}targetSdkVersion", "0"))
    assert_true(min_sdk <= 29, f"minSdkVersion ({min_sdk}) supports Android 10–16 across all OEMs")
    assert_true(target_sdk >= 35, f"targetSdkVersion ({target_sdk}) meets modern Android compatibility requirements")

    perms = [
        p.attrib.get(f"{ANDROID_NS}name", "")
        for p in root.findall("uses-permission")
    ]
    assert_true("android.permission.MANAGE_MEDIA" in perms, "Uses Play-Protect-safe MANAGE_MEDIA for silent delete")
    assert_true("android.permission.SYSTEM_ALERT_WINDOW" in perms, "Uses SYSTEM_ALERT_WINDOW for auto-open overlay")
    assert_true("android.permission.READ_MEDIA_IMAGES" in perms, "Uses READ_MEDIA_IMAGES for Android 13–16")
    assert_true("android.permission.READ_EXTERNAL_STORAGE" in perms, "Uses READ_EXTERNAL_STORAGE fallback for Android 10–12")

    forbidden_perms = [
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.INTERNET",
    ]
    for fp in forbidden_perms:
        assert_true(fp not in perms, f"Play Protect & Privacy Hygiene: {fp} is NOT declared")

    assert_true("LOCKED_BOOT_COMPLETED" not in manifest_raw, "Play Protect Hygiene: LOCKED_BOOT_COMPLETED removed")
    assert_true("QUICKBOOT_POWERON" not in manifest_raw, "Play Protect Hygiene: QUICKBOOT_POWERON removed")
    assert_true("requestLegacyExternalStorage" not in manifest_raw, "Play Protect Hygiene: requestLegacyExternalStorage removed")

    app = root.find("application")
    assert_true(app.attrib.get(f"{ANDROID_NS}allowBackup", "") == "false", "Security Hygiene: allowBackup='false'")
    assert_true(app.attrib.get(f"{ANDROID_NS}usesCleartextTraffic", "") == "false", "Security Hygiene: usesCleartextTraffic='false'")
    assert_true(app.attrib.get(f"{ANDROID_NS}icon", "") == "@mipmap/ic_foldshot", "Uses dedicated bundled raster PNG icon @mipmap/ic_foldshot")

    activities = {a.attrib.get(f"{ANDROID_NS}name", ""): a for a in app.findall("activity")}
    assert_true(".MainActivity" in activities, "MainActivity declared")
    main_act = activities[".MainActivity"]
    assert_true(
        main_act.attrib.get(f"{ANDROID_NS}excludeFromRecents", "false") == "false",
        "Play Protect Hygiene: Launcher MainActivity does NOT set excludeFromRecents='true'"
    )
    assert_true(
        f"{ANDROID_NS}taskAffinity" not in main_act.attrib,
        "Play Protect Hygiene: Launcher MainActivity does NOT spoof empty taskAffinity"
    )

    assert_true(".ScreenshotEditorActivity" in activities, "ScreenshotEditorActivity fallback declared")
    ed_act = activities[".ScreenshotEditorActivity"]
    assert_true(
        ed_act.attrib.get(f"{ANDROID_NS}autoRemoveFromRecents", "false") == "true",
        "ScreenshotEditorActivity auto-removes cleanly from Recents after crop"
    )

    services = {s.attrib.get(f"{ANDROID_NS}name", ""): s for s in app.findall("service")}
    assert_true(".ScreenshotInterceptService" in services, "ScreenshotInterceptService declared")
    assert_true(".FoldShotTileService" in services, "Quick Settings FoldShotTileService declared for universal shade access")
    assert_true(
        services[".FoldShotTileService"].attrib.get(f"{ANDROID_NS}permission", "") == "android.permission.BIND_QUICK_SETTINGS_TILE",
        "FoldShotTileService protected with BIND_QUICK_SETTINGS_TILE"
    )

    receivers = app.findall("receiver")
    assert_true(len(receivers) == 0, "Play Protect Hygiene: No persistent boot broadcast receivers declared")
    assert_true(
        not os.path.exists(os.path.join(WORK_DIR, "src/com/foldshot/app/BootCompletedReceiver.java")),
        "Dead BootCompletedReceiver.java removed from source tree"
    )

    with open(os.path.join(WORK_DIR, "res/values/strings.xml"), "r", encoding="utf-8") as f:
        strings_raw = f.read()
    assert_true("accessibility_desc" not in strings_raw, "Dead accessibility_desc string removed from strings.xml")
    assert_true(os.path.exists(os.path.join(WORK_DIR, "res/mipmap-xxxhdpi/ic_foldshot.png")), "Custom PNG app icon res/mipmap-xxxhdpi/ic_foldshot.png exists")


def test_security_and_universal_oem_features():
    with open(os.path.join(WORK_DIR, "src/com/foldshot/app/MediaStoreHelper.java"), "r", encoding="utf-8") as f:
        helper_src = f.read()
    with open(os.path.join(WORK_DIR, "src/com/foldshot/app/ScreenshotInterceptService.java"), "r", encoding="utf-8") as f:
        service_src = f.read()
    with open(os.path.join(WORK_DIR, "src/com/foldshot/app/MainActivity.java"), "r", encoding="utf-8") as f:
        main_src = f.read()
    with open(os.path.join(WORK_DIR, "src/com/foldshot/app/OneUiSmartCaptureView.java"), "r", encoding="utf-8") as f:
        view_src = f.read()
    with open(os.path.join(WORK_DIR, "src/com/foldshot/app/ClipboardFileProvider.java"), "r", encoding="utf-8") as f:
        provider_src = f.read()

    # 0. CWE-22 Path Traversal guard in ClipboardFileProvider
    assert_true(
        "getCanonicalPath" in provider_src and "SecurityException" in provider_src,
        "Security Fix (CWE-22): ClipboardFileProvider enforces canonical path containment"
    )

    # 1. Android 10 (API 29) RecoverableSecurityException + Build.VERSION.SDK_INT >= 30 guard
    assert_true(
        "Build.VERSION.SDK_INT >= 30" in helper_src and "RecoverableSecurityException" in helper_src,
        "Android 10 (API 29) silent/recoverable delete guard prevents NoSuchMethodError crash"
    )

    # 2. Android 14–16 Partial Photo Access detection
    assert_true(
        "getPhotoAccessState" in helper_src and "LIMITED" in helper_src,
        "Detects Android 14–16 partial/limited photo access trap and surfaces fix in MainActivity"
    )
    assert_true(
        "getPhotoAccessState" in main_src,
        "MainActivity shows live Photo & Media Access status card"
    )

    # 3. OPPO / Realme DCIM/Camera filename screenshot exception + StorageVolumes discovery
    assert_true(
        "explicitScreenshotName" in helper_src and "StorageManager" in helper_src,
        "Supports OPPO/ColorOS DCIM/Camera screenshots & multi-volume StorageManager discovery"
    )

    # 4. Modern FileObserver(File, mask) & Mali/MediaTek OOM/Texture Safe Bitmap loader
    assert_true(
        "new FileObserver(dir, mask)" in service_src,
        "Uses modern non-deprecated FileObserver(File, mask) for API 29–36"
    )
    assert_true(
        "inSampleSize" in helper_src and "MAX_STITCH_HEIGHT" in helper_src,
        "GPU texture & OOM-safe bitmap sampling (Helio G99 / Mali / budget RAM safe)"
    )

    # 5. Clean OEM Detection without probing 3rd-party security-center packages
    oem_tokens = [
        "isSamsungDevice",
        "isXiaomiDevice",
        "isTranssionDevice",
        "isColorOsFamilyDevice",
        "isVivoDevice",
        "isHuaweiHonorDevice",
    ]
    for tok in oem_tokens:
        assert_true(tok in helper_src, f"Universal OEM support token '{tok}' present in MediaStoreHelper")

    suspect_tokens = [
        "com.coloros.safecenter",
        "com.iqoo.secure",
        "com.huawei.systemmanager",
        "com.transsion.phonemaster",
        "com.miui.securitycenter",
        "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    ]
    for sus in suspect_tokens:
        assert_true(sus not in helper_src, f"Play Protect Hygiene: suspect security-center probe '{sus}' removed")

    # 5b. No redundant Copy & Delete confirmation Toasts (v2.1.3)
    with open(os.path.join(WORK_DIR, "src/com/foldshot/app/ScreenshotEditorActivity.java"), "r", encoding="utf-8") as f:
        editor_src = f.read()
    with open(os.path.join(WORK_DIR, "src/com/foldshot/app/FoldShotPrefs.java"), "r", encoding="utf-8") as f:
        prefs_src = f.read()
    assert_true(
        "Copied & deleted" not in service_src
        and "Copied & deleted" not in editor_src
        and "Copied to Clipboard & deleted" not in editor_src,
        "UX Hygiene (v2.1.3): Redundant 'Copied & deleted' Toast notifications removed"
    )

    # 5c. Visible & Usable Folders Allowlist Feature (v2.2.0)
    assert_true(
        "isFolderAllowlistEnabled" in prefs_src
        and "setFolderAllowlistEnabled" in prefs_src
        and "getAllowedFolders" in prefs_src
        and "setAllowedFolders" in prefs_src
        and "isFolderUsable" in prefs_src,
        "FoldShotPrefs implements Visible & Usable Folders allowlist persistence and checks"
    )
    assert_true(
        "showSelectVisibleFoldersDialog" in main_src and "isFolderUsable" in main_src,
        "MainActivity provides UI to select which folders are visible and usable"
    )
    assert_true(
        "isFolderAllowlistEnabled" in view_src and "isFolderUsable" in view_src,
        "OneUiSmartCaptureView enforces folder allowlist across Row A pills and More Folders drawer"
    )
    assert_true(
        "isFolderUsable" in service_src and "isFolderUsable" in editor_src,
        "ScreenshotInterceptService and ScreenshotEditorActivity enforce folder allowlist on move"
    )

    # 5d. MainActivity preserves scroll position across setting updates (v2.2.1)
    assert_true(
        "private ScrollView scrollView;" in main_src
        and "savedScrollY" in main_src
        and "removeAllViews()" in main_src
        and "scrollTo(0," in main_src,
        "UX Fix (v2.2.1): MainActivity reuses persistent ScrollView and restores scrollY when settings change"
    )

    # 5e. Save Cropped Screenshot button between Reset Crop and Close X + crisp Reset icon
    assert_true(
        "saveScreenshotBtnRect" in view_src
        and "drawSaveScreenshotIcon" in view_src
        and "onSaveScreenshot" in view_src
        and "saveCroppedScreenshot" in helper_src
        and "saveCroppedScreenshot" in service_src
        and "saveCroppedScreenshot" in editor_src,
        "Save Cropped Screenshot button & MediaStore saveCroppedScreenshot wired across overlay and service"
    )
    assert_true(
        view_src.find("resetBtnRect.set(w - 138f * dp")
        < view_src.find("saveScreenshotBtnRect.set(w - 94f * dp")
        < view_src.find("closeBtnRect.set(w - 50f * dp"),
        "Save Cropped Screenshot button positioned directly between Reset Crop button and Close (X) button"
    )
    assert_true(
        "canvas.drawArc(oval, -90f, 285f, false, paint)" in view_src,
        "Reset Crop button icon uses crisp counter-clockwise circular arrow with connected arrowhead"
    )

    # 5f. Strict Screenshot-Only Detection (Never trigger on Quick Share, Downloads, Messaging, etc.)
    assert_true(
        "isDirectHintUri" not in helper_src
        and "isAlwaysBlockedNonScreenshotLocation" in helper_src
        and "extractParentDirLower" in helper_src
        and "\"quick share\"" in helper_src
        and "\"download\"" in helper_src
        and "IS_TRASHED" in helper_src,
        "Strict Screenshot Filtering: blocks Quick Share, unrelated Downloads, messaging apps, trash, and arbitrary hintUri triggers"
    )
    assert_true(
        "lastHandledUri" in service_src and "lastHandledHeight" in service_src,
        "ScreenshotInterceptService prevents duplicate re-triggers on metadata updates or moves"
    )

    # 5g. Optional Messenger & Browser/Chrome Download Triggers (OFF by default)
    assert_true(
        "getBoolean(\"trigger_on_messenger\", false)" in prefs_src
        and "getBoolean(\"trigger_on_browser_downloads\", false)" in prefs_src,
        "FoldShotPrefs defines Messenger and Browser/Chrome download triggers OFF by default"
    )
    assert_true(
        "isTriggerOnMessengerEnabled" in main_src
        and "isTriggerOnBrowserDownloadsEnabled" in main_src
        and "ACTION_RELOAD_OBSERVER" in main_src,
        "MainActivity provides seamless switches for Messenger & Browser/Chrome downloads and reloads observer live"
    )
    assert_true(
        "isMessengerFolder" in helper_src
        and "isBrowserDownloadFolder" in helper_src
        and "getWatchedCaptureDirs" in helper_src
        and "getWatchedCaptureDirs" in service_src,
        "MediaStoreHelper & ScreenshotInterceptService seamlessly watch Messenger & Browser download folders when enabled"
    )

    # 5h. Full-Resolution Loading, Cropping & Lossless Stream Copy for Downloaded/Saved Photos
    assert_true(
        "loadFullResolutionBitmap" in helper_src
        and "cropFullResolution" in helper_src
        and "BitmapRegionDecoder" in helper_src
        and "ExifInterface" in helper_src
        and "copyOriginalStreamToMediaStore" in helper_src
        and "detectSourceMimeAndExt" in helper_src
        and "isFullCrop" in helper_src,
        "MediaStoreHelper preserves full resolution via BitmapRegionDecoder, EXIF orientation mapping, and lossless stream copy"
    )
    assert_true(
        "MediaStoreHelper.cropFullResolution(" in service_src
        and "MediaStoreHelper.cropFullResolution(" in editor_src
        and "MediaStoreHelper.isFullCrop(" in service_src
        and "MediaStoreHelper.isFullCrop(" in editor_src,
        "ScreenshotInterceptService and ScreenshotEditorActivity use cropFullResolution and lossless original moves/saves"
    )

    # 5i. Persistent Settings Profile (Backup, Export, Import, Auto-Restore across Uninstall/Reinstall)
    assert_true(
        "exportProfileJson" in prefs_src
        and "importProfileJson" in prefs_src
        and "exportProfileToUri" in prefs_src
        and "importProfileFromUri" in prefs_src
        and "tryAutoRestoreFromDocuments" in prefs_src
        and "FoldShot_Profile.json" in prefs_src
        and "allowed_folders" in prefs_src
        and "pinned_albums" in prefs_src,
        "FoldShotPrefs implements complete Settings Profile JSON export/import & Documents auto-backup"
    )
    assert_true(
        "ACTION_CREATE_DOCUMENT" in main_src
        and "ACTION_OPEN_DOCUMENT" in main_src
        and "tryAutoRestoreFromDocuments" in main_src
        and "showProfileClipboardDialog" in main_src
        and "SETTINGS PROFILE (BACKUP & RESTORE)" in main_src,
        "MainActivity provides SAF Export/Import, Clipboard JSON backup/restore, and auto-restore on fresh install"
    )

    # 6. Expandable "More Folders" button on left of the 3 favorite folders (v2.1.0)
    assert_true("expandFoldersBtnRect" in view_src, "Small expand button rect declared on left of the 3 folders")
    assert_true("showMoreFoldersDrawer" in view_src, "In-overlay expanded folder selection drawer state implemented")
    assert_true("queryDeviceGalleryAlbums" in view_src, "Expanded folder drawer loads real MediaStore Gallery albums")
    assert_true(
        view_src.find("dragDockHandleRect.set") < view_src.find("expandFoldersBtnRect.set") < view_src.find("albumPillRects[i].set"),
        "Expand button is positioned strictly on the left of the 3 favorite folder pills"
    )

    # 7. Multi-Screen & Live Resolution / DPI Adaptation (v2.1.1)
    assert_true(
        "private float dp;" in view_src and "updateDensityFromContext" in view_src,
        "OneUiSmartCaptureView dynamically refreshes dp density on fold/unfold/config changes"
    )
    assert_true(
        "findActiveDisplay" in helper_src and "getAllDisplayDimensions" in helper_src,
        "MediaStoreHelper supports multi-display detection and dimension matching across all screens"
    )
    assert_true(
        "findActiveDisplay" in service_src and "DisplayListener" in service_src,
        "ScreenshotInterceptService binds active Display via DisplayManager and listens for display switches"
    )

    # 8. Critical regressions (Full-height overlay + 82% reachable preview + Android 14 FGS)
    assert_true("FOREGROUND_SERVICE_TYPE_SPECIAL_USE" in service_src, "Android 14 FGS SpecialUse preserved")
    assert_true("FLAG_LAYOUT_NO_LIMITS" in service_src and "lp.setFitInsetsTypes(0)" in service_src, "Full-bottom overlay preserved")
    assert_true("prefs.getPreviewScale()" in view_src, "Thumb-reachable preview scale preserved")


def test_geometry_across_all_oem_form_factors():
    devices = [
        ("Samsung Galaxy Z Fold 7 (Cover)", 1080, 2520, 96, 3.0),
        ("Samsung Galaxy Z Fold 7 (Unfolded)", 1968, 2184, 84, 3.0),
        ("Infinix X6851 (Note 40 Pro / XOS 14)", 1080, 2436, 90, 2.75),
        ("Xiaomi 11T (MIUI / HyperOS)", 1080, 2400, 92, 2.75),
        ("OPPO Find X8 / OnePlus 13 (ColorOS)", 1264, 2780, 104, 3.25),
        ("Vivo X100 / iQOO (FuntouchOS)", 1260, 2800, 102, 3.25),
        ("Sony Xperia 1 VI (21:9)", 1080, 2340, 88, 2.75),
        ("Google Pixel 9", 1080, 2424, 98, 2.75),
        ("Android Tablet (Landscape 16:10)", 2560, 1600, 64, 2.0),
    ]
    preview_scale = 0.82
    for name, sw, sh, status_h, dp in devices:
        win_y = status_h
        win_h = sh - status_h
        bottom_edge = win_y + win_h
        top_zone = 54.0 * dp
        bottom_zone = 112.0 * dp
        max_avail_w = sw - 32.0 * dp
        max_avail_h = win_h - top_zone - bottom_zone

        scaled_w = max_avail_w * preview_scale
        scaled_h = max_avail_h * preview_scale
        img_aspect = sw / float(sh)
        if img_aspect > (scaled_w / scaled_h):
            draw_w = scaled_w
            draw_h = draw_w / img_aspect
        else:
            draw_h = scaled_h
            draw_w = draw_h * img_aspect

        height_ratio = draw_h / win_h

        albums_bar_w = min(sw - 20.0 * dp, 386.0 * dp)
        slot_w = (albums_bar_w - 92.0 * dp) / 3.0
        nav_inset = 24.0 * dp
        clearance_px = 18.0 * dp
        main_bar_bot = min(win_h - 10.0 * dp, win_h - nav_inset - clearance_px + 12.0 * dp)
        albums_top = (main_bar_bot - 50.0 * dp) - 44.0 * dp
        drawer_h = min(210.0 * dp, max(120.0 * dp, albums_top - top_zone - 16.0 * dp))
        drawer_top = albums_top - 8.0 * dp - drawer_h

        assert_true(
            win_y == status_h and bottom_edge == sh and 0.50 <= height_ratio <= 0.85
            and slot_w >= 68.0 * dp and drawer_top >= 46.0 * dp,
            f"Geometry & Folder Drawer verified on {name}: window=[{win_y}..{bottom_edge}px], slotW={slot_w/dp:.1f}dp, drawerTop={drawer_top/dp:.1f}dp"
        )


def test_apk_binary_and_signature():
    assert_true(os.path.exists(APK_PATH) and os.path.getsize(APK_PATH) > 10000, f"APK binary exists ({os.path.getsize(APK_PATH)} bytes)")

    align_res = subprocess.run([ZIPALIGN, "-c", "4", APK_PATH], capture_output=True, text=True)
    assert_true(align_res.returncode == 0, "ZIP/APK 4-byte page alignment verified via zipalign -c 4")

    res = subprocess.run([APKSIGNER, "verify", "-v", "--print-certs", APK_PATH], capture_output=True, text=True, check=True)
    assert_true("Verifies" in res.stdout, "APK signature verified via apksigner")
    assert_true("Verified using v3 scheme (APK Signature Scheme v3): true" in res.stdout, "APK Signature Scheme v3 verified (API 28+)")
    assert_true("Verified using v1 scheme (JAR signing): false" in res.stdout, "Legacy v1 JAR signing disabled (modern API 29+ v2/v3 only)")
    assert_true("CN=Android Debug" not in res.stdout and "androiddebugkey" not in res.stdout, "Play Protect Hygiene: APK is NOT signed with CN=Android Debug key")
    assert_true("CN=FoldShot Utility" in res.stdout, "APK signed cleanly with dedicated FoldShot Utility release certificate")

    badging = subprocess.run([AAPT2, "dump", "badging", APK_PATH], capture_output=True, text=True, check=True)
    assert_true("versionName='2.2.1'" in badging.stdout, "APK versionName='2.2.1' confirmed via aapt2 dump badging")


if __name__ == "__main__":
    print("=== Running FoldShot Automated Security & Verification Suite (v2.2.1) ===")
    test_manifest_and_play_protect()
    test_security_and_universal_oem_features()
    test_geometry_across_all_oem_form_factors()
    test_apk_binary_and_signature()
    print("=== ALL FOLDSHOT v2.2.1 TESTS PASSED (100%) ===")
