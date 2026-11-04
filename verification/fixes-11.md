# Fixes 11: audit of fixes 10 (Y-1..Y-4, Z-1, Z-2)

Run 2026-11-04 on `main` from fd99a1c. Emulator `emulator-5554` (`reception_pixel8`, Android 16), local backend
`http://10.0.2.2:3123`, example debug build with StrictMode `penaltyDeath`. UI through the device CLI; adb for install,
logcat, `dumpsys` and the verification broadcasts.

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| Y-1 routing off cancels a tap waiting for its record | 3ffd8a2 | `ReceptionNotificationActivityTest.routingTurnedOffWhileATapsPresentationRecordIsSavedCancelsItButNotTheHostsOwnRequest` ("tap launched after routing turned off") |
| Y-4 no sheet over a finishing host | 5f53f16 | `ChatPresentationTest.aScreenThatFinishesWhileTheRecordIsSavedGetsNoSheet` ("sheet started over a finishing screen") |
| Y-2 asynchronous launch result for automations | e05c9af | `AutomationPresentationTest.aPresentationCancelledByAnotherActivityIsTriedAgainOverItWithinTheWindow` ("the open rule gave up after the cancellation"), `…aRecordSavedAfterTheWindowEndedStartsNoSheet` ("sheet started after the window"); old behaviour simulated in the adapter, since the old interface does not compile with the new test |
| Y-3 message ID committed before posting | 75934ac | `PushMessageIdsTest.aRecordedIdIsOnDiskBeforeRecordAnswers` ("not on disk when record answered"), `…aFailedWriteStillHandlesTheMessageOnce` |
| Z-2 SPEC: Compose `Dialog` override and Android 10 limit | 175fc01 | documentation |
| Example (debug): `…TRACK --el secondAfter <ms>` | fbb3540 | live run below |
| Z-1 flaky test | 8c4ada0 | see below |

- Y-1: `ChatPresenter.open/present(stillWanted)`: the presenter asks the request's check right before the sheet starts,
  after the record was saved. `PendingChatOpen` builds it per source: always true for the host's own requests, the
  routing generation captured when a tap asks for the open for taps (so off-then-on does not revive it). The waiting
  open and the drain/`handlePushNotification` path with a resumed activity (`PendingChatOpen.open`) both carry it.
- Y-4: the check right before starting also cancels when the host `isFinishing || isDestroyed` (it can still be the
  current activity because its pause has not arrived); the presentation is dismissed, which clears its record.
- Y-2: `present(stillWanted, completed)` reports once, on the main thread, whether the sheet started (or was brought to
  the front). `AutomationPresenter.present` is a `suspend` function over it. `openChatWhenSettled` passes
  "inside the 5 s window and same presentation revision" as the check, so a save that ends after the window starts
  nothing, and on a `false` result it polls again within the same window (a cancellation because another activity
  resumed is retried over the settled one). `openedChatAt` is cleared after a failed attempt.
- Y-3: `PushMessageIds.record` writes with `commit()` (it already runs on the storage thread, awaited before the main
  thread posts) and logs a failed commit; the message is still handled (the in-memory value keeps a repeat in this
  process out). Robolectric writes `apply()` to disk at once as well, so the test models the crash with a context whose
  preferences drop applied writes, then reads a fresh `SharedPreferences` instance (the `ContextImpl` cache cleared).
- Z-2: two sentences in SPEC.md's embedded-chat paragraph.
- CHANGELOG.md is unchanged: 1.0.0 is unreleased and these are corrections of unreleased behaviour, which its feature
  list already describes ("a repeated FCM message shows once", taps open the chat).

## Z-1: the flaky test

Twenty `./gradlew :reception:test --rerun-tasks --continue` at fd99a1c in a separate worktree, reports copied per run:
20 of 20 green. Twenty more at fbb3540 with seven `yes` processes as CPU load: run 6 failed

```
sh.reception.ui.ChatPresentationTest.aLateConfigureFollowedByALogoutDoesNotRestore (release variant), 5.114 s
java.lang.AssertionError: timed out, requests: [GET /v1/appearance?platform=android, POST /v1/devices, …]
  at sh.reception.viewmodel.ChatHarness.mainLoopUntil
```

(normally 0.1 s). Looping only that class under load reproduced it in runs 14 and 42. Thread dumps on the timeout
showed no blocked thread (storage thread idle, main thread in the test's loop); trace prints placed the restore's record
read inside `configure`:

```
TRACE test configure
TRACE ns seen <namespace>        restore resumes inline when configure sets the namespace
TRACE load on storage
TRACE loaded                     before the test's logout: restore accepted, sheet shown
TRACE test resetLocal            logout: ChatPresenter.dismiss() → sheet.dismiss() (animated)
STATE presented=false            sheet neither finishing nor presented
```

Cause (in the test): `Storage.run` is `withContext(storage)`. When the block finishes before the calling coroutine
reaches its suspension decision (the main thread preempted under load), `withContext` returns synchronously on the
caller's thread, so the restore could complete inside `configure()`, before the test's `resetLocal()`. The sheet was
then correctly restored and correctly dismissed by the logout, but that dismissal is the sheet's slide-out animation,
which never ends in Robolectric's paused looper, so the test's wait for "finishing or presented" timed out. Both
orders are correct product behaviour (SPEC: a logout still finishes a restored sheet); the test assumed one. The fix
holds the storage thread across configure and the logout, so the test always covers "logout while the restore reads
its record". The other restore tests log out before the sheet is recreated and are deterministic.

Verification of the fix (worktree at 8c4ada0): 60 consecutive loaded runs of
`:reception:testReleaseUnitTest --rerun-tasks --tests sh.reception.ui.ChatPresentationTest` green (it had failed in
runs 14 and 42 before), then ten consecutive `:reception:test --rerun-tasks`:

```
full run 1..10 rc=0 tests 500 (250 debug + 250 release) failing 0
```

## Build

RELEASE_CHECK

## Live run (StrictMode `penaltyDeath`)

Automation `android-phase-d-automation` (event `android_example_event`, `open_chat` + message) enabled in the local
database for the run and disabled after.

Y-2, Track event tapped with the example in front:

```
22:51:02.107 Automation message received for android_example_event
22:51:02.155 Unread refresh, 1 unread
22:51:02.198 Chat presented
```

(`fixes-11/y2-automation-open.png`). With another activity started in between (`am broadcast …TRACK --el
secondAfter <ms>`; the service answered about 70 ms after the broadcast, so 0–80 ms were swept). `secondAfter 70`
landed inside the record save:

```
22:52:40.655 ActivityTaskManager START …/.SecondActivity
22:52:40.657 Automation message received for android_example_event
22:52:40.683 Chat presentation cancelled, the screen changed before it started
22:52:40.683 Chat presentation cancelled
22:52:40.822 ActivityTaskManager START …/sh.reception.ui.ReceptionChatActivity
22:52:40.843 Chat presented
topResumedActivity=…/sh.reception.ui.ReceptionChatActivity t143     (SecondActivity's task)
```

The open rule retried and opened the chat over the settled second screen 160 ms later. With `secondAfter` 0–60 the
second screen resumed before the answer and the chat opened over it at once (e.g. 0 ms: SecondActivity START 15.286,
received 15.598, Chat presented 15.725, sheet on top in t139); with 80 ms the chat opened first and the second screen
covered it.

Y-1, a notification tap with routing turned off right after it (`…ROUTING --ez enabled false`, sent 0.3/0.4/0.5 s
after the device-CLI press started; the example's "Update app badge" switch off so the test notification stays):

```
0.4 s: 22:54:56.946 Chat opens once an activity is resumed
       22:54:57.207 Chat presentation requested
       22:54:57.313 Chat presentation cancelled, it was withdrawn before it started
       22:54:57.313 Chat presentation cancelled
       topResumedActivity=…/.MainActivity
0.3 s, 0.5 s: "Chat opens once an activity is resumed", then nothing (routing off before the resume dropped the tap)
```

Normal tap (routing on, app in the background):

```
22:54:24.880 Chat opens once an activity is resumed
22:54:25.186 Chat presentation requested
22:54:25.289 Chat presented
topResumedActivity=…/sh.reception.ui.ReceptionChatActivity t134
```

(`fixes-11/push-tap-chat.png`).

Logs: the example ran in one process for the whole run (pid 15441 from `Configured` at 22:50:52 to the end; StrictMode
`penaltyDeath` would have killed it); `logcat -b all`: 0 `StrictMode`, 0 `FATAL EXCEPTION`; crash buffer empty; no
`am_crash`/`am_proc_died` for the example in the events buffer (not cleared since 22:50:30).

## Deviations

1. `./gradlew :example:installDebug` ran once without `ANDROID_SERIAL` and installed the example on `emulator-5556`
   too ("Installed on 2 devices"). Every later adb call was pinned to `emulator-5554`.
2. The broadcasts (`…TRACK`, `…ROUTING`, `…PUSH`) went through adb; taps, Back, Home, the shade and screenshots
   through the device CLI. The routing broadcast was timed against a press, so only one of three attempts hit the
   record save; the unit test is the deterministic evidence.
3. A stale device-CLI session from fixes 10 (`t3-73b84b46fdd2d90bb72b1e71`, last event 22:16, no process) held
   `emulator-5554` and was closed.
4. The Y-2 test proving the old behaviour fails was run against a simulated old adapter (immediate `ChatPresenter.present()`),
   because the old `AutomationPresenter` interface does not compile with the new test.
5. The example on `emulator-5554` has "Update app badge" off and the app notification icon switch on.

## Open questions

1. Under load `ChatPresenter.dismiss` logs "Chat presentation cancelled, <reason>" and then "Chat presentation
   cancelled": the second line is `dismiss()`'s own; harmless, could be folded into one.
