---
name: sync-main-and-android5
description: Safely port, compare, or synchronize selected Android client features and fixes in either direction between the dashcam repository's main and android-5-compatible branches while preserving each branch's storage limits, dependencies, camera implementation, and platform compatibility. Use when Android work developed on either branch must be adapted to the other branch.
---

# Sync Main and Android 5

## Workflow

1. Confirm the source branch, target branch, requested behavior, active branch, and working-tree state.
2. Identify source commits with Git history and compare the relevant code with Git diffs. Never infer freshness from file modification or upload times.
3. Separate portable behavior from branch-specific implementation.
4. Patch the smallest target surface. Do not copy entire files across branches when they contain platform-specific code.
5. Apply the target branch invariants below.
6. Build the target Android APK, inspect the final diff, and report what was adapted rather than copied.

## Shared Invariants

- Keep `applicationId = "com.example.dashcam"`.
- Preserve database schemas and API payload contracts unless the user explicitly requests and approves a migration.
- Delete at most one oldest unlocked video when storage cleanup is required.
- If cleanup is required but no unlocked video can be deleted, do not start the next segment.
- Do not modify server, dashboard, Docker, Compose, or deployment files as an incidental part of an Android branch sync.
- If the feature requires a server change, report it and handle it as an explicit `main` server task.

## Target: Android 5

When porting from `main` to `android-5-compatible`:

- Keep `minSdk = 21`, the Android 5 version identity, and the branch's pinned dependency versions.
- Keep CameraX `1.3.4`, WorkManager `2.9.1`, and the existing AndroidX/Lifecycle versions unless an explicit, tested compatibility migration is requested.
- Keep `MAX_VIDEO_BYTES` at exactly 5.5 GiB:

```kotlin
const val MAX_VIDEO_BYTES = 11L * 1024 * 1024 * 1024 / 2
```

- Never copy the 25 GiB `main` limit into Android 5.
- Preserve the legacy `android.hardware.Camera` background-recording path for API 21 and 22.
- Preserve the Android 5 camera-release delay, version-gated notifications, `PendingIntent` flags, `stopForeground` calls, and service lookup fallbacks.
- Do not introduce unguarded APIs above API 21.
- Keep `SimpleDateFormat` UTC serialization instead of `java.time.Instant` on the Android 5 path.
- Preserve the Android 5 connection-closing, connection-pool, and upload-worker compatibility behavior.
- Treat the Android 5 branch's server, dashboard, and deployment files as historical snapshots. Do not update or deploy them.

## Target: Main

When porting from `android-5-compatible` to `main`:

- Extract the intended feature or bug-fix behavior and implement it using the current `main` architecture.
- Keep `main` at `minSdk = 26`, its current dependency versions, and its modern camera/service implementation.
- Keep `main` `MAX_VIDEO_BYTES` at exactly 25 GiB:

```kotlin
const val MAX_VIDEO_BYTES = 25L * 1024 * 1024 * 1024
```

- Do not copy the Android 5 5.5 GiB limit into `main`.
- Do not downgrade CameraX, AndroidX, Lifecycle, or WorkManager.
- Do not copy legacy Camera API branches, old API fallbacks, artificial release delays, or Android 5-specific networking workarounds unless the same behavior is independently required on modern Android.
- Keep the maintained server, dashboard, and deployment implementation on `main`; do not replace it with files from Android 5.

## Validation

Before committing or pushing:

1. Confirm the active branch is the intended target.
2. Confirm the diff contains only requested target-side changes.
3. Confirm the target storage limit: 5.5 GiB on Android 5 or 25 GiB on `main`.
4. Confirm `minSdk`: 21 on Android 5 or 26 on `main`.
5. For Android 5, search changed code for unguarded APIs above API 21.
6. Build `android-app/gradlew.bat :app:assembleDebug` using JDK 17 and the configured Android SDK.
7. Summarize the source behavior, target adaptation, preserved branch-specific constraints, and build result.
