# 📱 FoldShot (`v2.2.1`)

**Stop letting your Screenshots and Downloads folders become a graveyard.**  
Instant **1-tap folder sorting** + **iOS-style Copy & Delete** + **Save Cropped Screenshot** + **inline Redact & Highlight markup** + **AES-256 encrypted Hidden Vault** + **thumb-reachable full-resolution cropping** right when you capture a screenshot (or optionally when you save images from **Messenger** or **Chrome/Browser**)—100% offline, **~225 KB**, release-signed, with a built-in **Settings Profile & Encrypted Vault backup/restore**, **hideable tray notification**, and live adaptation across both screens on foldables.

[▶️ **Watch the 30-Second Demo**](https://drive.google.com/file/d/10zp5OYTAV0vMAbJnqnfiXAlXtnpYkRpP/view) · [⬇️ **Download Latest APK (v2.2.1)**](https://github.com/dommunatorinator/FoldShot/releases/latest/download/FoldShot.apk) · [☕ **Ko-fi**](https://ko-fi.com/dommu) · [💖 **PayPal**](https://www.paypal.me/dommuyu)

> [!NOTE]
> **No pressure to update:** If FoldShot already works well for you, **you don't have to update if you don't want to.** Only grab a newer build if you want a new feature or fix—and if something *doesn't* work on your phone, open an issue with your device model and Android version and **I'll update it when I can.**

---

## 👋 Hey, I’m Dom — Why I Built This

I got really annoyed trying to keep my screenshots (and random saved images from Messenger or Chrome) organized. Every time I saved something I wanted to **watch later**, **read later**, or keep as a **receipt**, it got dumped into one massive `Screenshots` or `Download` folder—and finding *that one image* later was a nightmare.

I used to use an iPhone, and the thing I missed most after switching to Samsung was **Copy & Delete** for quick one-off screenshots I never wanted cluttering my Gallery. Samsung didn’t have it, so I thought: *what if I build my own capture overlay that combines Copy & Delete with instant folder sorting, offline privacy controls, full-res cropping, and zero bloat?*

I'm currently daily-driving a foldable (**Galaxy Z Fold 7**), so I made sure FoldShot adapts live to **both the cover screen and the unfolded screen** (while working cleanly across Android 10–16 phones too). Honestly, I originally built it just for myself—but it saved me so much time that I figured I'd share it with everyone else.

---

## ✨ What FoldShot (`v2.2.1`) Does

1. **🗂️ 1-Tap Quick-Sort Folders + Strict Folder Filtering**
   * Shows your **Top 3 pinned Gallery folders** (e.g., `Receipts`, `Work`, `Saved`) right inside the capture bar—one tap crops and moves your screenshot straight into the right album.
   * **Expandable `3×3` Folder Drawer:** Keeps the main overlay clean and capped at 3 quick pills, with a small **`2×2` expand button** on the left that opens a paginated **`3×3` drawer** for your other folders.
   * **Visible & Usable Folders Control:** Don’t want random WhatsApp/Instagram folders cluttering your list? Enable **Restrict to selected folders only** in Settings and pick (or add) the exact folders FoldShot is allowed to see and save to.

2. **🔐 Discreet Encrypted Hidden Vault (100% Offline)**
   * **Secret 3-Second Save Gesture:** Open the **More Folders** drawer during capture and **hold the minimize chevron (`⌄`) for 3 seconds**—your screenshot is encrypted with **AES-256-GCM** (via `AndroidKeyStore`) directly into app-private storage (`Saved privately`) and removed from your public Gallery.
   * **Custom Long-Press Spot + PIN (`Summer` at the Bottom):** Zero obvious "Open Vault" button anywhere in the UI. Long-press your chosen secret spot—defaulting to **Summer** (cat cutout at the bottom of FoldShot Settings, or switchable to Title / Subtitle / Section Headers)—and enter your **salted PBKDF2 PIN** to unlock.
   * **`FLAG_SECURE` & Auto-Lock:** Vault screens are blocked from screenshots/screen-recording and auto-lock the moment you leave the app or turn off the screen.
   * **Portable `.foldvault` Backups:** Export and import passphrase-encrypted `.foldvault` archives so your private vault items survive APK uninstall/reinstall updates.

3. **🖍️ Inline Redact & Highlight Markup + Aspect Ratio Lock**
   * **Permanent Blackout Redact (`Redact`) & Highlight (`Pen`) + `Undo`:** Scrub out sensitive names, totals, or addresses or highlight key lines right inside the smart capture overlay—cropped pixels have redactions permanently burned in before copying, saving, moving, sharing, or vaulting.
   * **Aspect Ratio Lock:** Open the top-left capture pill (`Auto ▾`) to lock crop selection to **`Free`**, **`1:1`**, **`4:5`**, or **`16:9`**.

4. **🛡️ Offline Privacy Toolkit (All Local, Zero Cloud)**
   * **EXIF & GPS Metadata Stripper (ON by default):** Removes GPS coordinates, camera model, serials, lens tags, and timestamps from saved, moved, or vaulted JPEGs.
   * **Auto-Clear Clipboard Timer (`Off` / `30s` / `2m` / `5m`):** Automatically wipes clipboard screenshots and cached temp images after copying.
   * **Self-Destructing Temp Folder (`Off` / `24h` / `3d` / `7d`):** Automatically cleans out screenshots placed in a configurable temporary folder (default: `Temp`) once their TTL expires.
   * **Anonymous Filenames (`IMG_...`):** Optionally names outputs `IMG_yyyyMMdd_HHmmss_SSS` instead of a `FoldShot_` prefix.
   * **Privacy Overlay & Auto-Hide on Lock:** Optionally applies `FLAG_SECURE` to the capture overlay and dismisses it immediately when your screen locks.

5. **📋 Quiet iOS "Copy & Delete" + Save Cropped Screenshot**
   * Need to grab something just to paste it into a chat or note? Tap **`Copy & Delete`**—FoldShot writes your cropped/redacted image to the clipboard, silently deletes the raw screenshot from your Gallery, and disappears cleanly (with zero redundant Toast clutter overlapping Android's clipboard confirmation).
   * **Save Cropped Screenshot Button:** Positioned right between **Reset Crop** and **Close (`X`)** in the top bar—crop any screenshot or downloaded image and save only the clean cropped version straight to your `Screenshots` folder in 1 tap.

6. **🌐 Optional Messenger & Browser/Chrome Download Triggers + Strict Anti-False-Trigger Protection**
   * **Screenshots Only by Default:** Strictly ignores **Quick Share**, Nearby Share, Bluetooth transfers, messaging/social app saves, unrelated downloads, Gallery edits, and trashed images—so incoming transfers never falsely trigger FoldShot.
   * **Opt-In Triggers (OFF by default):** Want FoldShot to seamlessly pop up when you save photos from **Facebook Messenger** or download images in **Chrome / mobile browsers**? Flip either toggle on under **Auto-Open Triggers** in Settings—crop, Copy & Delete, or sort downloaded images straight into a folder on the spot.
   * **Actual Full-Resolution Photo Quality:** Even though the on-screen preview scales smoothly for fast touch cropping, **saving, moving, cropping, copying, or sharing always pulls the full-resolution source image** (using `BitmapRegionDecoder` + EXIF orientation mapping for crops, and lossless original-stream copying when moving/saving uncropped downloads).

7. **👍 One-Handed Reach + Foldable Multi-Screen Adaptation**
   * Biases an **82% scaled preview** into the lower thumb zone with a **blue crosshair loupe** so reaching top crop corners on tall or large screens feels effortless.
   * **Live Multi-Screen DPI Switching:** Listens for display topology changes (`DisplayManager` + config updates) and recalculates DPI and layout immediately when you fold or unfold.
   * **Quick Settings Tile:** Includes a universal **`FoldShot Crop`** Quick Settings tile to trigger or resume FoldShot in one tap.

8. **💾 Persistent Settings Profile + Hideable Tray Notification**
   * Automatically backs up your **Visible Folders, Top 3 Albums, Triggers, Privacy settings (excluding Vault PIN), and Crop/Layout preferences** to `Documents/FoldShot/FoldShot_Profile.json`, with 1-tap **Export / Import (`.json`)**, **Copy / Paste Profile JSON**, and automatic restore on fresh installs.
   * Want a cleaner status bar? The tray notification explicitly reminds you **`Hide in Settings`**, and you can turn on **Hide tray notification** right inside **System Integration** (or mute the channel via the included **System notification channel** shortcut for 24/7 hidden foreground uptime).
   * FoldShot Settings remembers your exact scroll position whenever you toggle switches or permissions.

9. **🔒 100% Offline, Play-Protect-Clean & Zero Bloat**
   * **Zero `INTERNET` permission** — physically impossible for your screenshots, downloads, or Vault items to leave your phone.
   * **No root, no ADB, no Accessibility Service, no boot-persistence bloat, no ads.**
   * Lightweight **~225 KB** pure native Java APK (`v2+v3` release-signed, `minSdk 29` / Android 10–16) using official Scoped Media (`MANAGE_MEDIA`).

---

## ⚔️ How FoldShot Compares

| App / Tool | iOS Copy & Delete | 1-Tap Folder Sort + Allowlist | Inline Redact + Hidden Vault | Full-Res Crop + Opt-In Downloads | Offline Privacy + Foldable DPI |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **FoldShot (`v2.2.1`)** | ✅ (Silent & instant) | ✅ (Top 3 + `3×3` + Allowlist) | ✅ (Blackout + AES-256 Vault) | ✅ (`BitmapRegionDecoder` + Opt-in) | ✅ (Zero Internet + EXIF Strip + Live DPI) |
| **Samsung Nice Shot** | ❌ (Delete only) | ❌ | ⚠️ Separate Editor | ⚠️ Separate screen | ✅ (Samsung-only) |
| **Pixel Screenshots** | ❌ | ❌ (AI Vault only) | ❌ | ❌ | ❌ (Pixel-only) |
| **Clipshot** | ⚠️ Auto-copy (all shots) | ❌ | ❌ | ❌ | ⚠️ Basic |
| **ShotSort** | ❌ | ⚠️ Auto-OCR guess | ❌ | ❌ | ✅ |
| **Screenshot Organiser** | ❌ | ✅ (List popup) | ❌ | ❌ | ⚠️ Play Store |

---

## 📲 Installation, Updates & First-Time Setup (Android 10–16)

> [!TIP]
> **Already running a version that works for you?**  
> **If it works for you, you don't have to update if you don't want to!** Keep rocking your current setup with zero hassle. If something *doesn't* work or a bug gets in your way, let me know and **I'll update it when I can.**

> [!IMPORTANT]
> **Before relying on FoldShot:** Complete **all** checks under **System Integration** inside FoldShot Settings (Photo access set to `Allow all`, Auto-open overlay, Silent delete & move, and Background/Battery settings) so it works smoothly. It takes a short moment for the screenshot integration to properly initialize on Android—**take a few quick test screenshots first** before using it on anything important!

1. Download **`FoldShot.apk` (`v2.2.1`)** from [GitHub Releases](https://github.com/dommunatorinator/FoldShot/releases/latest) (or [Google Drive mirror](https://drive.google.com/file/d/1aj8mr5IM8JRkiB2kOQI9Ypjl1CQfOJVo/view)).
   > *Reinstalling to update?* Back up your folders & preferences first using **Save / Export Profile (`.json`)** (or rely on the auto-saved `Documents/FoldShot/FoldShot_Profile.json`), and if you have items in your Hidden Vault, export a **`.foldvault` backup** before uninstalling—then import both after installing the new APK.
2. Open FoldShot and finish every row in **System Integration** (`Photo & screenshot access` -> **Allow all**, **Auto-open on screenshot**, **Silent delete & move**, and background/battery allowance).
3. *(Optional)* Configure **Auto-Open Triggers** (Messenger / Browser downloads), **Visible & Usable Folders**, and **Privacy, Vault & Markup** (set a Vault PIN & long-press spot, EXIF strip, clipboard auto-clear timer, or self-destructing Temp folder).
4. Take 2–3 quick test screenshots (or tap **Preview Full-Height Overlay**) to confirm everything is popping up cleanly. *(Optional: add the **`FoldShot Crop`** tile to your Quick Settings panel for 1-tap manual launch anytime.)*

---

## 🎓 Support a Student Dev (Side Note & Honesty Disclaimer)

FoldShot is **100% free and ad-free**.

On a personal note: I'm a **fourth-year accountancy student** working toward becoming a **Certified Public Accountant (CPA)** and **funding my own education**. There’s still a ways to go before I earn my license, so if FoldShot saves you time or keeps your gallery clean, any tip helps me pay my way through school!

* ☕ **Ko-fi:** [ko-fi.com/dommu](https://ko-fi.com/dommu)
* 💖 **PayPal:** [paypal.me/dommuyu](https://www.paypal.me/dommuyu)
* ⭐ **Free way to help:** Star this repo or share it with a friend whose Screenshots folder has 2,000+ unsorted images!

> **Transparency Disclaimer:** This app was **vibe-coded** with AI assistance. I don’t support the greedy corporate side of the AI industry one bit—but I do have to give credit where it’s due: for someone like me with a real idea and little-to-no traditional coding background, AI has been an incredible interface for computers that lets regular people turn their ideas into working tools.

---

## 🤝 Feedback & Device Testing

Want to confirm how FoldShot runs on your device or hit a bug? Open an **Issue** with your phone model and Android version. **If your current build works for you, there's never any rush to update—and if it doesn't work on your device, I'll update it when I can.**
