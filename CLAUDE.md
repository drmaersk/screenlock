# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug        # Build debug APK
./gradlew assembleRelease      # Build release APK
./gradlew build                # Full build (debug + release)
./gradlew clean                # Clean build outputs
./gradlew lint                 # Run lint checks
./gradlew test                 # Run unit tests
./gradlew connectedAndroidTest # Run instrumented tests (requires device/emulator)
```

## Architecture

This is a two-component Android app (package `com.bumphead.invisibleoverlay`) that implements a child-lock screen overlay.

### Components

**`MainActivity`** — Configuration UI using a traditional XML layout (`res/layout/layout.xml`). Handles runtime permission requests for `POST_NOTIFICATIONS` and `SYSTEM_ALERT_WINDOW`. Saves two boolean settings to SharedPreferences (`"ChildLockPrefs"`):
- `ALLOW_UNLOCK` — whether the unlock button is visible on the overlay
- `LOCK_ROTATION` — whether screen rotation is locked while active

**`ChildLockService`** — Foreground service that creates a `WindowManager`-managed overlay view. Has two states:
- **Locked** (red button): Full-screen semi-transparent overlay with `FLAG_NOT_TOUCHABLE` cleared — blocks all touches. System gesture exclusion rects block swipe gestures.
- **Unlocked** (green button): Small button only, `FLAG_NOT_TOUCHABLE` set on the overlay — touches pass through.

The padlock button (top-right corner) requires a long-press to toggle state, preventing accidental taps. The persistent notification includes "Toggle Lock" and "Exit App" action buttons.

### Key Technical Details

- Min/Target SDK: 36 (Android 15). No backward-compat concerns needed.
- Compose is imported but the main UI uses traditional `View`/XML layout. Compose is only used for the `ui/theme/` files.
- ProGuard is disabled for release builds.
- Dependency versions are centralized in `gradle/libs.versions.toml`.
