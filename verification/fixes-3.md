# Fixes 3: Phase C1 audit and decided items

Run 2026-11-04 on `main` after e92c044. Every failing-first check below was run against the previous code by stashing
the main-source change: the new test failed there and passes with the fix.

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| F-1 registration race after a credential read | f575bda | `SessionAuthorizationTest.callersThatReadMissingCredentialsAfterAnUnreadableStoreRegisterOnce` (expected 1 registration, was 2) |
| F-2 revocation durable before the local reset | 94afb45 | `LogoutRevocationsTest.aLogoutDuringTheInitialLoadFinishesOnlyOnceTheRevocationIsOnDisk` (the job had completed during the load) |
| F-3 latest message time loaded in `open()` | 04642e0 | `DeviceStoreTest.openingAStoreWithAStartedChatLoadsTheLatestMessageTime` |
| F-4 foreground rechecked before launch registration | 332933b | `LaunchRegistrationTest.anAppBackgroundedWhileTheSessionPreparesDoesNotRegister` (POST /devices was sent) |
| F-5 no LocaleConfig parse on the caller's thread | 315d307 | none named |
| F-6 cached photo follows the authoritative team | e3bfaf2 | `TeamProfilesTest.aCachedPhotoRestoredAfterItsDownloadFailedIsStillUsed` (images empty), `…IsNotRestoredWhenTheTeamHidesPhotos` |
| F-7 team cache load independent of the caller | 7e9ad64 | `TeamProfilesTest.aCallerCancelledBeforeTheLoadDoesNotLetAWriteEraseTheCachedPhotos` (photo deleted) |
| F-8 baseline scheme rule for mode and accent | 85d894b | `ThemeModeTest.anEmbeddedChatUnderTheBaselineSchemeCountsAsUnthemed`, `…anyChangedColorRoleMakesTheSchemeTheHosts`, `…theSdksSheetNeverFollowsTheEnclosingScheme` |
| F-9 latch-gated cancellation tests | f7ee781 | three reruns, stable |
| D-1 chat stays visible under the photo picker | 6c06c23 | live check not run (see below) |
| D-2 `ImageSent` doc | fd048fc | — |
| D-3 splits | f531783, fd07d43, e6005f7 | full suite |

Notes:

- F-1: `token()` and `renewedToken()` check the token and the shared task again after `storedCredentials()`. In the
  test, the storage answers come from a queue, so both callers deterministically reach the second read.
- F-2: `enqueue` returns `Deferred<Boolean>`. It waits for the initial load, then for the merged write. `true` means
  the queue is on disk. `false` means it is held in memory only (unreadable secure storage or a failed write) or that
  nothing needed revoking. Like iOS, the logout's reset still proceeds after a `false`. In the test, the first disk
  access is gated and a fresh `SecureStore` reads the entry back. The test drives `LogoutRevocations` directly, the
  same way `configure` followed by `logout` does, because `logout()` does not expose the job.
- F-4: `registerIfNeeded` returns after `prepared()` when the app is no longer active. `token()` refuses a
  launch-context registration while the app is in the background, right before it starts. `SessionAuthorizationTest`
  used the launch context only to allow registration, so it now uses the automation context.
- F-5: before `prepare` finishes, `languageTag` answers without the declared locales and does not cache that answer.
  `prepare` publishes the parsed list through a volatile field on the storage thread. A session prepares on the same
  serial thread afterwards, so requests, which wait for `prepared()`, see the final language.
- F-8: Phase C1 had already applied the rule in `ChatRoot`, but it compared only primary, background, surface and
  secondary. `receptionDarkMode(embedded)`, the fallback outside a chat root, ignored the rule. `hostBackground()` now
  decides both mode and accent. Material 3 1.3.1's `ColorScheme` has no `equals`, so it compares all 36 color roles
  with `lightColorScheme()`. The accent follows from the same decision: without a host theme, `ChatRoot` wraps the
  SDK's scheme. There is no Compose UI test, because `ui-test` would be a new dependency.
- D-1: `ComposerModel.isPicking` is set when the picker launches and cleared in the result callback.
  `ChatLifecycle` ignores pause and stop while it is set. Start and resume are already idempotent, so no reload
  happens, the stream stays open and `isChatOpen` stays true.
- D-3: `ReceptionState` 195 lines (read generation moved to `ReceptionUnread`), `ChatModel` 188 (`ChatPolling.kt`),
  `SessionAuthorization` 166 (credential helpers moved to `SessionCredentials.kt`). Only `Reception.kt` (299) is over
  the guideline.

## Build

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all --rerun-tasks
BUILD SUCCESSFUL in 31s
116 actionable tasks: 116 executed
(no other output; lint: "No issues found."; unit tests: debug 112, 0 failures; release 112, 0 failures)
$ tool/sync_strings.py --check
strings up to date (49 files)
```

## Live run: not done

The example's debug APK with all commits above is installed on `emulator-5554` (`adb install -r`: Success). The
device CLI refused every UI action with `DEVICE_IN_USE`, because session `t3-5a3e8d97629e88edf5170305` holds the
device. `open --wait 120000` timed out with the device still busy. The device rules forbid reusing or closing another
session, and they forbid driving the emulator over adb, so the StrictMode run did not happen. It still needs to cover
a relaunch with an existing conversation (F-3), opening the chat, sending, picking a photo (D-1), logout and a
relaunch.

To check D-1, the backend's request log (`/private/tmp/reception-dev-3123.log`, one line per request, no
timestamps) shows whether a `GET /v1/conversation/messages` follows the picker. The SDK does not log its loads to
logcat.
