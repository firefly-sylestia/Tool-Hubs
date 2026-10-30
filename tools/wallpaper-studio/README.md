# Wallpaper Studio

Wallpaper Studio is a standalone Android app module for local wallpaper collections and event-driven scene switching.

## What it does

- Imports selected images through Android Photo Picker and persists URI read grants.
- Applies a scene to the Home screen, Lock screen or both with Android WallpaperManager.
- Supports unlock/keyguard-dismissed, charging, unplugged, low-battery and Wi-Fi connection state triggers.
- Supports daily time scenes with battery-aware, inexact AlarmManager delivery.
- Resolves overlapping events by scene priority and falls through to the next matching scene if an image cannot be applied.
- Publishes enabled scenes as dynamic app shortcuts, within the launcher's reported shortcut limit.
- Preloads up to six enabled scene images into a bounded 32 MiB memory cache; large images bypass the cache and are streamed directly. Original encoded bytes are retained so EXIF orientation and Android's own crop behavior remain intact.
- Skips duplicate writes when the same scene is applied again within 15 seconds, avoiding redundant wallpaper-service work during clustered unlock or system events.
- Offers optional Shizuku connection detection, permission request and ADB/root identity reporting. Shizuku is not required for ordinary wallpaper changes.

## Background model

Android 8+ does not deliver several implicit broadcasts to manifest receivers. Event scenes therefore use WallpaperMonitoringService, a special-use foreground service with a low-importance ongoing notification. It registers a runtime receiver while at least one non-time event scene is enabled and stops when all such scenes are disabled or removed. Time-only scenes use alarms and do not run the monitor.

The service is started while the app is visible and is also requested after boot or package replacement. Android and OEM background-start / battery policies can still prevent a restart; opening the app resynchronises it. On Android 13+, the app requests notification permission when the user enables an event scene, but the user can deny it.

## Shizuku integration

Shizuku is an optional system bridge. Wallpaper Studio detects whether the service is installed/running, requests its own permission only after the user taps Grant access, and reports whether the service is running as ADB shell or root. It does not request root, silently execute commands, or depend on Shizuku to set wallpapers. The supported WallpaperManager API remains the primary application path; using Shizuku as a blanket wallpaper-setting shortcut is not reliably faster and can be less compatible across Android/OEM versions. On non-rooted Android 11+ devices, Shizuku is started through Wireless debugging and normally needs to be restarted after reboot.

## Biometric boundary

ACTION_USER_PRESENT indicates that the keyguard has been dismissed; it does not identify whether the user used Face Unlock, fingerprint, PIN or another method. A distinct fingerprint condition may be available through Samsung Routines+ on some Galaxy / One UI versions. The app publishes a scene shortcut for compatible automation integrations, but does not claim a face-specific trigger or guarantee that Routines+ exposes the shortcut on every version.

## Performance

The app warms a small, bounded cache of the highest-priority enabled scenes in the background after launch and scene edits. Cached data is the original image byte stream rather than a re-encoded bitmap, preserving orientation metadata and Android's crop handling. The cache is memory-only and automatically evicts older images. A 15-second same-scene guard prevents duplicate wallpaper writes during rapid event bursts.

## Permissions

- SET_WALLPAPER — apply the selected wallpaper.
- RECEIVE_BOOT_COMPLETED — restore time alarms and request the event monitor after boot.
- ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE — inspect the active transport after a Wi-Fi event.
- FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE — keep the event listener alive while enabled scenes need it.
- POST_NOTIFICATIONS — show the ongoing automation notification on Android 13+.

Photo access is scoped to the images selected by the user; no broad media/storage permission is requested. Images and scene rules are kept locally.

## Build

```bash
gradle :tools:wallpaper-studio:assembleRelease
```

Version source of truth: build.gradle.kts (versionCode 2, versionName 0.2.0).
