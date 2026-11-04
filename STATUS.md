# Status

Progress log of the Android SDK build. Newest entry first. On a restart, read this file, then SPEC.md, then continue.

- 2026-11-04: Phase B2 done (see verification/phase-b2.md). `Reception.identify(token)` is now `identifyWithToken`.
  Public appearance: `Reception.Appearance` (Compose-state-backed properties, so changes reach an open chat; title
  texts track whether the host set them), `CloseIcon`, `WelcomeIcon` (`symbol`/`image(Painter)`/`hidden`), `FontDesign`,
  `ColorScheme`, `appearance`, `usesRemoteAppearance`; these must sit inside `Reception.kt` (Kotlin has no nested
  typealiases), so that file is 327 lines. Internal state in `ReceptionAppearance.kt` (`resolved`, `imageUrl`, refresh
  task started at configure/foreground/session resume, cancelled in background). `configuration/`: RemoteAppearance +
  RemoteAppearanceResolution (`applying` → `ResolvedAppearance`, colors per mode as `AdaptiveColor`, null variants =
  native default chosen at draw time), AppearanceCache (envelope + ETag in SharedPreferences `sh.reception.appearance`;
  iOS keeps the ETag in memory only, the brief asked to persist it, so a cold start gets a 304), AppearanceConfiguration
  (active vs latest, `activate()` at chat opening, uses the current session only when it has the same App ID),
  HostAppearanceReport (filled into the device payload directly; `FacadeState.appearanceReport` is gone), HostAccent
  (theme `colorPrimary`, then `colorAccent`, fallback iOS system blue), AppearanceSymbols + 34 Material Symbols
  (Rounded) drawables `reception_symbol_*` (THIRD_PARTY_NOTICES.md), AppearanceIconView + AppearanceImages (own Coil
  loader, memory cache only). Theme: `ThemeAppearance.kt` (`LocalAppearance`, `LocalHostAccent`), `ThemeFonts.kt`,
  ThemeColors resolve background/secondary/incoming/accent/onAccent/welcome icon colors and `outgoingBackground(olderBy)`
  (no increased-contrast exemption on Android). Client: ImagePreparation, ImageUploader, UploadProgress,
  AttachmentImageLoader (+ `AttachmentImageState` StateFlow) and AttachmentImages (`DeviceSession.attachmentImages`,
  cleared on deactivate), TeamProfiles (StateFlows; OkHttp, not Coil, because the bytes also go to TeamProfileCache),
  LinkPolicy (`queryIntentActivities`, cached per scheme+host), `HttpExchange.data`. Phase C must: provide
  `LocalAppearance` (`ReceptionAppearance.resolved`) and `LocalHostAccent` (MaterialTheme primary when embedded,
  `HostAccent.color(activity)` in the sheet) at the chat root, call `ReceptionAppearance.configuration.activate()` when
  the chat opens, run `refreshWhileActive` while an embedded `ReceptionChatView` is visible, and use
  `activePushPrompt`. Open: the reported/default accent of Compose-only hosts (their XML theme often keeps the platform
  `colorPrimary`, e.g. `#F5F5F5` in the example) — consider reporting the accent last drawn, or `colorAccent` first.
  Next, Phase C: ChatModel port (`viewmodel/`), Compose views, presenter and `ReceptionChatActivity`, `openChat`,
  `closeChat`, `ReceptionChatView`.
- 2026-11-04: Phase B1 done except the live smoke test (see verification/phase-b1.md). Toolchain: AGP 8.9.3, Gradle
  8.11.1, no compileSdk suppression; Compose ui/foundation/material3 are `api`. Strings are Kotlin now:
  `tool/sync_strings.py` writes `sh.reception.strings.<Tag>` objects plus `Strings.kt` (keys, `languages` registry)
  from the iOS catalog plus `tool/android-strings.json` (Rate on Google Play, Support replies, 48 languages);
  `ReceptionLocalization.string/plural` pick the language and use ICU plural rules; no `res/values*` anymore.
  `AppLocale` follows the SPEC rule (per-app locale, then the system locale that `localeConfig` declares, then the
  first system locale), cached until an activity resumes. Client core in `client/` + `model/`: ReceptionAPI (OkHttp,
  `User-Agent: Reception-Android/<version>`), ReceptionAPIError and cooldown parsing, RetryScope, Backoff/RetryState,
  RequestContext (coroutine context element), ServerCooldowns, SecureStore (Keystore AES-GCM, `SecureStore.keys`
  swapped for a software key in tests), DeviceStore, ChatCache/TeamProfileCache (noBackupFilesDir, photos as files;
  `Message.images` is JPEG `ByteArray`s), DeviceSession (+ SessionAuthorization/Requests/Cooldowns/Identity/Updates),
  LogoutRevocations, ActionClickDelivery, UnreadSchedule, UnreadMonitor and `DeviceSession.refreshUnread` (the
  non-UI part of iOS `ChatModel.refreshUnread`), ApplicationLifecycle, AppearanceRequest
  (`?platform=android`, returns raw JSON + ETag). Facade: Reception.kt (public API, FacadeState) + ReceptionState.kt +
  ReceptionIdentity.kt + ReceptionAccount.kt (push token, logout, deleteData). Calls the chat model owns go through
  `ChatHooks` (`ReceptionState.chat`, null until Phase C). The example reads the App ID from `.local/app-id.txt`.
  Not wired yet (their iOS call sites): AppBadge in `setUnread`, presenter dismiss in
  `advancePresentationRevision`, appearance configure/resume/refresh in configure, `sessionResumed`, foreground,
  `refreshPaywalls`/`checkSubscriptions` in configure, foreground, logout and deleteData, automations
  `subscriptionChanged` in `setMetadata`, `invalidateRead`/`noteRead` for ChatRead, and the SSE `stream` method of ReceptionAPI.
  Next, B2: image loading for models (AttachmentImageLoader/Images with Coil), ImageUploader + UploadProgress, TeamProfiles,
  remote appearance configuration (AppearanceConfiguration, cache, icons, HostAppearanceReport filling
  `FacadeState.appearanceReport`) and the public `Appearance` type. Before that, run the live smoke test
  (verification/phase-b1.md; the T3 device tools need a Java runtime to see the emulator).
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
