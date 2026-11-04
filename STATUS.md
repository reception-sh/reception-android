# Status

Progress log of the Android SDK build. Newest entry first. On a restart, read this file, then SPEC.md, then continue.

- 2026-11-04: Fixes 11 done (see verification/fixes-11.md), one commit each. Y-1 `ChatPresenter.present(stillWanted)`:
  a tap's open carries its routing generation through the record save and is withdrawn right before the sheet starts
  if routing turned off meanwhile (also off-then-on); the host's own requests always start. Y-4 no sheet over a host
  that finished or was destroyed during the save. Y-2 `present(…, completed)` reports whether the sheet started;
  `AutomationPresenter.present` suspends on it, so an automation's open rule retries a cancelled presentation within
  its 5 s window, which is checked again right before the sheet starts. Y-3 FCM message IDs are written with
  `commit()` before the notification posts (failure logged, message handled once). Z-2 SPEC: the Compose `Dialog`
  soft-input override and the Android 10 pan limit. Z-1 the flaky test is
  `ChatPresentationTest.aLateConfigureFollowedByALogoutDoesNotRestore` (found under CPU load: `withContext` returns
  synchronously when the storage read finishes first, so the restore completed inside `configure`, before the test's
  logout, whose animated dismissal never ends in Robolectric); the test now holds the storage thread across configure
  and logout; 60 loaded class runs and 10 full `--rerun-tasks` runs green (500 tests). Example (debug): `…TRACK --el
  secondAfter <ms>`. `tool/release_check.sh` passes; live on Android 16 StrictMode-clean: automation open, the retry
  over a second screen started during the save, routing off during a tap's save, normal tap.
- 2026-11-04: Fixes 10 done (see verification/fixes-10.md), one commit each. X-2 `ui/KeyboardWindow`: the embedded
  chat adjusts the window that shows its composition (a Compose `Dialog`'s, the activity's, or a platform dialog's
  through its root's window parameters), counted per window; found live: a Compose `Dialog` measures its content
  against the display, so its window adjusts nothing from Android 11 and pans before (f106957). X-3 only the adjust bits
  go back, and only while the window still carries the SDK's mode. X-1 `adjustNothing` resizes under an embedded chat
  before Android 11. X-4 the paywall limits are private (`apiDump` −2 fields). X-5 `notificationSmallIcon` is stored by
  resource name in `sh.reception.push` and restored with `getIdentifier` in a process FCM started (plugins need no
  persistence). Example (debug): Chat in a dialog, `--ez adjustNothing true`, App notification icon switch.
  `tool/release_check.sh` passes; `--rerun-tasks`: 18 of 19 runs green, one run failed one unnamed test (reports
  overwritten, not reproduced in 14 further runs); live on Android 16 and Android 10 (`reception_api29`, shut
  down after) StrictMode-clean: X-1 composer above Gboard, X-2 dialog `nothing` with the activity untouched, X-5 the
  star icon from a killed process with a real FCM reply. Open: on Android 10 a Compose `Dialog`'s pan clips the
  composer's lower edge (cursor line visible); whether overriding a host's explicit mode on a Compose `Dialog` is
  acceptable; the unnamed flaky test.
- 2026-11-04: Fixes 9 done (see verification/fixes-9.md), one commit each. U-1 `handlePushMessage(context, data,
  messageId)` (new overload): the SDK's service passes `RemoteMessage.messageId`, which Firebase keeps out of `data`, so
  real redeliveries now deduplicate; U-5 one global list of 64 message IDs across configure. U-2 no token leaves an
  ended session (checks after each suspension), and the retry after a rejected token is authorized again. U-3/U-4
  `PendingChatOpen`: routing off cancels a tap's queued open (generation), never the host's own. U-6 a covered sheet's
  reorder waits for its own task. U-7 the sheet starts only after its presentation record is committed, over the same
  activity. U-8 an unread delivery stops once a listener published a newer count. U-9 the push test awaits the refresh
  job. V-1 the permission card resolves its activity at call time (rebound `MutableContextWrapper`). V-2 the chat is
  keyed by the session's persisted identity (`DeviceStore.chatIdentity`), so launcher keys survive a process restart.
  W-1 `ReceptionState` split into `ReceptionResets` and `ReceptionLifecycle`. `tool/release_check.sh` passes, 5×
  `--rerun-tasks` green; live U-1 (real FCM through the service, repeat ignored) and U-7 (restore after `am kill`)
  StrictMode-clean; V-2 not reproducible live (translucent picker leaves the sheet without saved state). Open: the
  customer docs should show `handlePushMessage(this, message.data, message.messageId)`.
- 2026-11-04: Phase E done (see verification/phase-e.md): 1.0.0 is ready to publish. E-1 the setup check also starts
  from the activity a plugin hands to `noteCurrentActivity` (Flutter/RN run the same adb command). E-2 a sheet restored
  after process death waits up to 3 s for a late `configure` (logout, reconfiguration, newer presentations still finish
  it). E-3 `ui/SheetGestures`. `Version.kt` is the only version source (Gradle reads it); public API tracked in
  `reception/api/reception.api` (binary-compatibility-validator, `apiCheck`). README, CHANGELOG (1.0.0 unreleased),
  CONTRIBUTING; THIRD_PARTY_NOTICES checked (Material Symbols only). `tool/release_check.sh` passes on a clean tree
  (signing step skipped: no key on this machine). Found and fixed on the way: coroutines are `api` (public
  `StateFlow`s); an embedded chat in a host window that pans for the keyboard (no `windowSoftInputMode`, Android
  Studio's Compose template) floated a keyboard height too high, now `ui/EmbeddedKeyboardMode` resizes such a window
  while a chat is embedded and restores it. Full matrix on the emulator with StrictMode: all steps passed (font scale
  1.35 instead of 1.3, cooldown via the local limit budget); 0 StrictMode lines from the app, 0 FATAL.
  **Andi's steps to publish:** (1) create a Sonatype Central Portal account (central.sonatype.com) and add the namespace
  `sh.reception`; (2) add the verification key it shows as a DNS TXT record on `reception.sh` and verify the namespace;
  (3) generate a portal user token; (4) create a GPG key, upload its public key to keyserver.ubuntu.com, and put
  `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, `signingInMemoryKeyId`,
  `signingInMemoryKeyPassword` into `~/.gradle/gradle.properties` (never the repo); (5) set the CHANGELOG date for
  1.0.0, run `tool/release_check.sh` (the signing step must now run), then `./gradlew publishToMavenCentral` and publish
  the deployment in the portal; (6) `git tag 1.0.0`, push `main` and the tag, GitHub release with the CHANGELOG text.
  Flutter (`reception_flutter` 1.2.0) and React Native (`react-native-reception` 1.6.0) wait for the artifact on Maven
  Central. Known limits: the Play Billing live path (a signed-in Google account with a purchase) is unverified, only
  `PaidAccessTest` and the "billing unavailable" path ran; the iOS `ChatPreview` launch argument is not ported; team
  replies from scripts appear on the next load because the dev server's event bus is in-process. Open: the Flutter/RN
  docs can mention that the setup check and the sheet restore now work for them.
- 2026-11-04: Fixes 8 done (see verification/fixes-8.md), one commit each. T-1 a superseding paid-access read is
  installed before the previous one is cancelled. T-2 the token gate waits for credentials that changed during a save.
  T-3 the trampoline restores any app-rooted Recents task (also without activity records), never its own. T-4
  `ui/CoveredSheet`: a sheet covered in its own task while another task is in front comes back above the cover (task
  moved to the front, reorder from the activity that resumes there; `AppTask.startActivity` ignores the flag). T-5 the
  chat model counts its started/resumed chats, so removing one of two embedded chats keeps the other running. T-6 the
  sheet (and its lightbox) is restored after process death: a random presentation token per presentation, recorded
  with the namespace in `sh.reception.presentation`; any dismissal or reset clears it; the first configure cancels
  nothing. T-7 an unread listener removed during a delivery is skipped. T-8 configure-dependent tests select requests
  by path. SPEC: plugins turn routing off before configure. Example (debug): `…COVER`/`…LOGOUT` broadcasts, a second
  embedded chat. 422 tests, 5× `--rerun-tasks` green; live T-3..T-6 StrictMode-clean. Open: Flutter/RN configure too
  late for the sheet restore. Next, Phase E: full verification matrix (SPEC.md Verification), `tool/release_check.sh`,
  README, CONTRIBUTING, CHANGELOG, Maven Central dry run; the Flutter and React Native plugins call the routing hook
  before configure.
- 2026-11-04: Fixes 7 done (see verification/fixes-7.md), one commit each. S7-1 internal
  `Reception.setNotificationRoutingEnabled` (plugins' `handlesNotifications: false`: pending tap discarded, drain
  cancelled, taps only bring the app forward; plugins must call it before `Reception.configure`). S7-2
  `handlePushMessage` ignores a repeated `google.message_id` (last 64, `sh.reception.push`, per App ID namespace or
  global before configure, checked on the storage thread). S7-3 `ReceptionColor.argb(light, dark = light)`. S7-4 the
  payload test's flake was configure's parallel `GET /v1/automations` arriving first; requests are now found by path
  (5× `--rerun-tasks` green, 402 tests). S7-5 AGENTS.md records `Reception.kt` (441 lines) as the one length
  exception. Example: debug-only `VerificationReceiver` (push data, routing flag via `am broadcast`). Live: S7-1 and
  S7-2 StrictMode-clean. Next, Phase E: full verification matrix (SPEC.md Verification), `tool/release_check.sh`,
  README, CONTRIBUTING, CHANGELOG, Maven Central dry run; the Flutter and React Native plugins call the new hook.
- 2026-11-04: Fixes 6 done (see verification/fixes-6.md), one commit each (P-1/P-2 together). Paid access: a failed
  read makes it unknown, only the latest read publishes. Push: `reception_replies_unbadged` channel while the host
  keeps the badge, tag `reception:<conversation>` + id 1 + per-conversation intent data, channel named in the host's
  language before configure. Card: a dismissed dialog is not stored; `pushActive` is Compose state. API:
  `add/removeUnreadCountListener`, `ReceptionColor` (color, light/dark pair, color resource) for every appearance
  color, `WelcomeIcon.resource`, `Appearance.closeImageResource`. A covered sheet comes back (reorder in its task, or
  its task moved to the front); the lightbox survives rotation; the trampoline brings back an existing app task instead
  of starting the launcher (Flutter's `taskAffinity=""`, now in the example). Live: all except paid access and P-7.
  Open: `Reception.kt` 432 lines, Java has no `ReceptionColor(Color)`. Next, Phase E: full verification matrix
  (SPEC.md Verification), `tool/release_check.sh`, README, CONTRIBUTING, CHANGELOG, Maven Central dry run.
- 2026-11-04: Fixes 5 done (see verification/fixes-5.md), one commit each. K-1 `ui/ChatLifecycle` (process stop
  deactivates unconditionally), K-2 `settled()` gate before every token hand-out, K-3 `onPaywall` Compose state, K-4
  `LinkPolicy.normalizedScheme` in host queries and every launch, K-5 text links share the card route (no event or
  receipt), K-6 lightbox gestures start past the touch slop (double tap 7/7 live). N-1 notification taps go to
  `ui/ReceptionNotificationActivity` (records the tap, starts the launcher only as a new task's root, finishes; not
  `excludeFromRecents`, which hid the app's task after a cold tap); launcher extras and `onNewIntent` listening are
  gone; live: second activity on top, background, process killed with and without task. N-2 internal
  `Reception.noteCurrentActivity` (`@ReceptionInternalApi`, plugin-only). N-3 SPEC note. N-4: the "no purchases →
  subscribed false" path needs a Google account on the device; it stays covered by `PaidAccessTest` only. Splits:
  `SubscriptionEvents.kt` out of `Automations.kt` (165), `Reception.kt` 397 (delegations and nested public types).
  Example: Second screen. Open: a sheet buried under another app activity blocks `openChat()` and taps.
- 2026-11-04: Phase D done (see verification/phase-d.md). Push: `ReceptionPush` (receipt, taps, registration),
  `client/PushNotifications` (channel `reception_replies`, tag `reception`, id from the conversation, launcher intent with
  `FLAG_ACTIVITY_SINGLE_TOP` and the two extras), `client/NotificationRouting` (port, 64 keys, pending tap drained 500 ms
  after configure), `client/FirebaseBridge` (only Firebase touch), public `ReceptionMessagingService` (library manifest,
  no intent filter; the snippet is in SPEC.md), `handlePushMessage(context, data)` (deviation: a `Context`, because FCM
  can start the process before anything configured the SDK), `handlePushNotification`, `registerForPushIfAuthorized`
  (configure, foreground, after logout and deletion; skips an unchanged token), `notificationSmallIcon`. Taps: created
  activities, `ComponentActivity.addOnNewIntentListener`, `activity.intent` on resume. Badge: `ReceptionUnread.set`
  cancels the SDK's active notifications at zero (not at configure). Card: `client/NotificationPermission`,
  `viewmodel/PushPromptDecision` + `ChatPushPrompt`, `ui/PushPromptSlot`, `ui/NotificationPermissionRequest` (the
  hosting activity's result registry; none → no card). Automations: `client/Automations` (+ `AutomationPresenter`),
  `ReceptionAutomations`, reveal in `ChatLoading`, `subscriptionChanged` from `setMetadata`. Paid access:
  `client/PaidAccess`, `StoreSubscriptions` (active/cancelled/expired), `PlayBillingBridge` (billing 5–8). Setup check:
  `client/SetupCheck`, `Log.unconditional`. Example: Register for push, Track event, Delete data, Switch user (token;
  HS256 with `.local/identity-secret.txt`, which this phase created for the local test app), Update app badge switch,
  the SDK's messaging service enabled through a manifest placeholder (off in the bare build). Live: all FCM scenarios,
  card (Continue/Allow, No thanks), automation with the reveal, setup check, bare build. Not live: Play purchases (no
  Google account on the emulator), ChatPreview (not ported). Files over the guideline: `Reception.kt` 413 (nested public
  types plus the push/track API), `Automations.kt` 224 (one iOS file). Open: hosts that configure after their activity
  resumed (Flutter, React Native) have no current activity and miss taps read before configure, so their plugins need an
  internal hook to hand the activity over; a tap while another app activity is above the launcher activity only brings
  the app forward. Next, Phase E: full verification matrix (SPEC.md Verification), `tool/release_check.sh`, README,
  CONTRIBUTING, CHANGELOG, Maven Central dry run (`publishToMavenLocal` plus the signed bundle check).
- 2026-11-04: Fixes 4 done (see verification/fixes-4.md), one commit each. G-1: `token()`/`renewedToken()` return a
  token only through `settledToken` (no registration in flight, pending credentials saved; a failed save fails every
  waiting caller with `secure_storage_unavailable`). G-2: `ChatLifecycle` also observes `ProcessLifecycleOwner`; its
  stop (Home, lock) ends listening and visibility even under the photo picker, which only pauses the chat's activity;
  the chat's own start/resume show and load it again. G-3: `ComposerModel.picker` (`PhotoPickerOwnership`), bound to
  the launcher, released on disposal, handed over on recreation. G-4: C2's team fix holds (production-path test).
  H-1: `Theme.symbolScale` 1.15 on built-in glyphs (cards, welcome, close, composer photo); side-by-side in
  `verification/fixes-4/`. H-2: the flaky activation test's server contradicted itself (unread said "no conversation"
  after the load); fixed in the test, stream assertion restored. H-3: nothing to move (paywall members already
  delegate; `Reception.kt` stays 354 lines because of the nested public appearance types). Docs phase: the Android
  guide must say that a host presenting its paywall from `onPaywall` as a dialog or activity calls
  `Reception.closeChat()` first when it wants the paywall above the SDK's sheet (SPEC.md "Paywall cards, screens,
  links"); iOS keeps the chat open underneath, Android cannot.
- 2026-11-04: Phase C2 done (see verification/phase-c2.md). C1 audit C1-1..C1-5, one commit and failing-first test
  each: `ChatActivation.kt` (activation job + generation, cancelled on deactivation), presentation numbers in the
  launch intent (`ChatPresenter.EXTRA_PRESENTATION`; a cancelled or superseded sheet finishes in `onCreate`),
  `ComposerModel.isEdited` (typed text beats the stored draft and is stored), no authoritative team update before a
  conversation response, `ComposerModel.add` (picked URIs queued and prepared in the model scope). Public API added:
  `paywalls`, `paywallAvailability`, `availablePaywalls`, `refreshPaywalls`, `onPaywall`, `screens`,
  `opensWebLinksInApp`, `ReceptionPaywall` (`Source`, `Product`), `ReceptionScreen` (`ReceptionPaywalls.kt` holds the
  state and the availability generations; checks at configure, foreground, chat appearance, logout, deletion). Links:
  `viewmodel/ChatLinks.kt` + `LinkHandling` (`LinkRoute` screen → host → web → system, receipts with outcomes),
  `ChatPresenter.dismiss(then)` continues from the sheet's `onDestroy`, `ui/LinkOpener` (claiming app, Custom Tab in
  the accent, Play Store app for listings). Views: cards, `ImageLightbox` (full screen, 1–5× pinch, 3× double tap,
  drag to close), `RemoteAttachmentImage`, `TypingIndicator`, `JumpToLatestButton`, `TeamAvatar`, reply haptic. The
  black strip above the light sheet was the missing `windowDrawsSystemBarBackgrounds`. Example: two paywalls, two
  screens, dialog on `onPaywall` (closes the chat first), German override switch, chat appearance button.
  Side-by-side: with the iOS `Examples/Basic` app (the Dart VM hangs on this Mac, so the Flutter example could not
  run); no iOS dark or keyboard shot. Files over the guideline: `Reception.kt` 354 (paywall API), `ChatModel.kt` ~200.
  Open: the card icons' optical size vs SF Symbols, a lightbox double tap not verified live, one unexplained missing
  stream request in a unit run, the Android guide must say that `onPaywall` hosts close the chat before presenting.
  Next, Phase D: push and notifications (FCM handling, channel, badge, taps opening the chat), the notification card
  (`ChatPushPrompt`, `PushPromptDecision`, POST_NOTIFICATIONS through the hosting activity), automations (`track`, the
  reveal in `ChatLoading.load`), paid access (Play Billing), the setup check, AppBadge in `ReceptionUnread.set`.
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
