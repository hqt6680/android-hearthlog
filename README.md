# HearthLog

**English** · [简体中文](README.zh-CN.md)

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**HearthLog** is a lightweight, open-source Android constructed deck tracker, dual-overlay match assistant, and meta explorer for *Hearthstone*, written in 100% Kotlin with Jetpack Compose.

Instead of relying on continuous screen capture or heavy OCR models, HearthLog reads Hearthstone's structured `Power.log` and `Decks.log` streams on-device through user-authorized [Shizuku](https://shizuku.rikka.app/) IPC, delivering deterministic entity tracking, opponent hand & secret deduction, and real-time decision analysis in a ~2.4 MB release APK.

---

## Highlights

- **Deterministic `Power.log` Tracking**: Tracks friendly and opponent hand counts, hand card origins (opening, drawn, returned, generated with creator tracking), remaining deck cards, board combat totals, active weapons, and graveyard minions directly from game state events.
- **Dual Split Floating Overlays**: Independent left and right floating panels that snap to screen curtains clear of display cutouts, collapse into compact bubbles, and present play advice/graveyard on the left and live deck/opponent hand tracking on the right.
- **Pure-Kotlin Constructed Decision Engine**: Reconstructs complete match state snapshots (`NeteasePowerLogStateTracker`) and queries constructed mulligan, discover, action, and live win-rate predictions (`NeteaseDirectPredictor`) plus aggregate opening keep-rate statistics (`NeteaseOpeningStats`) natively on the JVM without native `.NET` bridges or external helper apps.
- **Offline Log Replay & Audit**: Replay any saved `Power.log` at a specific turn and options packet ID to inspect state reconstruction and decision recommendations deterministically.
- **Automatic Deck Sync & Meta Explorer**: Syncs constructed decks directly from `Decks.log`, supports deck code import/export (including E.T.C. and Zilliax sideboards), calculates collection crafting dust costs, and browses Standard & Wild meta snapshots (China server & HSReplay).
- **One-Tap `log.config` Provisioning**: Enables `[Power]` and `[Decks]` logging safely via Shizuku using TOCTOU-checked `SecureDirectoryStream` file operations.

---

## Architecture

HearthLog is split into two Gradle modules with strict dependency boundaries:

| Module | Target | Responsibility |
| --- | --- | --- |
| **`:core`** | Pure Kotlin / JVM 17 | Zero Android dependencies. Houses the `PowerLogTracker` state reducer, `PowerLogDecisionGate`, `PowerLogSecretCandidates` solver, `NeteasePowerLogStateTracker` entity/zone/attachment/deck-counter serializer, `NeteaseDirectPredictor` & `NeteaseDirectCodec`, deck code codec, and meta repositories. Covered by **26 test suites / 133 JUnit & MockWebServer unit tests**. |
| **`:app`** | Android SDK 26–35 | Jetpack Compose Material 3 UI, `WindowManager` dual floating overlay service (`OverlayService`, `OverlayWindow`), AIDL-isolated Shizuku user service (`PowerLogReaderService`, `ShizukuLogSource`), and local JSON data stores. |

---

## Prerequisites & Setup

1. **Android 8.0 (API 26)** or newer.
2. **[Shizuku](https://shizuku.rikka.app/guide/setup/)** running (on Android 11+, start Shizuku directly on-device via *Developer options → Wireless debugging*).
3. **Enable Hearthstone Match Logs**:
   - Open HearthLog → **Tracker** tab → authorize Shizuku → tap **Enable Hearthstone logs** (or manually add `[Power]` and `[Decks]` sections to Hearthstone's `log.config`).
   - Fully close and reopen Hearthstone before starting a Standard or Wild match.
4. **Overlay Permission**:
   - Grant *Display over other apps* when prompted. *(Note: On Android 13–15+ with sideloaded APKs, open system **Settings → Apps → HearthLog → top-right menu `⋮` → Allow restricted settings** first if the toggle is greyed out.)*

---

## Building & Testing

Requires **JDK 17 or 21** and **Android SDK 35**.

```bash
# Run the full JVM unit & MockWebServer test suite (:core)
./gradlew :core:test

# Assemble Debug and R8-minified Release APKs (:app)
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

---

## Project Governance

- [Privacy Policy](PRIVACY.md)
- [Security Policy](SECURITY.md)
- [Contributing Guide](CONTRIBUTING.md)
- [License (GNU GPL v3.0)](LICENSE)

## Attribution & Disclaimer

HearthLog builds upon the open-source foundation of `hs-deck-tracker-android` under the [GNU General Public License v3.0](LICENSE). Card metadata is provided by [HearthstoneJSON](https://hearthstonejson.com/). HearthLog is an independent community open-source project and is not affiliated with or endorsed by Blizzard Entertainment or NetEase.
