# Fixes 8: audit of rounds 6 and 7 (T-1..T-8)

Run 2026-11-04 on `main` after 2dd0d73. Emulator `emulator-5554` (`reception_pixel8`, Android 16), local backend
`http://10.0.2.2:3123`, example debug build with StrictMode `penaltyDeath`. Team replies: the round-6 scratchpad script
(`createAgentMessage` as the first member, then `sendPush` over real FCM HTTP v1).

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| T-1 paid-access supersede | 6af509d | `PaidAccessTest.aCallerWaitingOnTheMainThreadFollowsAReadThatCancelsItsOwn` (cancellable read, awaiting caller on `Main.immediate`: `waited` null, the caller died with the cancellation) |
| T-2 token gate | 6ed0654 | `SessionAuthorizationTest.aTokenWaitsForCredentialsThatChangedDuringItsSave` ("token handed out before the newer credentials were saved") |
| T-3 zero-record Recents task | da1e274 | `ReceptionNotificationActivityTest.aTaskWhoseProcessDiedIsBroughtBackInsteadOfASecondOne` (0 and 2 records; 0 failed), `…aLeftoverTrampolineTaskIsNotRestoredAndTheAppStarts` |
| T-4 sheet covered in its own task | c9f1398 | `ChatPresentationTest.aSheetCoveredInItsOwnTaskWhileAnotherTaskIsInFrontIsReorderedWithinItsTask` |
| T-5 two embedded chats | 7f1a1d5 | `ChatLifecycleTest.twoChatsShareTheModelAndItStopsOnlyWhenTheLastLeaves`, `…theBackgroundStopsEveryChatThatSharesTheModelAndEachComesBack` (both fail) |
| T-6 restore after process death | bb29c86 | `ChatPresentationTest.aSheetOnScreenWhenTheProcessDiedIsRestored`, `…aSheetIsNotRestoredAfterALogoutInTheNewProcess`, `…aSupersededSheetIsNotRestored` |
| T-7 listener removed during delivery | b82e386 | `ReceptionUnreadListenerTest.aListenerRemovedByAnEarlierOneDuringADeliveryIsNotCalled` |
| T-8 order-independent requests | 4d6bc78 | `SessionAuthorizationTest` (revoked session, payload) |

- T-1: `start()` installs the new `Deferred` and only then cancels the previous one; a caller resuming inside
  `cancel()` finds the replacement.
- T-2: `settled()` loops until no registration is in flight and no credentials are unsaved.
- T-3: `AppTasks.restorable(context, excluding, trampoline)`: any task other than the trampoline's own whose base intent
  is one of the app's activities other than `ReceptionNotificationActivity`, with or without activity records.
  `AppTasks.withId` (live tasks only) stays for the presenter.
- T-4: `ui/CoveredSheet`: the sheet's task is moved to the front, and the first activity that resumes in it (the cover)
  starts the sheet with `REORDER_TO_FRONT`. See Deviations.
- T-5: `ChatModel.startedOwners`/`resumedOwners`; `ChatLifecycle` shows/activates on the first owner, hides/deactivates
  on the last; `backgrounded()` clears both sets and stops for all.
- T-6: a random token per presentation in the launch intent; `client/PresentationRecord` (SharedPreferences
  `sh.reception.presentation`, token + storage namespace, committed on the storage thread). A recreated sheet with an
  unknown token reads the record on the storage thread (content set afterwards, so its saved state, the lightbox
  included, restores) and is shown when the record names its token and the configured namespace and nothing presented
  or dismissed since. Dismissal, logout, deletion and reconfiguration clear the record; a finished sheet clears it; the
  first `configure` of a process no longer cancels anything.
- T-8: grep for `requests[`, `takeRequest`, ordered `summary()`/`map` over all tests. Only the two
  `SessionAuthorizationTest` cases that call `ReceptionState.configure` can see configure's own reads (automations,
  appearance, paywalls); both now keep only the session's paths. The others run without configure (`takeRequest` as a
  wait, sequential uploads, the attachment loader) or already select by path (`harness.requests(method, path)`,
  `LaunchRegistrationTest`).
- SPEC: plugins call `setNotificationRoutingEnabled(false)` before `configure` (the flag is in memory; a tap before
  configure would otherwise open the chat); covered-sheet path; presentation tokens and restore; shared model for
  several chats; listener removal during a delivery; Recents tasks without records.

## Build

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all --rerun-tasks
BUILD SUCCESSFUL in 1m 56s
118 actionable tasks: 118 executed
(grep "^w: |warning:|deprecat" → 0; lint-results-debug.txt "No issues found."; 422 unit tests (both variants), 0 failures)
$ tool/sync_strings.py --check
strings up to date (49 files)
$ for i in 1..5: ./gradlew :reception:test --rerun-tasks
run 1..5 rc=0 tests 422 failures 0
```

## Live run (StrictMode `penaltyDeath`)

The example gained debug-only hooks: `VerificationReceiver` `…COVER` (starts the second screen inside the task whose top
is the chat sheet, via `AppTask.startActivity`) and `…LOGOUT`; the embedded screen has "Add/Remove a second chat" in
debug builds.

T-4. Support → sheet; `…COVER`; `am start -n …/.SecondActivity` (new task #103 in front). Before:

```
Task #103: SecondActivity
Task #102: Hist #2 SecondActivity, #1 ReceptionChatActivity, #0 MainActivity
```

Support on #103:

```
20:17:44.453 Chat brought to the front with its task
20:17:44.545 Chat brought to the front within its task
Task #102 topResumedActivity=ReceptionChatActivity: Hist #2 ReceptionChatActivity, #1 SecondActivity, #0 MainActivity
```

(`t4-after.png`). The first attempt with `AppTask.startActivity(REORDER_TO_FRONT)` stacked a second sheet instead:
`Chat brought to the front with its task` → `Chat presentation cancelled before it appeared`, cover still on top.

T-6. A photo sent and opened in the lightbox (`t6-lightbox-before.png`), Home, `am kill` (no pid), the card tapped in
Recents:

```
20:20:11.262 Configured, SDK 1.0.0
20:20:11.560 Chat presentation restored
topResumedActivity=ReceptionChatActivity t104
```

The lightbox is open again over the chat (`t6-lightbox-restored.png`). Negative case: lightbox closed, Home, `am kill`,
`…LOGOUT` (starts the process: `Configured` → `Logged out, session revocation queued`), the card tapped:

```
20:20:45.298 Chat presentation not restored, the chat was closed or reset since
Task #104: Hist #0 MainActivity only
```

(`t6-after-logout.png`).

T-3. A new conversation (push registered, one message), Home, `am force-stop`: Recents keeps Task #105 (MainActivity)
with no activity records (`dumpsys activity activities` lists nothing for t105). The package un-stopped by a debug
broadcast, `am kill`, a real FCM reply → `Notification shown`. `am kill` again, tap in the shade:

```
20:22:48.631 START … cmp=…/sh.reception.ui.ReceptionNotificationActivity
20:22:49.455 Configured, SDK 1.0.0
20:22:49.500 Chat opens once an activity is resumed
20:22:49.510 START {act=MAIN cat=[DEFAULT,LAUNCHER] flg=0x10100000 …}   (the task restored from history, not a new launch)
20:22:50.720 Chat presented
Recents: #0 Task #105 …/.MainActivity, then the launcher and other apps; one card
Task #105: Hist #1 ReceptionChatActivity, #0 MainActivity
```

The 2-record case was verified in round 6 (R-1) and is unchanged (also in the unit test).

T-5. Embedded chat, "Add a second chat": both chats visible (`t5-two-chats.png`). The app's SSE connection to the
backend (`/proc/net/tcp6`, uid 10224, established): `:9810` with one chat, with two, and after "Remove the second
chat" still `:9810` (the remaining chat keeps its stream, `t5-after-removal.png`); Back (the last chat leaves) closes it.
`isChatOpen` stayed true throughout (a reply in between: `Notification not shown, chat open`). The reply itself does
not appear live: the script runs in its own process, so the dev server's in-process event bus never sees it, also with
a single chat; it appears on the next load.

StrictMode or `FATAL EXCEPTION` lines (`logcat -b all`): 0; the crash buffer, cleared at install, empty after the whole
run (with `penaltyDeath`, any violation would be there).

## Deviations

1. T-4: `AppTask.startActivity` ignores `FLAG_ACTIVITY_REORDER_TO_FRONT` (live: a second sheet, rejected by the
   presenter). The task moves to the front with `moveToFront()`, and the reorder is started from the first activity
   that resumes in that task.
2. T-6: a restored sheet needs the SDK configured before Android creates it (`Application.onCreate`). Flutter and React
   Native configure from Dart/JS after their activity starts, so there the restored sheet finishes (screenshots of this
   run are in `verification/fixes-8/`).
3. T-6: the first `configure` of a process no longer calls `ChatPresenter.dismiss()` (nothing can be presented before
   it); reconfiguration still does.
4. T-5: the live check is the stream socket, not a delivered event (see above).

## Open questions

1. Flutter/React Native sheet restore (deviation 2): the plugins could call `Reception.configure` from the native side
   before the activity's `onCreate`, or the restored sheet could wait for configure for a short time.
2. `ChatPresenter.kt` is at 200 lines; the sheet gesture rules (`isSheetHandle`, `locksSheet`) could move to the sheet.
