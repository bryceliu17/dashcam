# Dashcam Repository Instructions

## Branch Responsibilities

- `main` is the maintained source for the current Android client, server API, web dashboard, and deployment configuration.
- `android-5-compatible` maintains the Android 5-compatible client only.
- Treat the server, web dashboard, and deployment files on `android-5-compatible` as historical snapshots. Do not update or deploy them.

## Synchronizing Main and Android 5

- Use `.agents/skills/sync-main-and-android5/SKILL.md` whenever a task ports, adapts, compares, or synchronizes Android client behavior in either direction between `main` and `android-5-compatible`.
- Compare Git commits and diffs. Never decide which implementation is newer from file modification or upload times.
- Port the smallest required business-logic changes. Never replace whole files across branches when they contain branch-specific implementation details.
- Preserve all Android 5 compatibility code unless the user explicitly authorizes a compatibility change.
- Keep the Android 5 local video archive limit at exactly 5.5 GiB: `11L * 1024 * 1024 * 1024 / 2`.
- Do not copy the 25 GiB `main` limit into the Android 5 branch.
- Keep the `main` local video archive limit at 25 GiB. Do not copy the Android 5 limit or legacy compatibility implementation back into `main`.
- Do not modify `server/`, `web-dashboard/`, `compose.yaml`, or deployment documentation while porting an Android feature to Android 5.
- Build the target Android APK after every port and inspect the final diff before committing or pushing.
