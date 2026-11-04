# Reception Android SDK — specification

The Android SDK mirrors the iOS SDK (`../reception-ios`, specified in `../reception/docs/SDK_SPEC.md`) one to one:
the same features, the same public API shape translated to Kotlin, the same wire contract, the same behaviour. Where
Android differs by platform (push transport, presentation, storage, store APIs), this document names the Android
answer and why. Everything not mentioned here follows the iOS SDK and `SDK_SPEC.md`; when in doubt, read the iOS
source file with the same name and port its behaviour, not its structure.

Written 2026-11-04 for SDK 1.0.0, which bundles the feature set of iOS SDK 1.7.0.

## Stack (fixed)

| Item | Value | Why |
| --- | --- | --- |
| Language | Kotlin 2.0.21, `explicitApi()` | Readable by hosts on Kotlin 2.0 (Expo modules default), 2.1 (React Native 0.86) and 2.4 (Flutter 3.47). Never raise it above the oldest host toolchain minus nothing. |
| UI | Jetpack Compose, Compose BOM 2024.12.01, Material 3 | Google's current UI toolkit; a year old so hosts are never forced to upgrade. |
| Build | AGP 8.7.3, Gradle 8.9, JDK 17 | Consumed as an AAR by AGP 8.x and 9.x hosts. |
| minSdk / compileSdk / targetSdk | 24 / 36 / 36 | Flutter and Expo default to minSdk 24; a higher library minimum breaks the host build. |
| Coordinates | `sh.reception:reception:<version>` on Maven Central, Kotlin package `sh.reception` | Same namespace as the iOS bundle and the service. |
| Dependencies | kotlinx-coroutines, kotlinx-serialization-json, OkHttp 4.12 + okhttp-sse, Coil 2.7 (compose), androidx core, activity-compose, lifecycle-process, exifinterface, browser (Custom Tabs). `compileOnly`: firebase-messaging, play billing. | Everything a Compose host already has, plus the two de-facto standards for HTTP and images. No Firebase or Billing dependency is forced on the host; both are used only when the host already ships them (see Push, Paid access). |
| Native code | none | No 16 KB page-size or ABI concerns. |
| R8 | `consumer-rules.pro` shipped in the AAR | Hosts need no ProGuard edits. |

Hosts need no manifest edits except what their own push setup already requires. The SDK's manifest declares the
chat activity, the Custom Tabs query, the optional messaging service (disabled until the host enables it) and the
`POST_NOTIFICATIONS` permission (so the notification card can ask; the permission is only requested from the card).

## Repository layout

```
reception/                         library module (the SDK), published artifact
  src/main/kotlin/sh/reception/
    Reception.kt                   facade (object): configure, presentation, push, logout, deleteData, state
    ReceptionIdentity.kt           identify, identity tokens, metadata
    ReceptionAppearance.kt         Appearance, ReceptionEvent, CloseIcon, WelcomeIcon
    ReceptionError.kt, ReceptionPaywall.kt, ReceptionScreen.kt, ReceptionPush.kt
    Localization.kt, Log.kt, Version.kt
    Theme.kt                       internal tokens (colors, spacing, radius, animations)
    client/                        ReceptionAPI, DeviceSession, DeviceStore, SecureStore, uploads, cooldowns, retries,
                                   automations, paid access, logout revocations, setup check
    configuration/                 remote appearance, cache, icons, host appearance report
    model/                         Message, Conversation, Attachment, MessageStatus
    viewmodel/                     ChatModel and its delivery, recovery, connection, stream, read, cooldown parts
    ui/                            ReceptionChatView (composable), ReceptionChatActivity (sheet), bubbles, composer,
                                   cards, banners, lightbox, presenter
  src/main/res/                    strings (48 languages, generated from the iOS String Catalog), vector icons
  src/test/                        focused unit tests (JUnit 4, MockWebServer, Robolectric only where Android
                                   classes are unavoidable)
example/                           verification app against a local backend (Compose), with Firebase and Play Billing
tool/                              release_check.sh, sync_strings.py (xcstrings → values-*/strings.xml), publish helpers
verification/                      evidence of the last verification round
SPEC.md, AGENTS.md, STATUS.md, CHANGELOG.md, README.md, CONTRIBUTING.md, LICENSE (MIT)
```

One file, one responsibility, nothing over ~200 lines; see AGENTS.md.

## Public API

Everything lives on the `Reception` object (Kotlin `object`, Java-visible as static members through `@JvmStatic`).
Every call is main-thread only, like `@MainActor` on iOS; calls from other threads post to the main looper and log
nothing. The API surface below is complete; nothing else is `public`.

### Configure

```kotlin
Reception.configure(context: Context, appId: String)
Reception.isConfigured: Boolean
Reception.version: String
Reception.logLevel: Reception.LogLevel          // OFF, ERROR, INFO, DEBUG; default INFO
Reception.logHandler: ((LogLevel, String) -> Unit)?
```

`configure` takes any `Context` and keeps only `applicationContext`. It behaves like iOS `configure(appId:)`: the
App ID is validated (`^app_[A-Za-z0-9]{22}$`; `ids_` secrets are refused with the same error text), the same App ID
is a no-op, another scope forgets the staged identity token, a restored chat whose session is missing from secure
storage is reset like logout, device registration starts while the app is in the foreground and on every
foreground until it succeeds, remote appearance refreshes, queued logout revocations resume, paywalls are checked,
subscriptions are read. Lifecycle comes from `ProcessLifecycleOwner` (foreground/background) and
`Application.ActivityLifecycleCallbacks` (current activity for presentation and notification taps), both registered
at the first configure.

Debug builds of Reception's own test apps use `Reception.configureForTesting(context, appId, serviceUrl: String)`,
which exists in the library but only accepts a call when the host application is debuggable
(`ApplicationInfo.FLAG_DEBUGGABLE`); otherwise it logs an error and does nothing. Release hosts therefore cannot
redirect the SDK. It is marked `@ReceptionInternalApi` (an opt-in annotation) so it never autocompletes by accident.

### Presentation and observable state

```kotlin
Reception.openChat()
Reception.closeChat()
Reception.isChatOpen: StateFlow<Boolean>
Reception.unreadCount: StateFlow<Int>
@Composable fun ReceptionChatView(modifier: Modifier = Modifier)
class ReceptionChatView(context, attrs?) : FrameLayout   // the same screen as an Android View, for XML hosts and the
                                                        // Flutter/React Native platform views
```

`openChat()` presents the chat as a bottom sheet over the current activity: `ReceptionChatActivity`, a translucent
activity (theme without window background, `windowSoftInputMode=adjustResize`, edge to edge) hosting a Material 3
`ModalBottomSheet` with a drag handle, full height, that dismisses by drag, scrim tap and back (predictive back on
Android 14+). It is an activity, not a dialog fragment, so it works over any host activity, including Flutter's
`FlutterActivity` and React Native's `ReactActivity`, survives configuration changes and keeps the keyboard inset
logic in one place. The presenter (`ChatPresenter`) starts it from the current resumed activity; when no activity
is resumed (app in background) the request is dropped with a debug log, like iOS when no window exists. A second
`openChat()` while the sheet is up is a no-op. The sheet reports `isChatOpen` from its actual visibility
(`onStart`/`onStop`), not from the request.

While the keyboard is open, the sheet closes only by a drag that starts on its header (drag handle and title bar,
`Theme.sheetHandleHeight`); a drag over the messages dismisses the keyboard instead. With the keyboard closed, a drag
anywhere closes the sheet. This matches the iOS sheet rule exactly.

`closeChat()` finishes the SDK's activity; a host-embedded `ReceptionChatView` is the host's to remove. Logout,
reconfiguration and successful `deleteData()` finish the SDK's activity.

`ReceptionChatView()` (composable) is the chat screen without the sheet, for host navigation. The View wrapper
embeds the composable in a `ComposeView`; it needs a host that sets a `ViewTreeLifecycleOwner` (every activity and
Flutter/React Native platform view does). Neither calls `openChat()`.

`unreadCount` and `isChatOpen` are `StateFlow`s: Compose hosts `collectAsState()`, View hosts collect on
`lifecycleScope`, Java hosts call `Reception.addUnreadCountListener(listener)` / `removeUnreadCountListener`, which
deliver on the main thread. Show a badge only above zero.

### Identity and metadata

```kotlin
Reception.identify(userId: String?, name: String? = null, email: String? = null)
Reception.identify(token: String)
Reception.setMetadata(metadata: Map<String, String>)
Reception.logout()
suspend fun Reception.deleteData()                    // throws ReceptionError
Reception.deleteData(callback: (ReceptionError?) -> Unit)   // Java-friendly overload
```

Semantics, limits, user-switch path, `409 identity_mismatch`, rejection events, `logout()` revocation queue and
`deleteData()` outcomes are the iOS ones (`SDK_SPEC.md` Identity, Logout, Delete data). `ReceptionError(code,
status)` is a Kotlin `Exception` with the two public read-only fields; status 0 means no HTTP response.
`secure_storage_unavailable` is thrown when the Keystore-backed store cannot be read.

### Language

`Reception.languageOverride: String?` (BCP 47), null follows the app's locale (`AppCompatDelegate`'s per-app locale
when set, otherwise the first system locale). Changes update built-in strings, formatting, layout direction and
cached remote translations immediately; the matching rules and the 48 bundled languages are the iOS ones. The strings
are generated from the iOS String Catalog by `tool/sync_strings.py` into `values-<tag>/strings.xml` (Android
qualifiers: `fr-rCA`, `b+es+419`, `pt-rBR`, `pt-rPT`, `b+zh+Hans`, `b+zh+Hant`, `b+zh+Hant+HK`, `nb`, `he` → `iw`
and `b+he`, `id` → `in` and `b+id`); the SDK resolves the language itself (`ReceptionLocalization`) and reads
strings through a locale-configured `Resources`, so the host app's own locale configuration never changes the chat
language rules.

### Push notifications

```kotlin
Reception.setPushToken(token: String)                                   // FCM registration token
Reception.handlePushMessage(data: Map<String, String>): Boolean         // from FirebaseMessagingService.onMessageReceived
Reception.handlePushNotification(data: Map<String, String>, openChat: Boolean = true): Boolean
Reception.registerForPushIfAuthorized()
Reception.updatesAppBadge: Boolean                                      // default true
Reception.notificationSmallIcon: Int?                                   // @DrawableRes, default: built-in glyph
class ReceptionMessagingService : FirebaseMessagingService               // optional, for hosts without their own
```

Android has no APNs environment; the device update sends `pushToken` (the FCM token, 1–4096 characters of
`[A-Za-z0-9_:-]`) and `pushEnvironment: "PRODUCTION"`. Logout clears the staged token; the host (or
`registerForPushIfAuthorized`) supplies it again.

The service sends **data-only, high-priority** FCM messages (see Backend) so the SDK decides presentation, exactly
like the iOS `willPresent` policy. The payload is `{ reception: "<conversationId>", title, body, badge?, sound?,
collapse: conversationId }` as data fields (`reception` is `brand.pushPayloadKey`). The host's
`FirebaseMessagingService.onMessageReceived` calls `Reception.handlePushMessage(remoteMessage.data)`: for a
Reception payload it sets `hasConversation`, refreshes unread and, unless the chat is open, posts a notification
(channel `reception_replies`, name localized "Support replies", importance high, sound when `sound` is set, the
badge number when `updatesAppBadge`, `setGroup(conversationId)`, tag `reception`, id from the conversation) whose
content intent launches the host's launcher activity (`PackageManager.getLaunchIntentForPackage`) with the extras
`sh.reception.conversationId` and `sh.reception.notificationId`; it returns true and the host does nothing else. Any
other payload returns false and the host keeps its own handling. `ReceptionMessagingService` is a ready-made service
that does only this, for hosts without a messaging service of their own; the host enables it in its manifest.

Taps need no host code: `Reception`'s activity-lifecycle callback reads those extras from every created or
re-delivered (`onNewIntent` is covered through `onActivityResumed` reading `activity.intent`) activity, deduplicates
by notification id (last 64 keys, like the Flutter plugin's `NotificationRouting`), and routes them through
`handlePushNotification(data, openChat = true)`. `handlePushNotification` is the iOS routing call: it recognizes
`reception`, sets `hasConversation`, opens the chat when asked, refreshes unread, returns true; it never posts a
notification. Hosts that own their tap handling call it themselves.

`registerForPushIfAuthorized()` mirrors iOS: it registers only when notifications are allowed now
(`NotificationManagerCompat.areNotificationsEnabled()`, which also covers `POST_NOTIFICATIONS` on Android 13+) and
Firebase Messaging is on the classpath and initialized; it then fetches the FCM token and stages it. Never requests
permission. Without Firebase it logs once at debug level and does nothing. The SDK calls it at configure and on every
foreground, like the Flutter plugin does on iOS, so hosts that already have Firebase need no token code at all.

Badge: Android launchers show counts from `NotificationCompat.setNumber` and the channel's badge setting. With
`updatesAppBadge` true the posted notification carries the unread count and reaching zero cancels the SDK's
notifications (`NotificationManager.cancel(tag, id)` for the stored ids); with false it does neither, and the server
omits the badge field. There is no app-icon badge API beyond that on Android; the in-app `unreadCount` is unaffected.

Notification card (`pushPrompt`): same rules as iOS. "Not determined" maps to: notifications enabled is false on
Android 13+ and `POST_NOTIFICATIONS` has never been answered (`shouldShowRequestPermissionRationale` false and no
stored answer), or below 13: never (the card does not show, because the only way to enable is the system settings).
Continue requests `POST_NOTIFICATIONS` through the chat activity's `ActivityResultLauncher` (an embedded
`ReceptionChatView` uses the host activity when it is a `ComponentActivity`, otherwise the card does not show), then
runs `registerForPushIfAuthorized()`. No thanks stores the per-device flag under the App ID in SharedPreferences.
The status line "You'll get a notification when we reply." follows the iOS rule with `areNotificationsEnabled()`.

### Paywall cards, screens, links

```kotlin
Reception.paywalls: List<ReceptionPaywall>
Reception.paywallAvailability: (suspend (String) -> Boolean)?
Reception.availablePaywalls: List<ReceptionPaywall>
Reception.refreshPaywalls(invalidate: Boolean = false)
Reception.onPaywall: ((String) -> Unit)?
Reception.screens: List<ReceptionScreen>
Reception.opensWebLinksInApp: Boolean                                   // default true
data class ReceptionPaywall(id, title, source: Source? = null, products: List<Product> = emptyList(), presentable: Boolean = true)
class ReceptionScreen(id: String, open: () -> Unit) / ReceptionScreen(id: String, value: String, open: (String) -> Unit)
```

Validation limits, availability generations, info-only entries, receipts and events are the iOS ones. Link taps:

1. `reception-screen:<id>[?value=…]` closes the SDK's activity (an embedded view stays) and runs the screen on the
   main thread; outcome `handled`/`failed` as on iOS.
2. A URL the host app itself handles (`PackageManager.queryIntentActivities(ACTION_VIEW uri)` contains an activity
   of the host package): close the SDK's activity, then `startActivity` with that URI; outcome `opened`/`failed`.
3. Otherwise the chat stays open. A web link (`http`/`https`, host not `play.google.com`) with `opensWebLinksInApp`
   true first tries an installed app that claims it (`Intent.ACTION_VIEW` with `FLAG_ACTIVITY_REQUIRE_NON_BROWSER`
   on API 30+, below that only when a non-browser handler is the default) and otherwise opens a Chrome Custom Tab
   (toolbar in the accent color) over the chat; with the setting false, and for Play Store, `mailto`, `tel`, `sms`
   and other schemes, the URL goes to the system with `ACTION_VIEW` (Play Store links prefer the `market://` form
   when the Play Store app is installed). `ActivityNotFoundException` reports `failed`.

The review card opens the Play Store listing of the host (`market://details?id=<package>`, falling back to
`https://play.google.com/store/apps/details?id=<package>`); the server builds the URL from the project's Android
package name like it builds the App Store URL from the App Store ID. Default button texts, event and receipt rules are
the iOS ones. Scheme allowlist for link cards is the iOS one.

### Events, appearance

```kotlin
Reception.onEvent: ((ReceptionEvent) -> Unit)?
sealed class ReceptionEvent { ChatOpened, MessageSent, ImageSent, ReviewOpened, PaywallOpened, LinkOpened,
                              DataDeleted, data class IdentityRejected(error: ReceptionError) }
Reception.appearance: Reception.Appearance        // a mutable class with the iOS properties
Reception.usesRemoteAppearance: Boolean           // default true
```

`Appearance` has exactly the iOS properties with Compose types: `accentColor: Color?` (null = the host's Material
`primary` when the chat is embedded in a host theme, otherwise the app's `colorPrimary`/`colorAccent` attribute,
resolved at render time), `onAccentColor: Color?`, `title`, `welcomeTitle`, `welcomeText`, `showsCloseButton`,
`chatBackground`, `incomingBackground`, `incomingForeground`, `preferredColorScheme: ColorScheme?` (`LIGHT`, `DARK`),
`closeIcon: CloseIcon` (`XMARK`, `CHEVRON_DOWN`, `ARROW_DOWN`), `closeImage: Painter?`, `fontDesign: FontDesign?`
(`DEFAULT`, `ROUNDED`, `SERIF`, `MONOSPACED`; Android has no rounded system face, so `ROUNDED` draws the default
face and is still reported as `rounded`), `fontFamily: FontFamily?` (a Compose font family the host built from its
bundled fonts; reported to the dashboard as "custom"), `fadesOlderMessages`, `welcomeIcon: WelcomeIcon?`
(`symbol(name)`, `image(Painter)`, `hidden`), `welcomeIconColor`, `welcomeIconSize: Dp?`, `welcomeIconCornerRadius:
Dp`, `welcomeIconOffset: DpOffset`, `hidesTitle`, `hidesWelcomeTitle`, `hidesWelcomeText`, `hidesPushStatus`.

Symbols: the dashboard and the host name SF Symbols. The SDK ships one Material Symbols vector drawable per name the
dashboard offers (`appearance-symbols.ts`: welcome, card and accessory symbols) plus the built-in icons (`xmark`,
`chevron.down`, `arrow.down`, `bell.fill`, photo, send, failure, link) and maps names to them in `AppearanceIcon`;
an unknown symbol name draws the built-in icon for that slot, as iOS does for a symbol the device cannot draw.
Remote appearance resolution, cache, activation, visibility and `hidden` rules, card styles, team photos/names,
adaptive color pairs and the host appearance report follow `SDK_SPEC.md` and `REMOTE_APPEARANCE.md` exactly.

### Automations and paid access

`Reception.track(event: String, opensChat: Boolean = false)` is the iOS call with the same rules and caching.

Paid access: iOS reads StoreKit. Android reads Play Billing when `com.android.billingclient:billing` (5+) is on the
host's classpath: at configure and every foreground, `BillingClient.queryPurchasesAsync` for `SUBS` and `INAPP`; any
purchase in state `PURCHASED` means paid access (`subscribed`). The billing connection is short-lived and every error
means "unknown" (field omitted), never false. `transactionIds` is omitted on Android (there is no App Store Server
Notification equivalent in this release; RevenueCat, Superwall and Adapty webhooks identify the person through their
own ids). Of the five store events, Android reports `subscription_cancelled` (an active subscription's
`isAutoRenewing` turned false) and `subscription_expired` (a subscription purchase disappeared from the query); trial
and billing-issue states are not visible through Play Billing on the device and are documented as not reported
(hosts with RevenueCat/Adapty/Superwall get them through those webhooks). Without the billing library nothing is read
and nothing is sent, with one debug log.

### Logging

`android.util.Log` with tag `Reception` and the `[Reception] LEVEL message` lines from the iOS catalog; the privacy
rules in `Log.kt`'s header comment are the iOS ones. `logHandler` replaces the console.

## Behaviour ported without change

Activation, unread polling schedule, SSE stream (okhttp-sse; `data: reconnect`, 20 s pings), read generations,
bounded read, server cooldowns (per service address, App ID and local session, in SharedPreferences), retry policy
(8 attempts per foreground period, backoff with jitter), halts, `409 chat_reset`, verification-required state,
blocked state, message limits (4,000 UTF-16 units, 5 photos, JPEG 2,048 px / 1 MiB, EXIF orientation applied before
scaling), upload reservation and checksum rules, cache of the last 50 confirmed messages plus pending/failed (in
`noBackupFilesDir`, so backups never carry it), drafts, retry budgets and recovery, team profiles and photos
(Coil with an in-memory-only cache keyed by photo id), lightbox with zoom, typing indicator timing, automation
typing delay, message grouping and timestamps, fading of older outgoing bubbles, Dynamic Type (font scale), dark
mode, RTL, the welcome state and every string.

Session credentials: `SecureStore` keeps `{ sessionSecret, verifiedUserId }` encrypted with an AES-GCM key in the
Android Keystore (`KeyGenParameterSpec`, no user authentication, not backed up), stored in SharedPreferences. A
Keystore failure is `secure_storage_unavailable`, never "no session"; an entry that cannot be decrypted after a
backup restore counts as missing, which is the iOS "restored chat without its Keychain item" path. The access token
stays in memory.

Device info: `platform: "android"`, `osVersion: Build.VERSION.RELEASE`, `deviceModel: Build.MANUFACTURER + " " +
Build.MODEL` (emulator reports `Google sdk_gphone64_arm64`, which the service recognizes as "Emulator"),
`appVersion`/`appBuild` from `PackageInfo`, `bundleId: packageName`, `locale`, `timezone`, `language`, `sdkVersion`,
`capabilities: ["linkCards", "screens"]`, everything else as iOS.

## Backend contract additions (implemented in `../reception`)

- `platform` accepts `"android"` beside `"ios"`. Android devices use `pushEnvironment: "PRODUCTION"` and FCM tokens
  (`^[A-Za-z0-9_:-]{1,4096}$`); iOS keeps the hex rule. The dashboard shows the platform.
- Project gets `fcmServiceAccountEncrypted` (the service-account JSON the customer downloads from the Firebase
  console, encrypted like the APNs key), `fcmProjectId` and `fcmClientEmail` (read from the JSON for display) and
  `androidPackageName`. The push settings page offers "Android (Firebase)" beside "iOS (APNs)", with a guide that
  mirrors `PushKeyGuide`.
- `sendPush` dispatches by `device.platform`: APNs as today, or `fcm.ts`: a service-account JWT (RS256,
  `https://www.googleapis.com/auth/firebase.messaging`) exchanged for an access token cached ~50 minutes per project
  and configuration, then `POST https://fcm.googleapis.com/v1/projects/<id>/messages:send` with
  `{ message: { token, data: { reception: conversationId, title, body, badge?, sound? }, android: { priority: "high",
  collapse_key: conversationId } } }`. `404 UNREGISTERED` and `400 INVALID_ARGUMENT` on the token clear the token like
  APNs 410; `403 SENDER_ID_MISMATCH` and `401` record a rejection without clearing. `pushEligible` checks the
  platform's credentials.
- Review links, paywalls, link cards and screens are allowed for `android` devices; the review URL comes from
  `androidPackageName`. `link-screens`, AI chat actions and the Telegram layout treat both platforms alike.
- Docs: `content/docs/android.md` (mirror of `native-ios.md`), Android sections in `push-notifications.md`,
  `flutter.md`, `react-native.md`, `get-started.md`, `sdk-reference.md`; setup prompt and verification prompt gain
  Android; `SDK_SPEC.md` gets an Android section that points here; `SPEC.md` lists the Android SDK.

## Verification

Every feature is verified on the Google Play emulator (`reception_pixel8`, Android 16) against the local backend
with the example app, as the Flutter plugin was on the iOS simulator: registration and identity, verified identity
and rejection, send/sent/retry, photos, cooldowns, automations, paywall and link cards, screens, remote appearance,
language switch, dark mode, font scale, rotation, keyboard over the sheet and over an embedded chat, background and
foreground, logout and deletion, every push scenario with real FCM (foreground, background, killed, cold-start tap,
chat open, badge), Firebase and Billing present and absent, R8 release build of the example. Commands and observed
output go to `verification/`. Four independent audits (Codex) before release, findings fixed. `tool/release_check.sh`
must pass on a clean tree.
