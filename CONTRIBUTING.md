# Contributing to HearthLog

Thank you for your interest in contributing to **HearthLog**!

## Development Environment

- **JDK**: 17 or 21
- **Android SDK**: Compile SDK 35, Build-Tools 35.0.0, Min SDK 26
- **Build System**: Gradle 8.11.1 (`./gradlew`)

## Module Guidelines

1. **Keep `:core` Pure JVM**:
   - All `Power.log` / `Decks.log` parsers, entity state reducers, secret candidate solvers, deck code codecs, and prediction protocol logic belong in `:core` with zero `android.*` dependencies.
   - Every parser change, bug fix, or protocol adjustment in `:core` should include deterministic unit tests under `core/src/test/kotlin/` (using `MockWebServer` for HTTP transport tests).
2. **Android UI & Services in `:app`**:
   - Jetpack Compose screens, floating `WindowManager` overlays, and Shizuku `UserService` IPC live in `:app`.
   - Keep user-facing strings localized in both `app/src/main/res/values/*.xml` and `app/src/main/res/values-zh-rCN/*.xml`.
3. **Privacy & Sanitization**:
   - Never commit real player `BattleTag` identifiers, session cookies, tokens, or personal `Power.log` captures into test fixtures or source files. Use synthetic identifiers (e.g., `LocalPlayer#1234`) in all unit tests.

## Running Checks Before Submitting a PR

```bash
# Run all 133+ JVM unit tests
./gradlew --no-daemon :core:test

# Verify Debug and R8-minified Release APK builds
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease
```

## License

By contributing to HearthLog, you agree that your contributions will be licensed under the [GNU General Public License v3.0](LICENSE).
