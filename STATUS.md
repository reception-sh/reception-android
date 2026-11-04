# Status

Progress log of the Android SDK build. Newest entry first. On a restart, read this file, then SPEC.md, then continue.

- 2026-11-04: Repo created. SPEC.md and AGENTS.md written. Toolchain: JDK 17 (/opt/homebrew/opt/openjdk@17), Android
  SDK at ~/Library/Android/sdk (platform 36, build-tools 36.0.0, emulator, Play image), AVD `reception_pixel8`
  (Android 16, Play services). Firebase project `reception-android-test`, Android app `sh.reception.receptionAndroidExample`;
  `google-services.json` and the FCM service-account key live in `.local/` (not committed). FCM v1 auth verified.
  Next: Phase A (Gradle scaffold, strings sync, Log/Version/Localization), backend FCM in `../reception` in parallel.
