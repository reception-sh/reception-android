# Status

Progress log of the Android SDK build. Newest entry first. On a restart, read this file, then SPEC.md, then continue.

- 2026-11-04: Phase A done (see verification/phase-a.md). Exists: Gradle 8.9 wrapper, version catalog with the SPEC.md
  versions, `:reception` (namespace `sh.reception`, explicitApi, warnings as errors, lint.xml, consumer-rules.pro,
  vanniktech publishing to the Central Portal, signing only when signing properties exist) and `:example`
  (`sh.reception.receptionAndroidExample`, Support button, Firebase plus Billing, google-services applied only when
  `example/google-services.json` exists; that file is copied from `.local/` and gitignored). Core files: `Log.kt`,
  `Version.kt`, `Localization.kt` (+ `AppLocale.kt`: LocaleManager on 33+, AppCompat by reflection below, else the
  first system locale), `Theme.kt` (iOS 18 composer metrics) + `ThemeAnimations.kt` + `ThemeColors.kt` (iOS system
  colors following the system color scheme, without appearance overrides yet), `Reception.kt` (version, logLevel,
  logHandler). `tool/sync_strings.py` generates 51 files (values, 46 language folders plus in/iw duplicates, Strings.kt)
  from the iOS catalog; `--check` for release checks. Next, Phase B: the rest of the `Reception` facade
  (configure, identity, push, paywalls, screens, events, appearance model, languageOverride feeding
  `ReceptionLocalization.language`), `client/`, `model/`, `viewmodel/`, then the Theme colors resolved from the
  appearance. Open: language splits in host app bundles, the Android wording of "Rate on the App Store", the
  untranslated "Support replies" channel name, and whether the SPEC.md first-system-locale rule is meant to replace the
  iOS rule (English-only hosts stay English).
- 2026-11-04: Repo created. SPEC.md and AGENTS.md written. Toolchain: JDK 17 (/opt/homebrew/opt/openjdk@17), Android
  SDK at ~/Library/Android/sdk (platform 36, build-tools 36.0.0, emulator, Play image), AVD `reception_pixel8`
  (Android 16, Play services). Firebase project `reception-android-test`, Android app `sh.reception.receptionAndroidExample`;
  `google-services.json` and the FCM service-account key live in `.local/` (not committed). FCM v1 auth verified.
  Next: Phase A (Gradle scaffold, strings sync, Log/Version/Localization), backend FCM in `../reception` in parallel.
