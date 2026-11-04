# Contributing to the Reception Android SDK

This guide is for anyone changing the Reception Android SDK. Customers add the SDK to their Android app and use the hosted Reception service and dashboard; their setup starts at [README.md](README.md) and the [Android guide](https://reception.sh/docs/android).

Read [AGENTS.md](AGENTS.md) and [SPEC.md](SPEC.md) before working on the SDK. The SDK mirrors the iOS SDK (`../reception-ios`); SPEC.md names every place where Android differs. Maintainers with access to the private Reception backend repository also read its SDK specification (`docs/SDK_SPEC.md`) and wire protocol (`docs/REST_API.md`); the backend and service operations live there.

## Prerequisites

- JDK 17 (`JAVA_HOME`) and the Android SDK with platform 36 and build-tools 36 (`ANDROID_HOME`, or `sdk.dir` in `local.properties`, which is not committed).
- An emulator with Google Play services (Android 16 image, for example `emulator -avd <name> -no-window -no-audio`), or a phone with USB debugging.
- python3, for `tool/sync_strings.py` and the release check.

## Strings

The built-in texts come from the iOS String Catalog (`../reception-ios`) plus `tool/android-strings.json`, the few strings only Android needs. They are generated Kotlin, not Android resources (see SPEC.md, Language); never edit `reception/src/main/kotlin/sh/reception/strings/` by hand:

```sh
tool/sync_strings.py          # regenerate
tool/sync_strings.py --check  # verify, as the release check does
```

## Example app and local backend

`example/` is the verification app (Compose, with Firebase Messaging and Play Billing). Its debug build calls `Reception.configureForTesting(context, appId, serviceUrl)` (`@ReceptionInternalApi`, accepted only by debuggable apps) with `http://10.0.2.2:3123`, the host machine's local backend as the emulator sees it; release builds call `configure`. Debug builds also turn on StrictMode with `penaltyDeath`, so any disk access on the main thread crashes the run.

Local values stay in `.local/`, which git ignores:

- `.local/app-id.txt`: the App ID of your test app on the local backend. Without it the example starts unconfigured.
- `.local/identity-secret.txt`: that app's identity secret, for the example's verified-user button. Only a test app's secret, never a real one; real apps sign tokens on their server.
- `.local/google-services.json` and the Firebase service-account key, see below.

```sh
./gradlew :example:installDebug
adb shell am start -n sh.reception.receptionAndroidExample/.MainActivity
adb logcat -s Reception ReceptionExample
```

`-PreceptionExampleBare=true` builds the example without Firebase and Billing, like a host that uses neither. The debug build has a `VerificationReceiver` for driving push data, the routing flag, a covering activity and logout over `adb shell am broadcast` (see the class).

## Firebase test project

Push is verified with real FCM messages. Use a Firebase project of your own for testing, never a customer's:

1. Create a project in the Firebase console and add an Android app with the package `sh.reception.receptionAndroidExample`.
2. Download its `google-services.json` to `.local/` and copy it to `example/google-services.json` (also ignored). The example applies the Google services plugin only when that file exists, so it builds without push otherwise.
3. Create a service account with the Firebase Cloud Messaging Admin role, download its JSON key to `.local/`, and upload it in the local dashboard's push settings for your test app (with the same package name).

Never commit either file, and never log tokens. Android delivers no FCM message to an app that was force-stopped until it is opened again; for "killed" scenarios use `adb shell am kill` on a backgrounded app instead.

## Setup check

On a debuggable build, start the app with the extra and read the result:

```sh
adb shell am start -S -n sh.reception.receptionAndroidExample/.MainActivity --es ReceptionSetupCheck <App ID>
adb logcat -d -s Reception | grep "Setup check"
```

It sends one real message. It also runs for Flutter and React Native hosts, which hand their activity to the SDK after configuring.

## Unit tests

`reception/src/test` holds focused tests (JUnit 4, MockWebServer, Robolectric where an Android class is unavoidable) for the parts that are easy to get subtly wrong: identity tokens, the single-flight token refresh, cooldowns, notification routing, presentation, validation, and the paywall and appearance-report contracts. Everything else is verified with the example app on the emulator against a local backend, with the commands and observed output in `verification/`.

```sh
./gradlew :reception:test
```

## Public API

`reception/api/reception.api` lists every public symbol. `./gradlew :reception:apiCheck` (also part of `check` and the release check) fails when the public API changes; when the change is intended and listed in SPEC.md, record it with `./gradlew :reception:apiDump` and commit the file with the change.

## Checks

`tool/release_check.sh` runs every automated check in order from the repository root and prints a summary. It requires a clean working tree, then checks that `Version.kt`, the top CHANGELOG entry and the README agree, verifies the generated strings, builds the library from clean (release build, lint, unit tests, API check) and fails on any warning line, builds the example's release variant with R8 with and without Firebase and Billing, publishes to Maven Local and checks the POM (coordinates, license, SCM, dependency scopes: Compose `api`, the rest `implementation`, no Firebase or Billing), and signs the publication when a signing key is configured.

The manual checks before a release are the verification round on the emulator (SPEC.md, Verification): every push scenario with real FCM, the chat in light and dark mode, German, font scale 1.3, rotation, the keyboard over the sheet and the embedded chat, offline and reconnect, identity and token switch, logout, deletion and the setup check, with StrictMode on. Report actual results, or the precise reason a check was not run.

## Releases

Customers install versions from Maven Central (`sh.reception:reception:<version>`); commits on `main` reach nobody until a version is published.

Once, before the first release:

1. Create an account on the [Sonatype Central Portal](https://central.sonatype.com) and add the namespace `sh.reception`. The portal shows a verification key; add it as a DNS TXT record on `reception.sh` and verify the namespace there.
2. Generate a user token in the portal (Account → Generate User Token).
3. Create a GPG key for signing, publish its public key to a key server (`gpg --keyserver keyserver.ubuntu.com --send-keys <key ID>`), and export the private key in ASCII armor.
4. Put the credentials into `~/.gradle/gradle.properties` on the release machine, never into the repository:

   ```properties
   mavenCentralUsername=<token user>
   mavenCentralPassword=<token password>
   signingInMemoryKey=<ASCII-armored private key, newlines as \n>
   signingInMemoryKeyId=<last 8 characters of the key ID>
   signingInMemoryKeyPassword=<key passphrase>
   ```

For each release:

1. Choose the version: patch (`1.0.1`) for fixes, minor (`1.1.0`) for new features, major (`2.0.0`) only when customers must change their code.
2. Set `SDKVersion.current` in `reception/src/main/kotlin/sh/reception/Version.kt`. It is the only place the version is written: the published coordinates read it, and the release check verifies that the README and the top CHANGELOG entry agree.
3. Move the `Unreleased` entries in [CHANGELOG.md](CHANGELOG.md) under the new version and date, and update the version in README.md.
4. Commit, then run `tool/release_check.sh` on the clean tree; it must pass, including the signing step.
5. Publish: `./gradlew publishToMavenCentral`. It uploads a signed deployment; check it in the Central Portal under Deployments and publish it there. (`./gradlew publishAndReleaseToMavenCentral` uploads and publishes in one step.)
6. Tag the commit with the bare version (`git tag 1.0.1`, no `v` prefix), push `main` and the tag, and publish a GitHub release with the CHANGELOG text.

Maven Central never deletes or replaces a published version. Never move or delete a published tag; release a new version instead.
