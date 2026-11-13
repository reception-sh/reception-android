# Reception Android SDK

The Reception SDK adds native in-app support chat to Android apps, with replies managed in the hosted Reception dashboard.

**Reception** is a hosted support chat for mobile apps. Your customers write to you in a native chat inside your app on iOS and Android, and your team replies from the Reception dashboard, Slack or Telegram. This repository is the native Android SDK for Jetpack Compose and View-based apps, on Maven Central as `sh.reception:reception`.

- **Docs:** [Android guide](https://reception.sh/docs/android) · [All guides](https://reception.sh/docs)
- **Price:** $30 per month per organization, plus sales tax or VAT where it applies. One subscription covers every app and every team member.
- **Free trial:** [Create an account](https://reception.sh/login) and set up your app. Your first subscription starts with a 7-day free trial: you add a card in Stripe Checkout, and nothing is charged if you cancel within the 7 days. Before you subscribe, you can reply to your own test device.
- **Other platforms:** [iOS](https://github.com/reception-sh/reception-ios) · [Android](https://github.com/reception-sh/reception-android) · [React Native](https://github.com/reception-sh/reception-react-native) · [Flutter](https://github.com/reception-sh/reception-flutter)

Requires Android 7.0 (API 24) or later, `compileSdk` 35 or later, Kotlin 2.0 or later and Android Gradle Plugin 8. Devices on Android 7.0 itself predate Let's Encrypt's root certificates: there the chat connects only if your app's [network security configuration](https://developer.android.com/privacy-and-security/security-config) adds them; Android 7.1.1 and later need nothing. The chat is drawn with Jetpack Compose and Material 3; apps built with Views use it without writing Compose code.

## Setup

The easiest setup is to choose **Copy setup prompt** in your Reception dashboard and paste it into a coding agent with your Android project open.

Add the dependency to your app module's `build.gradle.kts`. The SDK is on Maven Central:

```kotlin
dependencies {
    implementation("sh.reception:reception:1.0.0")
}
```

It needs no manifest edits, no R8 rules and no other dependency. Firebase Cloud Messaging and Google Play Billing are used only when your app already includes them.

Configure once in your `Application.onCreate`, using the App ID from your dashboard:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Reception.configure(this, "app_…")
    }
}
```

Open chat from your own button or settings row:

```kotlin
TextButton(onClick = { Reception.openChat() }) { Text("Support") }
```

In a View-based screen, call it from a click listener: `supportButton.setOnClickListener { Reception.openChat() }`. The chat opens as a bottom sheet over the current activity. To place it in your own navigation instead, use the `ReceptionChatView()` composable or the `sh.reception.ReceptionChatView` View.

`Reception.unreadCount` is a `StateFlow<Int>` for your own badge; collect it with `collectAsState()` and show the badge above zero.

## User details

Call these after `configure`, with values from your app:

```kotlin
Reception.identify(userId = userId, name = name, email = email)
Reception.setMetadata(mapOf("plan" to "pro"))
```

Call `Reception.logout()` when a signed-in session ends, and `Reception.deleteData()` from a coroutine when the person deletes their account. For verified identity, pass a token signed by your server to `Reception.identifyWithToken(token)`.

## Push notifications

Replies arrive through Firebase Cloud Messaging. With Firebase Messaging in your app, the SDK registers the token by itself when notifications are allowed, and never asks for permission. If your app has no messaging service of its own, add the SDK's to `AndroidManifest.xml`:

```xml
<service
    android:name="sh.reception.ReceptionMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

If your app has its own `FirebaseMessagingService`, forward Reception's messages from it instead:

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    if (Reception.handlePushMessage(this, message.data, message.messageId)) return
    // Your existing handling.
}

override fun onNewToken(token: String) {
    Reception.setPushToken(token)
}
```

That service must run in your app's main process (no `android:process`), where the SDK works; in other processes `configure` is refused with a log line and Reception's messages are not shown.

Taps on Reception's notifications open the chat with no code in your app. Upload your Firebase service-account key in the dashboard's push settings.

## Paywalls, screens and links

Register your purchase screens in `Reception.paywalls` and present them from `Reception.onPaywall`, list the places link cards may open in `Reception.screens`, and report moments for automations with `Reception.track("paywall_declined")`. Web links open in the app that claims them, otherwise in a Custom Tab over the chat.

## Appearance, language and logging

Set `Reception.appearance` right after `configure`. Every color is a `ReceptionColor`: one color, a light and dark pair, or a color resource with a `values-night` variant.

```kotlin
Reception.appearance.accentColor = ReceptionColor(light = Color(0xFF5B4FE0), dark = Color(0xFFB0A8FF))
Reception.appearance.chatBackground = ReceptionColor.resource(R.color.surface)
```

Java code uses `ReceptionColor.argb(0xFF5B4FE0, 0xFFB0A8FF)`. The design you publish from the dashboard applies on top.

The chat follows your app's language, in 48 languages. With an in-app language picker of your own, set `Reception.languageOverride` to its language tag.

The SDK logs to Logcat with the tag `Reception`. Set `Reception.logLevel = Reception.LogLevel.DEBUG` while setting it up, or route the lines into your own logger with `Reception.logHandler`.

## Documentation

- [Setup](https://reception.sh/docs/get-started)
- [Android guide](https://reception.sh/docs/android)
- [All guides](https://reception.sh/docs) · [Index for AI agents](https://reception.sh/llms.txt)

[Changelog](CHANGELOG.md) · [Contributing](CONTRIBUTING.md) · [MIT license](LICENSE) · [Third-party notices](THIRD_PARTY_NOTICES.md)
