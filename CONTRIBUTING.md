# Contributing to HearthLog

Pull requests and bug reports are welcome!

## Dev Setup

- **JDK**: 17 or 21
- **Android SDK**: Compile/Target SDK 35, Min SDK 26
- **Gradle**: 8.11.1 (`./gradlew`)

## Code Conventions

- **Keep `:core` Pure JVM**: Put log parsing, game-state reduction, secret filtering, deck codes, and prediction logic in `:core` without any `android.*` imports. When fixing a parser bug or adding support for a new Hearthstone mechanic (like Rewind `RESET_GAME`), add a unit test under `core/src/test/kotlin/`.
- **Keep Android UI & Shizuku IPC in `:app`**: UI screens, `WindowManager` overlays, and `PowerLogReaderService` live in `:app`. Please add both English (`values/*.xml`) and Simplified Chinese (`values-zh-rCN/*.xml`) strings for any new UI text.
- **No Real Logs or Account Data in Git**: Never commit personal `Power.log` captures, real `BattleTag`s, cookies, or tokens. Use synthetic names like `PlayerOne#1001` in test fixtures.

## Before Opening a PR

```bash
./gradlew --no-daemon :core:test
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease
```

Contributions are licensed under the [GNU General Public License v3.0](LICENSE).
