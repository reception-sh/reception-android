# Changelog

All notable changes to the Reception Android SDK are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows [Semantic Versioning](https://semver.org/).

## [1.0.0] — Unreleased

First public release, with the features of the Reception iOS SDK 1.7.0. Requires Android 7.0 (API 24) or later.

### Added

- `Reception.configure(context, appId)` connects your app with its public App ID from the Reception dashboard. Install `sh.reception:reception` from Maven Central; no manifest edits, R8 rules or other dependencies are needed.
- Native support chat with text and photos, opened with `Reception.openChat()` as a Material 3 bottom sheet over the current activity, or embedded as the `ReceptionChatView()` composable or the `ReceptionChatView` View. The sheet closes by drag, scrim tap and (predictive) Back; while the keyboard is open, a drag from its handle or title bar closes it and a drag over the messages puts the keyboard away.
- `Reception.unreadCount` and `Reception.isChatOpen` as `StateFlow`s for your own entry point and badge, plus `addUnreadCountListener` for Java code.
- `identify(userId, name, email)`, `setMetadata(_)`, `logout()` and `deleteData()` (suspending, or with a callback) for accounts, customer context, sign-out and account deletion.
- Identity verification with `identifyWithToken(token)` for tokens signed by your own server, and the `IdentityRejected` event.
- Push notifications for replies through Firebase Cloud Messaging: the SDK fetches and registers the FCM token by itself when your app includes Firebase Messaging and notifications are allowed, never asking for permission. `ReceptionMessagingService` receives the messages for apps without a messaging service of their own; others forward them with `handlePushMessage(context, message.data, message.messageId)` and `setPushToken(token)`. Notifications use their own "Support replies" channel in the person's language and are not shown while the chat is open; a repeated FCM message shows once. Taps open the chat with no code in your app, also when the app was not running.
- Launcher badges follow the unread count while `updatesAppBadge` is on (the default); `notificationSmallIcon` sets your notification icon.
- A card in the chat can ask for notification permission on Android 13 and later after the person's message, when enabled in the dashboard, and a status line confirms that replies will arrive as notifications.
- Google Play review cards and paywall cards. Register `Reception.paywalls`, present your own paywall from `onPaywall`, and limit offers with `paywallAvailability`.
- Link cards that open websites, other apps, Google Play, mail and the phone without code; web links open in the app that claims them, otherwise in a Custom Tab over the chat (`opensWebLinksInApp = false` hands them to the browser). Links to your app's own intent filters close the chat first. `Reception.screens` lists the places in your app that links can open.
- `Reception.track(event, opensChat)` reports app events for automations. With Google Play Billing in your app, device registration reports whether the person has paid access, and the SDK reports `subscription_cancelled` and `subscription_expired`.
- Local appearance options with `ReceptionColor` for light and dark mode (one color, a pair, or a color resource with a night variant; `ReceptionColor.argb` for Java), a custom `FontFamily`, welcome icon and close button options, plus remote appearance, texts, team photos and names published from the dashboard. Device registration reports the appearance your app sets in code, so the dashboard's design editor starts from it.
- Built-in text in 48 languages, following the app's per-app or system language, with `languageOverride` for in-app language settings; font scale, dark mode and right-to-left layouts.
- Events through `onEvent` for your analytics.
- Automatic retries for interrupted sends and rate-limit waits that keep drafts and resume on their own.
- The chat sheet comes back after the system ended the app's process while it was on screen.
- Setup check on debuggable builds: launching the app with the extra `ReceptionSetupCheck` sends one message and logs whether it was delivered.
- Device sessions with short-lived access and credentials encrypted with a key in the Android Keystore, excluded from backups.
