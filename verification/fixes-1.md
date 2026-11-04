# Fixes 1: client-core audit and decided items

Run 2026-11-04 on `main` after 2961cd0. Emulator `emulator-5554` (Android 16), local backend `http://10.0.2.2:3123`
(branch `android-push`, `/v1/health` → `{"ok":true}`), App ID from `.local/app-id.txt`.

## Commits

| Item | Commit | Test |
| --- | --- | --- |
| 1 Renewal/registration single flight | 7372ef7 | `SessionAuthorizationTest.aCancelledCallerLeavesTheRenewalToTheNextCaller`, `…TheRegistrationToTheNextCaller` (both fail on the old code: 2 of 7 failed) |
| 2 Durable secure storage writes | c6e8134 | `SecureStoreTest.aWriteThatDoesNotReachTheDiskReportsFailure`, `SessionAuthorizationTest.aRegistrationThatCannotBeStoredIsRetriedFromMemoryBeforeUse` |
| 3 Storage off the main thread | b1104e8 | StrictMode `penaltyDeath` in the example's debug `Application`; live run below |
| 4 INTERNET permission, bare example | a715170 | bare build below |
| 10 Test harness | 5e7d002 | `ReceptionState.resetForTests()` in teardowns |
| 5 `identify("x")` stages a user ID | 302bfed, 2381d15 | `ReceptionIdentityTest` |
| 6 Launch registration on a later turn | 5cbf30b | `LaunchRegistrationTest` (real main dispatcher; fails on the old code at the `identityToken` assertion) |
| 7 Declared-locale fallback | 63936d7 | `AppLocaleTest` |
| 8 Namespaces per service address | 5980ac1 | `DeviceStoreTest.twoServiceAddressesWithTheSameAppIdKeepSeparateSessions` |
| 9 Default accent | 08f435a | `HostAppearanceReportTest.theDefaultAppearanceReportsOnlyTheSettingWithoutAnAccent` |

## Build

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all
BUILD SUCCESSFUL in 10s
116 actionable tasks: 23 executed, 8 from cache, 85 up-to-date
(no warnings; test results: debug 71 tests, 0 failures; release 71 tests, 0 failures; lint: "No issues found.")
$ tool/sync_strings.py --check
strings up to date (49 files)
```

## Live run, full example (Firebase, Billing), StrictMode with `penaltyDeath`

`adb install -r example-debug.apk`, `pm clear`, then: launch; tap "Identify test user"; Home and reopen; tap "Log out";
Home and reopen; relaunch (`open … --relaunch`). `adb logcat -d | grep -E "Reception:|StrictMode|FATAL"`:

```
11-04 14:54:21.097 10551 10551 I Reception: [Reception] INFO Configured, SDK 1.0.0
11-04 14:54:21.186 10551 10551 D Reception: [Reception] DEBUG Device registration started
11-04 14:54:21.444 10551 10551 D Reception: [Reception] DEBUG Remote appearance refreshed, revision 1
11-04 14:54:21.552 10551 10551 I Reception: [Reception] INFO Device registered
11-04 14:54:21.729 10551 10551 D Reception: [Reception] DEBUG Device updated
11-04 14:54:21.834 10551 10551 D Reception: [Reception] DEBUG Unread refresh, 0 unread
11-04 14:54:28.575 10551 10551 D Reception: [Reception] DEBUG Identity staged
11-04 14:54:34.222 10551 10551 D Reception: [Reception] DEBUG Device updated
11-04 14:54:34.247 10551 10551 D Reception: [Reception] DEBUG Unread refresh, 0 unread
11-04 14:54:45.602 10551 10551 I Reception: [Reception] INFO Logged out, session revocation queued
11-04 14:54:46.124 10551 10551 D Reception: [Reception] DEBUG Session revocation completed
11-04 14:54:51.642 10551 10551 D Reception: [Reception] DEBUG Device registration started
11-04 14:54:51.803 10551 10551 I Reception: [Reception] INFO Device registered
11-04 14:54:51.888 10551 10551 D Reception: [Reception] DEBUG Device updated
11-04 14:54:51.940 10551 10551 D Reception: [Reception] DEBUG Unread refresh, 0 unread
11-04 14:54:58.932 10793 10793 I Reception: [Reception] INFO Configured, SDK 1.0.0
11-04 14:54:59.355 10793 10793 D Reception: [Reception] DEBUG Remote appearance unchanged
11-04 14:54:59.367 10793 10793 D Reception: [Reception] DEBUG Access token refreshed
11-04 14:54:59.434 10793 10793 D Reception: [Reception] DEBUG Device updated
11-04 14:54:59.547 10793 10793 D Reception: [Reception] DEBUG Unread refresh, 0 unread
StrictMode lines: 0
```

The backend's Device rows afterwards (`psql`, newest first):

```
2026-11-04 13:54:51.498|android|1.0.0|-|Google sdk_gphone64_arm64                      (after logout)
2026-11-04 13:54:21.064|android|1.0.0|android-example-user|Google sdk_gphone64_arm64   (identified)
```

Before the fix, the same launch with StrictMode (`penaltyLog`) reported main-thread disk reads in
`AppearanceCache.<init>`/`load`, `DeviceStore.preferences`/`generation`, `CacheFiles.directory`, `ChatCache.readSnapshots`,
`SecureStore.shared`, `SecureStore.KeystoreKey.existing`, `SecretBox.open` and `AppLocale.declaredMatch`.

## Live run, bare example (`-PreceptionExampleBare=true`)

No Firebase or Billing classes in the dex files (checked with the a715170 build; the dependency switch is unchanged
since) and no google-services plugin; the merged manifest has
`android.permission.INTERNET` from the library only. `pm clear`, launch:

```
11-04 14:55:24.262 10971 10971 I Reception: [Reception] INFO Configured, SDK 1.0.0
11-04 14:55:24.365 10971 10971 D Reception: [Reception] DEBUG Device registration started
11-04 14:55:24.726 10971 10971 D Reception: [Reception] DEBUG Remote appearance refreshed, revision 1
11-04 14:55:24.998 10971 10971 I Reception: [Reception] INFO Device registered
11-04 14:55:25.071 10971 10971 D Reception: [Reception] DEBUG Device updated
11-04 14:55:25.129 10971 10971 D Reception: [Reception] DEBUG Unread refresh, 0 unread
StrictMode lines: 0
```

The full build was reinstalled afterwards.
