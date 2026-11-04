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
| Build | AGP 8.9.3, Gradle 8.11.1, JDK 17 | The oldest AGP that supports compileSdk 36 without a suppression flag. Consumed as an AAR by AGP 8.x and 9.x hosts. |
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
    ReceptionColor.kt              adaptive appearance colors (one color, light/dark pair, color resource)
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
  src/main/res/                    vector icons (Material Symbols), the sheet's theme
  src/test/                        focused unit tests (JUnit 4, MockWebServer, Robolectric only where Android
                                   classes are unavoidable)
  api/reception.api                the public API dump (binary-compatibility-validator; apiCheck, apiDump)
example/                           verification app against a local backend (Compose), with Firebase and Play Billing
tool/                              release_check.sh, sync_strings.py (xcstrings → sh.reception.strings Kotlin objects)
verification/                      evidence of the last verification round
SPEC.md, AGENTS.md, STATUS.md, CHANGELOG.md, README.md, CONTRIBUTING.md, LICENSE (MIT), THIRD_PARTY_NOTICES.md
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
Reception.addUnreadCountListener(listener: (Int) -> Unit)       // Java hosts; current value at once, main thread
Reception.removeUnreadCountListener(listener: (Int) -> Unit)
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
`openChat()` while the sheet is up is a no-op. When another of the app's activities covers the sheet (it exists but is
stopped), `openChat()` and a notification tap bring that sheet back instead of starting a second one: with
`FLAG_ACTIVITY_REORDER_TO_FRONT` from an activity in the sheet's task, or by moving the sheet's task to the front
(`ActivityManager.AppTask.moveToFront`, then the activity that resumes first in that task, the one covering the sheet,
starts it with `FLAG_ACTIVITY_REORDER_TO_FRONT`; `AppTask.startActivity` ignores that flag and stacks a second sheet)
when the covering activity runs in another task of the app. Resumes in other tasks before that (or a plugin's
`noteCurrentActivity`) keep the request; it ends when the sheet's task resumes, or when the chat is dismissed or reset. The sheet reports `isChatOpen` from its actual visibility
(`onStart`/`onStop`), not from the request.

Each presentation gets a random token that its launch intent carries; a sheet whose token is not the current one (closed,
cancelled by logout, `deleteData()` or reconfiguration, or superseded before it was created) finishes at once. The token
and the configured storage namespace are also committed to SharedPreferences `sh.reception.presentation` on the storage
thread before the sheet starts (the presentation counts as requested meanwhile; after the commit the sheet starts only if
that presentation is still current, its activity is still the resumed one and neither finishing nor destroyed, and the request is still wanted (see the routing and automation rules); otherwise the presentation is cancelled; a failed commit is logged and the sheet
starts without restoration), so when the process dies with the sheet on screen and Android restores it (Recents), the sheet reads the record
and is shown again, with its saved state (an open lightbox included), if the record still names its token and the App
ID configured in the new process; any dismissal, logout, deletion or reconfiguration clears the record first. The first
`configure` in a process cancels nothing. A restored sheet created before the SDK is configured (Flutter and React
Native configure from Dart/JS after Android recreated it) waits up to 3 s for `configure`, drawing nothing, and finishes
when none comes; the same checks then apply, so a logout, reconfiguration or newer presentation still finishes it.
The chat's content (sheet or embedded) is keyed by the session's persisted chat identity (storage namespace with its
generation, plus the chat revision), not an in-memory counter, so its saved state, including the keys of the photo
picker and permission launchers, finds its place again in a process Android restarted for their result; it draws only
its background until the session's store is loaded.

While the keyboard is open, the sheet closes only by a drag that starts on its header (drag handle and title bar,
`Theme.sheetHandleHeight`); a drag over the messages dismisses the keyboard instead. With the keyboard closed, a drag
anywhere closes the sheet. This matches the iOS sheet rule exactly.

`closeChat()` finishes the SDK's activity; a host-embedded `ReceptionChatView` is the host's to remove. Logout,
reconfiguration and successful `deleteData()` finish the SDK's activity.

`ReceptionChatView()` (composable) is the chat screen without the sheet, for host navigation. Because its signature exposes `Modifier`, the Compose UI, foundation and Material 3 artifacts are `api` dependencies of the library, and so is kotlinx-coroutines-android, because `unreadCount` and `isChatOpen` are `StateFlow`s. The View wrapper
embeds the composable in a `ComposeView`; it needs a host that sets a `ViewTreeLifecycleOwner` (every activity and
Flutter/React Native platform view does). The embedded chat lifts its composer with the IME insets; a host window that pans for the
keyboard (`adjustPan`, or `adjustUnspecified`, the default of an activity without `windowSoftInputMode`, as in Android
Studio's Compose template) would move it a second time, and before Android 11 a window with `adjustNothing` gets no IME
insets at all, so the composer would stay behind the keyboard; while a chat is embedded such a window is set to
`adjustResize` and gets its own mode back when the last embedded chat in it leaves (`ui/EmbeddedKeyboardMode`, counted
per window: the window is the one that shows the composition, so a chat in a Compose `Dialog` or a platform dialog
adjusts that dialog's window and never the activity's, `ui/KeyboardWindow`; a Compose `Dialog` measures its content
against the display, so it cannot shrink with a resized window: its window adjusts nothing from Android 11, where the
IME insets alone lift the composer, and pans before, where only a resize would send IME insets; only the adjust bits go back, and only while
the window still carries the SDK's `adjustResize`, so a mode or state flags the host set meanwhile stay; an
unspecified mode comes back as what Android resolves it to, resize with a shown scroll container, else pan, because
Android keeps the last adjust mode when it is asked for "unspecified" again). On a Compose `Dialog` window this
temporarily overrides any soft-input mode the host set, an explicit `adjustResize` included, while a chat is in it, and
restores it afterwards. Known limit: on Android 10 and older a chat in a Compose `Dialog` pans to the cursor line, so
the keyboard may clip the composer's bottom edge. Neither calls `openChat()`. Every chat on screen shows the one chat model:
it stays visible (`isChatOpen`) while any of them is started and keeps loading and listening while any is resumed, so
removing one of two embedded views leaves the other running; the app going to the background stops it for all.

`unreadCount` and `isChatOpen` are `StateFlow`s: Compose hosts `collectAsState()`, View hosts collect on
`lifecycleScope`, Java hosts call `Reception.addUnreadCountListener(listener)` / `removeUnreadCountListener`, which
deliver on the main thread: the current value when added, then every change (adding the same listener twice does
nothing; a listener that another one removes during a delivery is not called; a listener whose call publishes a newer count,
by a logout for example, ends the older delivery, so nobody gets the older count after the newer one). Show a badge only above zero.

### Identity and metadata

```kotlin
Reception.identify(userId: String?, name: String? = null, email: String? = null)
Reception.identifyWithToken(token: String)      // `identify("x")` would otherwise resolve to the token overload in Kotlin
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

`Reception.languageOverride: String?` (BCP 47), null follows the app's language. Changes update built-in strings,
formatting, layout direction and cached remote translations immediately; the matching rules and the 48 bundled
languages are the iOS ones.

The app's language without an override: the per-app locale when one is set (`LocaleManager` on Android 13+,
`AppCompatDelegate` below, read by name because AppCompat is optional); otherwise, on Android 13+ when the host
declares `android:localeConfig`, the first system locale that the host's declared locales match (the iOS rule: an
English-only app stays English on a German phone); otherwise the first system locale.

Strings are not Android resources: Play's App Bundle language splits would strip every language the device does not
use, and `languageOverride` would silently fall back to English. Instead `tool/sync_strings.py` generates one Kotlin
object per language from the iOS String Catalog (`sh.reception.strings.<Tag>`, a `Map<String, String>` keyed by the
stable `Strings` constants) plus `tool/android-strings.json`, a small overlay with the strings only Android needs
("Rate on Google Play", the notification channel name "Support replies") in all 48 languages. Plural forms are
selected with `android.icu.text.PluralRules` for the resolved locale; placeholders use `%1$s`/`%1$d` and
`String.format(locale, …)`. The generated code is checked by `tool/sync_strings.py --check` in the release check.
Vector icons remain resources (`reception_` prefix).

### Push notifications

```kotlin
Reception.setPushToken(token: String)                                   // FCM registration token
Reception.handlePushMessage(context: Context, data: Map<String, String>, messageId: String?): Boolean  // from onMessageReceived, any thread
Reception.handlePushMessage(context: Context, data: Map<String, String>): Boolean  // raw FCM extras with google.message_id
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
like the iOS `willPresent` policy. The payload is `{ reception: "<conversationId>", title, body, badge?, sound? }` as data fields
(`reception` is `brand.pushPayloadKey`), with `android.collapse_key` set to the conversation id. The host's
`FirebaseMessagingService.onMessageReceived` calls `Reception.handlePushMessage(this, remoteMessage.data,
remoteMessage.messageId)`: for a
Reception payload it sets `hasConversation`, refreshes unread and, unless the chat is open, posts a notification
(channel `reception_replies`, or `reception_replies_unbadged` with `setShowBadge(false)` while `updatesAppBadge` is
false, because a channel's badge setting cannot change once created; both named with the localized "Support replies",
importance high, sound when `sound` is set, the badge number when `updatesAppBadge`, `setGroup(conversationId)`, tag
`reception:<conversationId>` and the fixed id 1, because hashed ids of two conversations can collide) whose content
intent starts the SDK's `ReceptionNotificationActivity` with the data `reception://conversation/<conversationId>` (so
each conversation gets its own `PendingIntent`) and the extras `sh.reception.conversationId` and
`sh.reception.notificationId` (see taps below); it returns true and the host does nothing else. Any
other payload returns false and the host keeps its own handling. `ReceptionMessagingService` is a ready-made service
that does only this (and forwards `onNewToken` to `setPushToken`), for hosts without a messaging service of their own.
`handlePushMessage` takes a `Context` because FCM can start the process for a message before anything configured the
SDK (Flutter and React Native configure from Dart/JS, which does not run for a background message); without one the
notification could not be posted. It recognizes the payload synchronously, so it may be called from FCM's worker
thread, and does the rest on the main thread. It is idempotent per FCM message: the last 64 FCM message IDs are kept
(SharedPreferences `sh.reception.push`, one global list outside any App ID or session namespace, because FCM message
IDs are globally unique and a repeat can arrive on either side of configure; checked and committed with `commit()` on the storage thread before the notification is posted, so a crash right after posting cannot let a redelivery notify again; a failed commit is logged and the message is still handled once), and a repeat returns true without a second notification or
unread refresh. Firebase removes the reserved `google.*` keys from `RemoteMessage.data` and exposes the ID as
`RemoteMessage.messageId`, so hosts with their own service pass `remoteMessage.messageId` (the SDK's service does). The
two-argument form reads the ID from `google.message_id` in the map, for callers that hold the raw FCM extras (the
Flutter and React Native plugins' broadcast receivers). A message without an ID is always handled.
`notificationSmallIcon` is stored by its package-qualified resource entry name (`getResourceName`; null clears it) in
the same `sh.reception.push` preferences, once the SDK has a context (configure or the first push message), and a
notification posted in a process where the app has not set it yet (FCM started the process, before or without
configure) uses the stored icon, looked up with `getIdentifier` because resource IDs change between app builds; a name
that no longer resolves falls back to the built-in glyph. The Flutter and React Native plugins therefore need no
persistence of their own: setting the icon once from Dart/JS covers later processes that FCM starts. The library manifest declares the service `exported="false"` without an
intent filter, so it receives nothing until the host adds the filter:

```xml
<service
    android:name="sh.reception.ReceptionMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

A host with its own `FirebaseMessagingService` must not add it (only one service receives FCM messages). Android
delivers no FCM message to a force-stopped app (Settings → Force stop, `am force-stop`) until the person opens it again;
a process the system killed receives them and starts for them.

Taps need no host code. The content intent targets `ReceptionNotificationActivity` (library manifest:
`exported="false"`, `noHistory`, `Theme.NoDisplay`, the default task affinity so it joins the app's task; not
`excludeFromRecents`, because as the root of a cold start's task that flag stays in the task's base intent and hides
the app's whole task from Recents). Its `onCreate` records the tap and finishes; it never draws. The tap already brought an existing task
of the app forward (with its state, and recreated from saved state when the process had died), so finishing reveals
whatever activity is on top. Only as the root of a new task does it act first: when `ActivityManager.appTasks` holds another
task rooted at one of the app's activities other than the trampoline (the process died with its card kept in Recents,
and the launcher activity declares another task affinity, like Flutter's template with `taskAffinity=""`, so the tap
could not join it; such a card may have live activities or, restored from disk, no activity records at all, and
`moveToFront()` restores it either way), it moves that task to the front and finishes with `finishAndRemoveTask()`; when no such task exists it starts the host's launcher
intent (`getLaunchIntentForPackage` with `FLAG_ACTIVITY_NEW_TASK`), so the app starts cold; inside an
existing task that intent would stack another launcher activity whenever the task's root intent differs from it. Android 12+ blocks only service and receiver trampolines, not activity
ones. Routing follows the Flutter plugin's `NotificationRouting` (keys are notification ids, last 64; a receipt
records its id, so its later tap only opens): after configure, a tap that routes opens the chat over the next resumed
activity, whichever it is; a repeated tap of the same notification is ignored, another notification opens again. A
tap before configure waits as the pending tap (latest wins, every other tap meanwhile is recorded and absorbed) and
opens the chat once, 500 ms after configure, so the settings the app applies right after `configure` come first.
A recreated trampoline or one relaunched from Recents (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, when the task it
rooted has no activities left) routes nothing and only starts the app. The host's activities and their intents are
never read. `handlePushNotification` is the iOS routing call: it recognizes `reception`, sets `hasConversation`,
opens the chat when asked (over the resumed activity, or the next one), refreshes unread, returns true; it never posts
a notification. Hosts that own their tap handling call it themselves.

Hosts that configure after their activity resumed (the Flutter and React Native plugins configure from Dart/JS) call
the internal `Reception.noteCurrentActivity(activity)` (`@ReceptionInternalApi`, plugin-only, not in the public
docs) with the activity they are attached to. It seeds the current-activity tracking (a weak reference) so
`openChat()` and the pending tap work before the next resume.

The plugins' `configure(handlesNotifications: false)` calls the internal
`Reception.setNotificationRoutingEnabled(false)` (`@ReceptionInternalApi`, plugin-only, main thread): it discards the
pending tap and cancels the post-configure drain and a tap's chat that waits for the next resume, also one whose
activity already resumed but whose open has not run yet, or whose sheet waits for its presentation record to be saved (a routing generation captured when the tap asks for the open is checked again right before the sheet starts; turning routing on again does not revive it), and `ReceptionNotificationActivity` then only brings the app forward
(restores its task or starts the launcher) without opening the chat. `true` (the default) routes taps again. The app's
own `handlePushNotification(data, openChat)` calls are unaffected, also one that waits for the next resume: a waiting
open remembers whether a tap or the host asked for it, and only a tap's is cancelled (a tap never replaces the host's). Plugins that let the host own notifications call
`setNotificationRoutingEnabled(false)` before `configure`, because the flag lives in memory and a tap that arrived
before configure would otherwise open the chat.

`onNewToken` stages the token even before notifications are allowed, as iOS stages the APNs token whenever it
arrives; the service only sends once the device reports a token, and posting checks the permission.

`registerForPushIfAuthorized()` mirrors iOS: it registers only when notifications are allowed now
(`NotificationManagerCompat.areNotificationsEnabled()`, which also covers `POST_NOTIFICATIONS` on Android 13+) and
Firebase Messaging is on the classpath and initialized; it then fetches the FCM token and stages it. Never requests
permission. Without Firebase it logs once at debug level and does nothing. The SDK calls it at configure and on every
foreground, like the Flutter plugin does on iOS, so hosts that already have Firebase need no token code at all.

Badge: Android launchers show counts from `NotificationCompat.setNumber` and the channel's badge setting. With
`updatesAppBadge` true the posted notification carries the unread count and reaching zero (a server answer, a read, a
reset; not configure) cancels the SDK's delivered notifications (`activeNotifications` whose tag starts with
`reception:`); with false it does neither, the server omits the badge field and the unbadged channel keeps launchers
from showing a dot or count. There is no app-icon badge API beyond that on Android; the in-app `unreadCount` is unaffected.

Notification card (`pushPrompt`): same rules as iOS. "Not determined" maps to: notifications enabled is false on
Android 13+ and `POST_NOTIFICATIONS` has never been answered (`shouldShowRequestPermissionRationale` false and no
stored answer), or below 13: never (the card does not show, because the only way to enable is the system settings).
Continue requests `POST_NOTIFICATIONS` through the chat activity's `ActivityResultLauncher` (an embedded
`ReceptionChatView` uses the host activity when it is a `ComponentActivity`, otherwise the card does not show), then
runs `registerForPushIfAuthorized()`. Only an answer the system keeps is stored: after the result the SDK re-reads
`areNotificationsEnabled()` and `shouldShowRequestPermissionRationale` (true after a denial); a dialog dismissed
without a choice changes neither, stays "not determined", and the card stays hidden only until the next launch. No thanks stores the per-device flag under the App ID in SharedPreferences.
The status line "You'll get a notification when we reply." follows the iOS rule with `areNotificationsEnabled()`.
The stored answer (`<namespace>.notificationPermissionAnswered`) sits beside `pushPromptDeclined`, outside the session.

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

Validation limits, availability generations, info-only entries, receipts and events are the iOS ones.

`onPaywall` runs while the chat stays open, as on iOS. The SDK's sheet is its own activity
(`ReceptionChatActivity`), so a dialog the host shows on its own activity appears under the sheet. A host that presents
its paywall from `onPaywall` as a dialog or an activity calls `Reception.closeChat()` first when it wants the paywall
above the SDK's sheet. iOS keeps the chat open underneath the paywall; Android cannot. An embedded `ReceptionChatView`
is part of the host's screen and has no such limit.

Link taps:

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

Addresses tapped in message text take the same route, including closing the chat before a link the host app
handles, but send no `LinkOpened` event and no receipt. Every query and launch uses the URL with its scheme in
lowercase (`Uri.normalizeScheme`), because intent filters compare schemes case-sensitively; the allowlist itself
ignores case.

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
class ReceptionColor(color: Color) / ReceptionColor(light: Color, dark: Color) / ReceptionColor.resource(@ColorRes id: Int)
ReceptionColor.argb(light: Int, dark: Int = light)  // @JvmStatic @JvmOverloads, for Java hosts
```

`ReceptionColor` is the iOS dynamic color: one color for both modes, a light/dark pair, or a color resource whose
night-qualified variant (`values-night`) is the dark one. The chat picks the variant of the dark mode it resolved
(system, `preferredColorScheme`, or the enclosing theme of an embedded view). A resource is read once, both variants
through `createConfigurationContext` with `UI_MODE_NIGHT_NO`/`UI_MODE_NIGHT_YES` on the application context, and
cached; before `configure` it has no context and counts as unset. The host appearance report sends `{ light, dark }`
like a remote adaptive pair (a single color sends the same value twice, as before). Java cannot call the `Color`
constructors (Compose `Color` is a value class), so `ReceptionColor.argb(0xFF5B4FE0, 0xFFB0A8FF)` takes ARGB ints.

`Appearance` has exactly the iOS properties with Compose types; every color is a `ReceptionColor?`: `accentColor` (null = `MaterialTheme.colorScheme.primary` of the enclosing theme when the
host embeds `ReceptionChatView` in its own `MaterialTheme`; the SDK's own sheet wraps the chat in a Material theme with
dynamic color on Android 12+, so the default is the device's system accent, and the iOS system blue (#007AFF light,
#0A84FF dark) below 12. The XML theme attributes `colorPrimary`/`colorAccent` are never read: Compose-only apps leave
them at platform defaults. The host appearance report sends the accent only when the host set one. An embedded
`ReceptionChatView` whose enclosing `MaterialTheme.colorScheme` is Material 3's baseline `lightColorScheme()` counts as
having no host theme: it follows the system dark mode and uses the sheet's default accent, so hosts wrap it in their
theme or set `accentColor`), `onAccentColor`, `title`, `welcomeTitle`, `welcomeText`, `showsCloseButton`,
`chatBackground`, `incomingBackground`, `incomingForeground`, `preferredColorScheme: ColorScheme?` (`LIGHT`, `DARK`),
`closeIcon: CloseIcon` (`XMARK`, `CHEVRON_DOWN`, `ARROW_DOWN`), `closeImage: Painter?` and `closeImageResource: Int?`
(`@DrawableRes`, for hosts without Compose; there is one close image, so setting either replaces the other), `fontDesign: FontDesign?`
(`DEFAULT`, `ROUNDED`, `SERIF`, `MONOSPACED`; Android has no rounded system face, so `ROUNDED` draws the default
face and is still reported as `rounded`), `fontFamily: FontFamily?` (a Compose font family the host built from its
bundled fonts; reported to the dashboard as "custom"), `fadesOlderMessages`, `welcomeIcon: WelcomeIcon?`
(`symbol(name)`, `image(Painter)`, `resource(@DrawableRes id)`, `hidden`; resources are drawn with `painterResource`, so
vector and bitmap drawables), `welcomeIconColor`, `welcomeIconSize: Dp?`, `welcomeIconCornerRadius:
Dp`, `welcomeIconOffset: DpOffset`, `hidesTitle`, `hidesWelcomeTitle`, `hidesWelcomeText`, `hidesPushStatus`.

Symbols: the dashboard and the host name SF Symbols. The SDK ships one Material Symbols vector drawable per name the
dashboard offers (`appearance-symbols.ts`: welcome, card and accessory symbols) plus the built-in icons (`xmark`,
`chevron.down`, `arrow.down`, `bell.fill`, photo, send, failure, link) and maps names to them in `AppearanceIcon`;
an unknown symbol name draws the built-in icon for that slot, as iOS does for a symbol the device cannot draw.
Remote appearance resolution, cache, activation, visibility and `hidden` rules, card styles, team photos/names,
adaptive color pairs and the host appearance report follow `SDK_SPEC.md` and `REMOTE_APPEARANCE.md` exactly.

### Automations and paid access

`Reception.track(event: String, opensChat: Boolean = false)` is the iOS call with the same rules and caching.
The chat an automation opens follows the iOS rule (only within 5 s of `track`, once the app is active and an activity
is resumed), with one Android difference: the sheet starts only after its presentation record is saved, so the rule
waits for that outcome instead of a synchronous answer. A presentation cancelled meanwhile (another activity resumed)
is tried again over the settled activity within the same 5 s, and the window and the session are checked again right
before the sheet starts, so a save that ends after the window opens nothing.

Paid access: iOS reads StoreKit. Android reads Play Billing when `com.android.billingclient:billing` (5+) is on the
host's classpath: at configure and every foreground, `BillingClient.queryPurchasesAsync` for `SUBS` and `INAPP`; any
purchase in state `PURCHASED` means paid access (`subscribed`). The billing connection is short-lived and every error
means "unknown" (field omitted), never false. `transactionIds` is omitted on Android (there is no App Store Server
Notification equivalent in this release; RevenueCat, Superwall and Adapty webhooks identify the person through their
own ids). Of the five store events, Android reports `subscription_cancelled` (an active subscription's
`isAutoRenewing` turned false) and `subscription_expired` (a subscription purchase disappeared from the query); trial
and billing-issue states are not visible through Play Billing on the device and are documented as not reported
(hosts with RevenueCat/Adapty/Superwall get them through those webhooks). Without the billing library nothing is read
and nothing is sent, with one debug log. Billing 7+ gets `enablePendingPurchases(PendingPurchasesParams)`, 5 and 6 the
parameterless call (removed in 8). A Play Store without a signed-in account answers `BILLING_UNAVAILABLE`: unknown.

### Setup check

On a debuggable host, an activity started with the string extra `ReceptionSetupCheck=<App ID>` (read in the
activity-lifecycle callback when the SDK was configured before that activity was created, as in
`Application.onCreate`, and from the activity a plugin hands to `Reception.noteCurrentActivity`, so Flutter and React
Native hosts that configure later run the same command) sends "Hello from setup" once after the app is active and logs `Setup check passed` or `Setup check failed:
app_id_mismatch|send_not_allowed|not_delivered|timeout|app_not_active` with the iOS rules, at every log level and past
`logHandler`, tag `Reception`:

```sh
adb shell am start -S -n <package>/<launcher activity> --es ReceptionSetupCheck <App ID>
adb logcat -s Reception | grep "Setup check"
```

The iOS `ChatPreview` launch argument is not ported in 1.0.

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
stays in memory. Unlike the iOS actor, a token's gate suspends on the storage thread, so it checks after each
suspension, and once more right before handing a token out, that the session has not ended (logout, deletion,
reconfiguration: `cancelled`); the retry after a rejected token is authorized again (halt, cooldown, ended session)
before it is sent, keeping the probe permission of the first send.

Device info: `platform: "android"`, `osVersion: Build.VERSION.RELEASE`, `deviceModel: Build.MANUFACTURER + " " +
Build.MODEL` (emulator reports `Google sdk_gphone64_arm64`, which the service recognizes as "Emulator"),
`appVersion`/`appBuild` from `PackageInfo`, `bundleId: packageName`, `locale`, `timezone`, `language`, `sdkVersion`,
`capabilities: ["linkCards", "screens"]`, everything else as iOS.

## Backend contract additions (implemented in `../reception`)

- `platform` accepts `"android"` beside `"ios"`. `GET /v1/appearance` takes `?platform=android` (absent means iOS) so
  the `pushPrompt` it returns reflects that platform's push credentials; the ETag varies with it. Saved links to the App
  Store are offered only to iOS devices and Google Play links only to Android devices. Android devices use `pushEnvironment: "PRODUCTION"` and FCM tokens
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
