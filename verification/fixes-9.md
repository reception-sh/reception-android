# Fixes 9: audit of phase E (U-1..U-9, V-1, V-2, W-1)

Run 2026-11-04 on `main` from 92a04c1. Emulator `emulator-5554` (`reception_pixel8`, Android 16), local backend
`http://10.0.2.2:3123`, example debug build with StrictMode `penaltyDeath`, real FCM HTTP v1. Team reply: the round-6
scratchpad script (`createAgentMessage` as the first member, then `sendPush`), run from `../reception` and removed
afterwards. UI through the device CLI; adb for install, logcat, `dumpsys`, `am kill`, `run-as` and the verification
broadcast.

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| U-1 dedupe by `RemoteMessage.messageId` | 440938e | `ReceptionPushMessageTest.aRepeatedRemoteMessageThroughTheServiceIsHandledOnce` (a `RemoteMessage` built from a Bundle through `ReceptionMessagingService`: handled twice) |
| U-5 one global message-ID list | b1f6585 | `PushMessageIdsAcrossConfigureTest` (both directions fail: posted twice) |
| U-9 no sleep in the push test | 5b90a32 | `ReceptionPushMessageTest` (gated unread answer; with dedupe disabled it fails at `assertNull(runningRefresh)`) |
| U-3 routing generation | 8b693b3 | `ReceptionNotificationActivityTest.routingTurnedOffAfterTheResumeButBeforeTheOpenRunsCancelsIt` |
| U-4 request source | d02de18 | `ReceptionNotificationActivityTest.turningRoutingOffKeepsTheHostsOwnRequestToOpenTheChat` |
| U-2 token after logout, retry re-authorized | 47ca917 | `SessionAuthorizationTest.aLogoutDuringTheCredentialSaveHandsOutNoToken` ("token handed out after the logout"), `…aRetryAfterTheRenewalIsAuthorizedAgain` (retry sent with `Bearer new` while halted) |
| U-8 publication generation | f4d76ea | `ReceptionUnreadListenerTest.aLogoutByAListenerDuringADeliveryIsTheLastCountEveryoneSees` (B got 0, then the stale 1) |
| U-6 covered-sheet reorder kept | ed26a4f | `ChatPresentationTest.anUnrelatedResumeBeforeTheSheetsTaskResumesKeepsTheReorder` |
| U-7 record on disk before the sheet starts | 38dac11, 609e598 | `ChatPresentationTest.theSheetStartsOnceItsRecordIsOnDiskAndOnlyOverTheScreenThatAskedForIt` ("sheet started before its record was saved"); 609e598 is the lint fix (`UseKtx` suppressed on the one `commit()` whose result is needed) |
| V-1 activity looked up at call time | 3505a12 | `NotificationPermissionRequestTest.aReboundContextWrapperIsReadThroughItsCurrentActivity` (`MutableContextWrapper` rebound to an activity with the rationale flag: authorization stays `NOT_DETERMINED`) |
| V-2 chat keyed by persisted identity | 1f41901 | `ChatContentRestoreTest.theChatsLaunchersKeepTheirKeysInAProcessThatAndroidRestarted` (configure → logout → save → new process → `create(saved)`: 3 launcher keys before, 5 after) |
| W-1 `ReceptionState` split | 90e4ff0 | full suite, no behaviour change |

- U-1: `Reception.handlePushMessage(context, data, messageId: String?)` (new public overload, `apiDump`); the service
  passes `message.messageId`; the two-argument form reads `google.message_id` from the map (plugins' raw extras).
  SPEC, README, CHANGELOG. `firebase-messaging` is now also `testImplementation` (the test builds a real
  `RemoteMessage`); the POM is unchanged.
- U-5: `sh.reception.push` holds one list `messageIds` (64), independent of App ID and configure. Earlier per-namespace
  keys from unreleased builds are left unread.
- U-9: `ReceptionUnread.runningRefresh` (`@VisibleForTesting`) is awaited; the repeat is delivered while unread answers
  are gated, so a refresh it started would stay in flight and be seen.
- U-3/U-4: new `PendingChatOpen` (out of `ReceptionPush`): the waiting open records its source (`NOTIFICATION_TAP` or
  `HOST`); turning routing off advances a generation and drops only a tap's request; the posted open checks the
  generation for taps. A tap never replaces a pending host request.
- U-2: `settled()` and `token()`/`renewedToken()` check `invalidated` after every suspension and before returning;
  `persistPendingCredentials` checks after its save. `ReceptionAPI.request` and the event stream run
  `authorizeRequest(scopes, probing = probe)` before the retry send (the probe's permission carries over). SPEC notes
  this Android difference from the iOS actor.
- U-8: `notifyListeners` advances a publication counter and stops the outer delivery once a nested one started.
- U-6: `CoveredSheet` keeps the task through resumes in other tasks; clears on the target task's resume, when the sheet
  is gone or moved, and on dismissal.
- U-7: `present()` marks the presentation requested, then `SheetRecord.save` commits on the storage thread and the
  sheet starts only if the token is still current and the host is still the resumed activity (otherwise the
  presentation is dismissed); a failed commit is logged and the sheet starts anyway. `PresentationRecord.save` returns
  `commit()`'s result. Tests that read the started intent now call `ChatHarness.settle()`; the "closed in the same
  callback" test now asserts that nothing starts, and a new test covers a sheet closed after it started.
- V-2: `DeviceStore.chatIdentity` (scope with generation + chat revision); `ReceptionState.chatKey` (Compose state,
  set when the current session is prepared, null on every session change) replaces the in-memory `revision`.
  `ChatContent` draws only its background until the key exists. Robolectric unit tests now include Android resources
  (`unitTests.isIncludeAndroidResources`) so the chat can be composed.
- W-1: `ReceptionState` (165 lines: App ID, session, chat model, configure, `replaceSession`), `ReceptionResets`
  (logout/deletion/revocation/team reset), `ReceptionLifecycle` (foreground, background, session resumed).

## Build

`tool/release_check.sh` on the clean tree at 90e4ff0 (exit 0); it runs the library from clean (release build, lint,
unit tests, API check, no warning line), both R8 example builds and the POM check. Tail:

```
== Summary
  ok    clean working tree
  ok    version agreement
  ok    tool/sync_strings.py --check
  ok    library: clean, release build, lint, unit tests, API check
  ok    example: release build (R8) with Firebase and Billing
  ok    example: bare release build (R8) without Firebase and Billing
  ok    publishToMavenLocal and the POM
  ok    signing configuration
All checks passed.
```

(Signing skipped: no key on this machine.) Five `./gradlew :reception:test --rerun-tasks`:

```
run 1..5 rc=0 tests 470 failures 0   (debug and release variants)
```

## Live run (StrictMode `penaltyDeath`)

U-1, a real FCM reply through the example's `ReceptionMessagingService`, app in the background:

```
21:43:28.611 Push payload recognized, conversation …
21:43:28.638 Notification shown, conversation …
shared_prefs/sh.reception.push.xml: <string name="messageIds">["<fcm-id>"]</string>
```

The service path recorded the FCM message ID (before the fix it never reached the SDK). The same ID delivered again as
raw extras (`…PUSH --es google.message_id <that id>`):

```
21:43:40.077 ReceptionExample: handlePushMessage returned true
21:43:40.078 Push message ignored, handled already
```

U-7. Support → sheet, then Home, `am kill` (no pid), the card tapped in Recents:

```
21:45:05.765 Chat presentation requested
21:45:05.842 Chat presented                      (commit awaited, 77 ms)
21:45:39.350 Configured, SDK 1.0.0               (new process)
21:45:39.440 Chat presentation restored
topResumedActivity=…/sh.reception.ui.ReceptionChatActivity t122
```

(`fixes-9/u7-restored.png`).

V-2 could not be reproduced live: the system photo picker is translucent, so the sheet behind it is only paused and
has no saved state; when the process died (`run-as … kill -9`, `am kill` refuses a visible process) Android finished
the sheet (`resultTo=…ReceptionChatActivity t-1 f`) and restarted the process for MainActivity; the picked photo then
returned to no one, without a crash. The restore path is covered by `ChatContentRestoreTest`.

U-2, U-3, U-4, U-6, U-8 and V-1 are races or host setups (rebound context wrapper) the example cannot drive
deterministically; their unit tests are the evidence.

Crash buffer (cleared at the start): empty after the whole run; `FATAL EXCEPTION` 0; `StrictMode` 0 (`logcat -b all`
since 21:44:58).

## Deviations

1. `./gradlew :example:installDebug` without `ANDROID_SERIAL` also installed the example on `emulator-5556`
   (`reception_pixel8_rn`); nothing else ran there, and it was left as is. Every later command was pinned to 5554.
2. U-7: `present()` now answers true once the sheet is requested (it starts after the commit), so a missing
   `ReceptionChatActivity` in the merged manifest is logged after the commit instead of making `present()` false;
   automations then do not retry, which only matters for a broken manifest.
3. V-2: after a logout or team reset the chat draws only its background until the new session's store is loaded
   (a storage-thread read of a few milliseconds).
4. V-2 live, see above. Test-only dependencies: `firebase-messaging` (U-1) and Android resources in Robolectric (V-2).
5. `ChatPresenter.kt` is 205 lines (the record writes moved to `ui/SheetRecord.kt`).

## Open questions

1. Customer docs (`../reception/content/docs/android.md`, not edited here) still show
   `handlePushMessage(this, message.data)`; with that call a host's own service never deduplicates. They should pass
   `message.messageId` as the README now does.
2. U-7 makes the photo-picker/permission result path after process death depend on Android keeping a saved state for
   the sheet, which a translucent picker prevents (see V-2 live); nothing in the SDK can change that.
