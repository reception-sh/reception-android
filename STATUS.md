# Status

Progress log of the Android SDK build. Newest entry first. On a restart, read this file, then SPEC.md, then continue.

- 2026-11-04: Fixes 3 done except the live run (see verification/fixes-3.md): the Phase C1 audit (F-1..F-9) and
  D-1..D-3, one commit each. `token()`/`renewedToken()` recheck the token and shared task after the credential read;
  `LogoutRevocations.enqueue` returns `Deferred<Boolean>` (on disk or not) after the initial load and merged write;
  `DeviceStore.open()` always restores `ChatCache.latestMessageAt` (getter memory-only); launch registration rechecks
  `ApplicationLifecycle.isActive` after `prepared()` and right before registering; `AppLocale.languageTag` never parses
  LocaleConfig (provisional answer uncached until `prepare` publishes); TeamProfiles' cached-photo eligibility follows
  the team's photo ID and `showsPhotos`, its load runs outside the caller's job and writes need a successful load;
  `hostBackground()` applies the baseline-scheme rule (all 36 roles vs `lightColorScheme()`) to mode and accent;
  `ComposerModel.isPicking` keeps the chat visible, active and streaming under the photo picker (rotation still
  reloads once); read generation lives in `ReceptionUnread`; `ChatPolling.kt`; credential helpers in
  `SessionCredentials.kt`. Open: the StrictMode live run (the emulator was held by another device session).
- 2026-11-04: Phase C1 done (see verification/phase-c1.md). `viewmodel/` ports the iOS ViewModel (one file per iOS
  file; `ChatMerge`, `ChatAutomaticRetry`, `MessagePhotos` split out). `ChatModel` holds Compose state, runs its tasks on
  `Dispatchers.Main` (enqueued like iOS `Task {}`), restores history and draft on the storage thread after
  `session.prepared()` (`isRestored`; nothing loads or sends before), and is `ReceptionState.chat` (created by
  `chatModel()`, stopped and replaced on every reset; `ChatHooks` is gone). `ReceptionUnread` refreshes through
  `ChatModel.refreshUnread`; `invalidateRead`/`noteRead` live in `ReceptionState`; `revision` is Compose state, so an open
  chat rebuilds with the new model. `client/EventStream` (okhttp-sse) and `client/NetworkMonitor` (default network
  callback). Presentation: `ReceptionChatActivity` (manifest: translucent `Reception.Chat` theme, `adjustResize`,
  predictive back, not exported) + `ChatSheet` (`ModalBottomSheet`, own grabber, keyboard rule through
  `confirmValueChange` because Material 3 1.3.1 has no `sheetGesturesEnabled`), `ChatPresenter` (open/close/dismiss,
  `locksSheet`), `ChatRoot` (SDK theme, dark mode, contrast, layout direction, baseline-theme rule), `ChatContent`
  (started = visible/`isChatOpen`/`ContrastMonitor`, resumed = loading and stream; a configuration change keeps both),
  public `ReceptionChatView` composable and View, `Reception.openChat`/`closeChat`. Library manifest adds
  `ACCESS_NETWORK_STATE` and the Custom Tabs `<queries>`. Example: Support opens the sheet with the unread badge,
  "Embedded chat" embeds the view under the example's own theme.
  C2 must: render cards in `MessageBubble` (REVIEW, LINK, PAYWALL fall back to a text bubble now), the lightbox
  (confirmed photos are not tappable yet), the jump-to-latest control (`ChatScrollState` is ready, `ChatHistory` keeps
  it), the typing indicator (`ChatModel.isTyping` is set by the stream), team avatars (`MessageLayout.authorPhoto` /
  `showsAuthorPhoto`; the reserved column is an empty spacer), the reply haptic, `ChatModel+Links` (screens, host links,
  link-card receipts, `opensWebLinksInApp`; `ui/TextLinks` does SPEC path 3 only), and the visual pass against the iOS
  simulator (not done in C1): composer surface translucency, title chip material, the black status-bar strip above the
  light sheet, message and status animations, the Sending opacity. Files over the guideline: `ChatModel.kt` 225,
  `ReceptionState.kt` 210, `Reception.kt` 299.
  D must: the automation reveal in `ChatLoading.load` (`takeOpenedChat`, typing then the arriving reply), the push
  prompt (`ChatPushPrompt`, `PushPromptDecision`, notification permission through the hosting activity),
  `refreshPaywalls()` when the chat appears, AppBadge in `ReceptionUnread.set`, notification taps opening the chat.
  Notes: the stream's quiet refresh is a 60 s timer in the reader because okhttp-sse does not surface `: ping`
  comments; rotation reloads once;
  a language change applies to layout direction on the next open (`ReceptionLocalization.layoutDirection` is not Compose
  state). Live verification needs Gboard on the emulator (the device CLI's test IME has no insets).
- 2026-11-04: Fixes 2 done (see verification/fixes-2.md): the Phase B2 audit plus the decided splits. LinkPolicy caches
  per complete URL (last 64; `LinkPolicy.of(context)` for tests). TeamProfiles takes `storage`/`decoding` dispatchers,
  restores asynchronously and chains cache writes after the load. `ThemeMode.kt`: `LocalReceptionDarkMode`,
  `resolveDarkMode`, `receptionDarkMode(embedded)`, `LocalReceptionIncreasedContrast`, `fadesOutgoing`;
  `ContrastMonitor` (Android 14+, reference-counted `start`/`stop`). Splits: `DeviceStore` + `DeviceStoreChat`
  (extension properties) + `DeviceStoreCredentials` + `ScopedPreferences`; `DeviceSession` + `SessionLifecycle`
  (registerIfNeeded, becameActive, requestSucceeded, recordFailure); `ReceptionState` + `ReceptionUnread` (count,
  set, cancelRefresh, refresh); `Reception.kt` delegates to `ReceptionSettings` and `ReceptionAccount`. It is still
  ~290 lines because of the nested public `Appearance` type. No main source file is over ~200 lines except
  `Reception.kt` and `SessionAuthorization.kt` (201).
- 2026-11-04: Phase C must also, in the SDK's own sheet: apply `MaterialTheme` with `dynamicLight/DarkColorScheme` on
  Android 12+ and the iOS blue (#007AFF/#0A84FF) below; provide `LocalReceptionDarkMode` (`receptionDarkMode(embedded =
  false)` in the sheet, `true` in an embedded `ReceptionChatView`) and `LocalReceptionIncreasedContrast`
  (`ContrastMonitor.increasedContrast` collected); call `ContrastMonitor.start(context)`/`stop()` as the chat becomes
  visible and hidden.
- 2026-11-04: Fixes 1 done (see verification/fixes-1.md): the client-core audit plus three decided items, one commit
  each. Storage: `client/Storage.kt` is the single storage thread (`Dispatchers.IO.limitedParallelism(1)`); SecureStore
  commits and returns the real result; a `DeviceSession` opens its `DeviceStore` there (`DeviceStore.open()`: generation,
  preferences load, restored-chat check, `hasStartedChat` from the chat cache) after the previous session's deletion,
  then loads credentials; `prepared()` is awaited by every `ReceptionAPI.request` and launch registration;
  `afterPrepared {}` defers what needs the verified user (identifyWithToken's decision, action clicks);
  `stage {}` changes `session.pending` (the identity sent with registration/updates) in memory and persists it
  asynchronously. Logout: memory reset at once, the revocation job (`LogoutRevocations.enqueue` → Job) persists first,
  then `store.reset()` deletes and rotates the generation; the next session opens after it. The appearance cache,
  `AppLocale`'s declared locales (`AppLocale.prepare`) and TeamProfiles' cache load on the storage thread too. Renewal and
  registration deferreds clear themselves only when they finish. Launch registration starts on a later main-looper turn.
  Namespaces: `DeviceStore.namespace(appId, serviceUrl)` (bare App ID for the hosted service); revocations keep their
  service address. `AppLocale.resolve`: no declared match → the first declared locale. Accent: `HostAccent` and
  `LocalHostAccent` are gone; `Theme.accent` falls back to `MaterialTheme.colorScheme.primary`; the report sends the
  accent only when set. Library manifest declares INTERNET; `-PreceptionExampleBare=true` builds the example without
  Firebase/Billing/google-services. The example dies on main-thread disk access in debug builds (StrictMode) and has a
  Log out button. `ReceptionState.resetForTests()` (`@VisibleForTesting(NONE)`). Phase C must: wrap the SDK's own
  sheet in `MaterialTheme` with `dynamicLight/DarkColorScheme` on 31+ and #007AFF/#0A84FF below; provide
  `LocalAppearance` at the chat root; call ChatCache/TeamProfileCache only through `Storage`; read `session.pending`,
  not `store.pending`; wait for `session.prepared()` (or `afterPrepared`) before touching `session.store` values.
  Open: `DeviceStore.kt` is 290 lines and `DeviceSession.kt` ~230 (over the ~200 guideline); `isRegistered` is false
  while secure storage is unreadable (iOS re-reads the Keychain there; Android re-reads only in suspend paths).
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
