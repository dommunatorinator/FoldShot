# 📱 FoldShot (`v2.2.1`)

**Stop letting your Screenshots folder become a graveyard.**  
Instant **1-tap folder sorting** + **iOS-style Copy & Delete** + **thumb-reachable precision cropping** right when you take a screenshot—100% offline, ~85 KB, release-signed, and built to adapt live across both screens on foldables.

[▶️ **Watch the 30-Second Demo**](https://drive.google.com/file/d/10zp5OYTAV0vMAbJnqnfiXAlXtnpYkRpP/view) · [⬇️ **Download Latest APK (v2.2.1)**](https://github.com/dommunatorinator/FoldShot/releases/latest/download/FoldShot.apk) · [☕ **Ko-fi**](https://ko-fi.com/dommu) · [💖 **PayPal**](https://www.paypal.me/dommuyu)

---

## 👋 Hey, I’m Dom — Why I Built This

I got really annoyed trying to keep my screenshots organized. Every time I saved something I wanted to **watch later**, **read later**, or keep as a **receipt**, it got dumped into one massive `Screenshots` folder—and finding *that one screenshot* later was a nightmare.

I used to use an iPhone, and the thing I missed most after switching to Samsung was **Copy & Delete** for quick one-off screenshots I never wanted cluttering my Gallery. Samsung didn’t have it, so I thought: *what if I build my own capture overlay that combines Copy & Delete with instant folder sorting?*

I'm currently daily-driving a foldable (**Galaxy Z Fold 7**), so I made sure FoldShot adapts live to **both the cover screen and the unfolded screen** (while working cleanly across Android 10–16 phones too). Honestly, I originally built it just for myself—but it saved me so much time that I figured I'd share it with everyone else.

---

## ✨ What FoldShot (`v2.2.1`) Does

1. **🗂️ 1-Tap Quick-Sort Folders + Strict Folder Filtering**
   * Shows your **Top 3 pinned Gallery folders** (e.g., `Receipts`, `Work`, `Saved`) right inside the capture bar—one tap crops and moves your screenshot straight into the right album.
   * **Expandable `3×3` Folder Drawer:** To keep the main overlay clean, it stays capped at 3 quick pills—with a small **`2×2` expand button** on the left that opens a paginated **`3×3` drawer** for your other folders.
   * **Visible & Usable Folders Control:** Don’t want random WhatsApp/Instagram/Downloads folders showing up? Enable **Restrict to selected folders only** in Settings and pick (or add) the exact folders FoldShot is allowed to see and save to.

2. **📋 Quiet, Native-Feeling iOS "Copy & Delete"**
   * Need to grab something just to paste it into a chat or note? Tap **`Copy & Delete`**—FoldShot writes your cropped image to the clipboard, silently deletes the raw screenshot from your Gallery, and disappears cleanly (with zero redundant Toast clutter overlapping Android's own clipboard confirmation).

3. **👍 One-Handed Reach + Foldable Multi-Screen Adaptation**
   * Biases an **82% scaled preview** into the lower thumb zone with a **blue crosshair loupe** so reaching top crop corners on tall or large screens feels effortless.
   * **Live Multi-Screen DPI Switching:** Listens for display topology changes (`DisplayManager` + config updates) and recalculates DPI and layout immediately when you fold or unfold.
   * **Quick Settings Tile:** Includes a universal **`FoldShot Crop`** Quick Settings tile to trigger or resume FoldShot in one tap.

4. **🔒 100% Offline, Play-Protect-Clean & Zero Bloat**
   * **Zero `INTERNET` permission** — physically impossible for your screenshots to leave your phone.
   * **No root, no ADB, no Accessibility Service, no boot-persistence bloat, no ads.**
   * Lightweight **~85 KB** pure native Java APK (`v2+v3` release-signed, `minSdk 29` / Android 10–16) using official Scoped Media (`MANAGE_MEDIA`).

---

## ⚔️ How FoldShot Compares

| App / Tool | iOS Copy & Delete | 1-Tap Folder Sort at Capture | Inline Crop | Foldable Dual-Screen DPI | 100% Offline / No Ads |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **FoldShot (`v2.2.1`)** | ✅ (Silent & instant) | ✅ (Top 3 + `3×3` + Allowlist) | ✅ (Thumb-zone + Loupe) | ✅ Live Switch | ✅ (Zero Internet perm) |
| **Samsung Nice Shot** | ❌ (Delete only) | ❌ | ⚠️ Separate screen | ✅ | ✅ (Samsung-only) |
| **Pixel Screenshots** | ❌ | ❌ (AI Vault only) | ❌ | ❌ | ❌ (Pixel-only) |
| **Clipshot** | ⚠️ Auto-copy (all shots) | ❌ | ❌ | ❌ | ✅ |
| **ShotSort** | ❌ | ⚠️ Auto-OCR guess | ❌ | ❌ | ✅ |
| **Screenshot Organiser** | ❌ | ✅ (List popup) | ❌ | ❌ | ⚠️ Play Store |

---

## 📲 Installation & First-Time Setup (Android 10–16)

> [!IMPORTANT]
> **Before relying on FoldShot:** Complete **all** checks under **System Integration** inside FoldShot Settings (Photo access set to `Allow all`, Auto-open overlay, Silent delete & move, and Background/Battery settings) so it works smoothly. It takes a short moment for the screenshot integration to properly initialize on Android—**take a few quick test screenshots first** before using it on anything important!

1. Download **`FoldShot.apk` (`v2.2.1`)** from [GitHub Releases](https://github.com/dommunatorinator/FoldShot/releases/latest) (or [Google Drive mirror](https://drive.google.com/file/d/1aj8mr5IM8JRkiB2kOQI9Ypjl1CQfOJVo/view)).
   > *Upgrading from an older debug build (`v2.1.1` or earlier)? Uninstall the old version first (including inside Samsung Secure Folder if installed there), since `v2.1.2+` uses FoldShot's dedicated release signing certificate.*
2. Open FoldShot and finish every row in **System Integration** (`Photo & screenshot access` -> **Allow all**, **Auto-open on screenshot**, **Silent delete & move**, and background/battery allowance).
3. Take 2–3 quick test screenshots (or tap **Preview Full-Height Overlay**) to confirm the screenshot integration is initialized and popping up cleanly. *(Optional: add the **`FoldShot Crop`** tile to your Quick Settings panel for 1-tap manual launch anytime.)*

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

Want to confirm how FoldShot runs on your device or report an edge case? Open an **Issue** with your phone model and Android version—I’d love to hear your feedback.
