# HearthLog

**English** · [简体中文](README.zh-CN.md)

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**HearthLog** is an open-source *Hearthstone* deck tracker and match assistant for Android, written in Kotlin with Jetpack Compose. The release APK is around 2.5 MB.

Instead of burning battery on screen capture and OCR, HearthLog reads Hearthstone's own `Power.log` and `Decks.log` on-device via [Shizuku](https://shizuku.rikka.app/). Everything from entity tracking and secret elimination to constructed decision analysis runs directly in pure Kotlin—no native `.NET` bridges or companion PC apps needed.

---

## What It Does

- **Split Floating Overlays**: Two draggable edge-snapping panels that collapse into small bubbles when you don't need them. The left panel shows mulligan/discover/play advice, win-rate estimates, and graveyards; the right panel tracks your remaining deck, opponent hand turns & card origins, secret candidates, and board/hero combat totals.
- **Deck Sync & Management**: Picks up your queued deck automatically from `Decks.log`, supports deck codes (including E.T.C. and Zilliax sideboards), mana curves, and crafting dust costs.
- **Meta Browser**: Browse Standard and Wild meta snapshots from both the China server and HSReplay.
- **Offline Log Replay**: Drop in a saved `Power.log`, pick a turn and options packet ID, and step through how the tracker reconstructed the board and predictions.
- **One-Tap Log Setup**: Writes `[Power]` and `[Decks]` into Hearthstone's `log.config` via Shizuku so you don't have to hunt down folders manually.

---

## Project Layout

- **`:core` (Pure Kotlin / JVM 17)**: Zero Android dependencies. Contains the `Power.log` / `Decks.log` parsers, match state tracker, secret candidate filter, deck code codec, meta clients, and prediction engine, backed by 136 unit tests (`./gradlew :core:test`).
- **`:app` (Android Jetpack Compose)**: Material 3 UI, dual `WindowManager` overlays, and the Shizuku `UserService` (`PowerLogReaderService`) that tails logs across processes.

---

## Getting Started

1. **Android 8.0 (API 26)** or higher.
2. Install and start **[Shizuku](https://shizuku.rikka.app/guide/setup/)** (on Android 11+, you can start it right on the device via *Developer options → Wireless debugging*).
3. Open HearthLog → **Tracker** → authorize Shizuku → tap **Enable Hearthstone logs** (or add `[Power]` and `[Decks]` to Hearthstone's `log.config` manually).
4. **Force-close and reopen Hearthstone** so it picks up the new `log.config`.
5. Grant **Display over other apps** when prompted.
   - *Tip for Android 13+*: If the overlay toggle is greyed out after sideloading the APK, go to **Settings → Apps → HearthLog → top-right `⋮` → Allow restricted settings** first.
   - *Note on Hearthstone's 10 MB log cap*: Hearthstone on Android stops writing to `Power.log` once a single session file hits `10000KB` (usually after 4–5 long games). If HearthLog tells you the log was truncated, just restart Hearthstone to start a fresh log file.

---

## Building & Testing

Requires **JDK 17 or 21** and **Android SDK 35**:

```bash
# Run the JVM unit tests
./gradlew :core:test

# Build Debug / Release APKs
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

---

## Docs & Credits

- [Privacy](PRIVACY.md) · [Security](SECURITY.md) · [Contributing](CONTRIBUTING.md) · [GPL-3.0 License](LICENSE)
- Built upon the open-source `hs-deck-tracker-android` codebase under GPL-3.0. Card data comes from [HearthstoneJSON](https://hearthstonejson.com/). HearthLog is an independent community project and is not affiliated with Blizzard Entertainment or NetEase.
