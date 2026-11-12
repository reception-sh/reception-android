# AGENTS.md — Rules for everyone who writes code here

These rules are for anyone changing this SDK, including coding agents. Customers who add the SDK to their app start at
[README.md](README.md) and the [setup guide](https://reception.sh/docs/get-started).

`Reception` is the Android SDK for an in-app support chat. It mirrors the iOS SDK in `../reception-ios` one to one;
the maintainers' specification, `SPEC.md`, names every place where Android differs and why. It is kept with the
maintainers' notes outside this repository (`../reception-internal/android/`); comments that cite "SPEC.md" refer to
it. The SDK talks to the hosted Reception service through
its `/v1` API. Maintainers with access to the private backend repository read its SDK specification
(`../reception/docs/SDK_SPEC.md`) and wire protocol (`../reception/docs/REST_API.md`) before changing behaviour that
touches the API: paths, headers and field names there are binding. Without that access, treat the client code in
`reception/src/main/kotlin/sh/reception/client/` as the contract.

When porting, open the iOS file with the same name and port its behaviour: every guard, every generation check, every
log line. Do not port its structure where Kotlin has a better idiom, and do not invent behaviour the iOS SDK does not
have.

## Stack (fixed)

Kotlin 2.0.21 with `explicitApi()`, Jetpack Compose (BOM 2024.12.01, Material 3), AGP 8.9.3, Gradle 8.11.1, JDK 17,
minSdk 24, compileSdk/targetSdk 36, kotlinx-coroutines and kotlinx-serialization, OkHttp + okhttp-sse, Coil. Firebase
Messaging and Play Billing are `compileOnly` and only ever touched behind a class-presence check. **No other
dependencies**, no native code. Must build without warnings (`allWarningsAsErrors`) and pass `lint` with the
project's `lint.xml`.

## The style we aim for

**Extremely clean, simple, safe at the boundaries.** The SDK runs in other apps: it must never crash, never block the
main thread, never block startup, never require anything from the host.

- One file, one responsibility. Nothing over ~200 lines. The one deliberate exception is `Reception.kt`: it holds the
  public `Reception` object with its nested public types (`Appearance`, `CloseIcon`, `FontDesign`, `ColorScheme`,
  `WelcomeIcon`, `LogLevel`), because Kotlin cannot move a nested public type out of its object without renaming it
  (`Reception.Appearance` would become a new API). Everything else in it stays a one-line delegation to an internal
  file.
- Keep the public API small and exactly what SPEC.md lists. Everything else `internal`. No `public` "just in case".
  `explicitApi()` is on, so every public symbol is a decision.
- Errors at the boundaries: network calls return `Result`/throw typed errors; the view model translates them into UI
  state. No `!!`, no unchecked casts, no `runCatching` that swallows programming errors, no `GlobalScope`.
- Secure storage and SharedPreferences access each have their own file and catch all their own errors.
- Main thread for the facade, state and UI (`Dispatchers.Main.immediate`); networking in `suspend` functions on
  `Dispatchers.IO`; no callbacks where a coroutine does, no RxJava, no LiveData.
- Composables contain only layout. No networking, no logic in composables. Colors, spacing, radii and animations
  exclusively from `Theme`/`Appearance`, nothing hardcoded.
- No dead code, no commented-out blocks, no TODOs. Comments only for the *why*.
- Names: clear, no abbreviations. Files are named after their main type.
- Use `Log.error`, `Log.info`, or `Log.debug`; never `println`/`android.util.Log` directly, and never log access
  tokens, session secrets, identity tokens or their claims, identity secrets, push token values, user IDs, names, email
  addresses, metadata values, message text, image URLs, conversation content, or service credentials.
- **Focused unit tests only** (JUnit 4, MockWebServer; Robolectric only where an Android class is unavoidable), for
  security-relevant parsing and concurrency that is easy to get subtly wrong (identity token decoding, single-flight
  token refresh, cooldown parsing, notification routing, validation), and focused paywall and appearance-report
  contract checks. Everything else is verified with the example app on the emulator against a local backend. Report
  actual output; a build is not a verification.

## UI

Native, like a Material 3 bottom sheet: system font (Roboto), font scale, dark mode, keyboard avoidance without
jumps, auto-scroll on new messages, predictive back. The host app owns entry buttons, settings rows, positioning and
unread badges. `Reception.openChat()` presents the standard chat sheet over the current activity;
`ReceptionChatView` remains available for host-owned presentation.

**Distinct identity:** the same chat as on iOS. Bubbles have radius 16, padding 10/14 and no tail; a photo icon,
send arrow, speech-bubble empty state, its own status/connection text and timestamp formats define the interface.
Compare against the iOS simulator side by side; measurements in `Theme.kt` are the iOS ones in dp.

## Git and releases

Small, focused commits. Customers install tagged versions from Maven Central, never `main`. A release is a
[CHANGELOG.md](CHANGELOG.md) entry plus a semantic version tag; see [CONTRIBUTING.md](CONTRIBUTING.md). Never commit
`.local/` (Firebase credentials of the test app), `local.properties`, signing keys or test App IDs.
