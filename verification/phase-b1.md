# Phase B1 verification (2026-11-04)

Toolchain fixes (AGP 8.9.3, Gradle 8.11.1, Compose `api`, strings as Kotlin, app language rule) and the client core.
Environment: `JAVA_HOME=/opt/homebrew/opt/openjdk@17`, `ANDROID_HOME=~/Library/Android/sdk`.

## Build, lint, unit tests

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all --rerun-tasks
BUILD SUCCESSFUL in 14s
116 actionable tasks: 116 executed
```

No warning lines in the output (`grep -ci warning` → 0). AGP 8.9.3 needs no `android.suppressUnsupportedCompileSdk`.
On the first build AGP downloaded Build-Tools 35, and the SDK manager printed "This version only understands SDK XML
versions up to 3 but an SDK XML file of version 4 was encountered". That happens only during the download; later
builds print nothing.

Unit tests (debug and release variants each):

| Suite | Tests | Failures |
| --- | --- | --- |
| ReceptionLocalizationTest | 3 | 0 |
| ReceptionStringsTest (Robolectric: tables, overlay, ru plural 1/3/5 → one/few/many, ja → other) | 5 | 0 |
| ThemeTest | 1 | 0 |
| IdentityTokenTest (string, integer, 1e3, > 2^53, bool, null, length, malformed) | 5 | 0 |
| RequestRulesTest (`accepts`, routing, gating, Retry-After cap, 0 → 1 s, 503 only with scope, envelope) | 8 | 0 |
| SecureStoreTest (Robolectric Keystore → `Unavailable`, AES-GCM round trip with an injected key, restored entry → `Missing`, tamper) | 4 | 0 |
| DeviceStoreTest (generation rotation, cleared store, unreadable session, chat reset) | 4 | 0 |
| SessionAuthorizationTest (MockWebServer: single-flight renewal, `401 unauthorized` retry once, `session_revoked` reset, registration and update payload) | 5 | 0 |

Mutation check: when `renewedToken` no longer shares `refresh`, `concurrentCallersShareOneRenewal` fails (line 89: two
token requests). When a second `401` triggers another renewal, `aSecondUnauthorizedAnswerIsNotRetriedAgain` fails
(line 123). Both changes were reverted.

Every commit of the phase compiles on its own (`compileReleaseKotlin`, `compileDebugUnitTestKotlin`,
`:example:compileDebugKotlin` in a temporary worktree, commits 8907638 through 6bdafe7).

```
$ tool/sync_strings.py --check
strings up to date (49 files)
```

## Live smoke test: not run

The example is built and installed on emulator-5554 (`adb install -r example-debug.apk` → `Success`, logcat cleared)
with App ID `app_0oLy3FxjPrgypqh8StRP6t` from `.local/app-id.txt` and `http://10.0.2.2:3123`. The local backend
answers (`curl localhost:3123/v1/health` → `{"ok":true}`, branch `android-push`).

The app could not be launched or tapped. The device tools do not see the Android emulator:

- `device_list`: "Failed to run `avdmanager list avd` … Unable to locate a Java Runtime … Could not list Android virtual devices (emulator -list-avds, exit code 127)", with no Android device listed
- `device_open` (hostId `local`, `emulator-5554` or `platform: android`): "No android devices were found on host local"

So there are no logcat lines for `Device registered` or `Device updated` and no Device row yet. The registration and
update payloads are covered by `SessionAuthorizationTest` against MockWebServer, which is not the backend. Steps once
the device host finds Java (for example by giving the T3 device host `JAVA_HOME`):

1. Launch `sh.reception.receptionAndroidExample`. Logcat (`adb logcat -s Reception`) should show `Configured, SDK 1.0.0`, `Device registration started`, `Device registered`, `Device updated`, `Unread refresh, 0 unread`.
2. Tap "Identify test user", send the app to the background and bring it back. Expect `Identity staged`, then `Device updated`.
3. `cd ../reception && npx prisma db execute` (or `psql` from `.env`):
   `select platform, "sdkVersion", "externalUserId", "deviceModel" from "Device" order by "createdAt" desc limit 1;`
   should show `android | 1.0.0 | android-example-user | Google sdk_gphone64_arm64`.
