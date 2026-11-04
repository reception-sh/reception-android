# Fixes 5: audit of C2 / fixes 4 (K-1..K-6) and the decided Phase D items (N-1..N-4)

Run 2026-11-04 on `main` after 9e0887c. Emulator `emulator-5554` (`reception_pixel8`, Android 16), local backend
`http://10.0.2.2:3123` (branch `android-push`), example debug build with StrictMode `penaltyDeath`. Team replies:
the Phase D scratchpad script (`createAgentMessage` as the first member, then `sendPush` over real FCM HTTP v1).

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| K-1 Home after a rotation under the picker | 572bf24 | `ChatLifecycleTest.homeAfterARotationUnderThePickerStopsTheChat` (`isChatActive` still true) |
| K-2 one hand-out gate | e539084 | `SessionAuthorizationTest.aRenewalThatEndsWithUnsavedCredentialsHandsOutNoToken` (`[fresh, fresh]` instead of `secure_storage_unavailable` twice) |
| — revoked-session test | 6141d8d | failed on 9e0887c already: configure's subscription check reads `GET /v1/automations` |
| K-3 `onPaywall` as Compose state | f3b6fe9 | `ReceptionPaywallsTest.cardsFollowTheHandlerAndTheAvailablePaywalls` (derived state stale after assigning the handler) |
| K-4 lowercase schemes | b540250 | `LinkPolicyTest.mixedCaseSchemesAreQueriedInLowercase`, `LinkOpenerTest` (`MyApp://…` launched unchanged); `LinkPolicyHandlersTest` now expects `MYAPP://` to match the `myapp` filter |
| K-5 text links share the card route | c1813fb | `ChatLinksTest.aTextLinkTheHostClaimsClosesTheChatFirstWithoutEventOrReceipt` (closed 0 times) |
| K-6 lightbox touch slop | 567bc21 | live (below) |
| N-1 notification trampoline | 7fe958c | `ReceptionNotificationActivityTest` (cold start, existing task, repeat, another notification), `PushNotificationsTest` (content intent targets the trampoline) |
| N-2 `noteCurrentActivity` | 69d00ba | `ReceptionNotificationActivityTest.aPluginsActivityPresentsTheChatBeforeItsNextResume` |
| N-3 `onNewToken` staging | in 7fe958c | SPEC.md only |
| N-4 Play purchases | — | STATUS.md only |
| `Reception.kt`, `Automations.kt` | f8c8700 | — |
| Example second screen | 43d74f5 | — |

- K-1: the lifecycle bookkeeping moved from the composable into `ui/ChatLifecycle` (testable without a composition).
  The process `ON_STOP` now calls `backgrounded()`: `deactivate(keepsPolling = false)` unconditionally, contrast
  stop when started, and `markVisible(false)` plus unread refresh when the model is visible.
- K-2: `settled()` (no registration in flight, `persistPendingCredentials()`, repeat) runs before every token leaves
  `SessionAuthorization`: both fast paths, both shared-refresh awaits, the own renewal and the registration.
- K-3: `ReceptionPaywalls.onPaywall` is `mutableStateOf`; `available` already was.
- K-4: `LinkPolicy.normalizedScheme` (`Uri.normalizeScheme` semantics) in `isHostScheme` (cache key and query) and
  in all three `LinkOpener` launches. `isAllowed` stays case-insensitive.
- K-5: `openLink` and `openTextLink` share `follow(url, messageId?)`; a null message id records no receipt, and only
  `openLink` emits `LinkOpened`. SPEC.md "Paywall cards, screens, links" says so.
- K-6: the transform detector accumulates zoom and pan until `viewConfiguration.touchSlop` is passed, acts only after
  that and consumes only changed positions, so a tap's up event reaches `detectTapGestures`.
- N-1: see "Deviations". The launcher-extras path, `onNewIntent` listeners and `activity.intent` reads are gone;
  `ApplicationLifecycle` no longer forwards created/destroyed activities to push.
- Splits: `Reception.kt` 413 → 397 lines; the push facade bodies moved to `ReceptionPush.receive/route`, the rest are
  one-line delegations plus the nested public appearance types. `Automations.kt` 224 → 165 lines plus
  `SubscriptionEvents.kt` 76 (Play subscription checks and paid-access changes; uses `Automations.deliver/listens`).

## Build

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all --rerun-tasks
BUILD SUCCESSFUL in 1m 6s
116 actionable tasks: 116 executed
(grep "w: |warning:|deprecat" → 0; lint "No issues found."; unit tests 368 (both variants), 0 failures)
$ tool/sync_strings.py --check
strings up to date (49 files)
```

## Live run (StrictMode `penaltyDeath`)

Notification taps (N-1), each after a real FCM reply:

```
A  app running, SecondActivity on top of MainActivity
   18:07:40.310 Notification shown
   18:07:48.099 Chat opens once an activity is resumed
   18:07:48.185 Chat presented                      task: MainActivity, SecondActivity, ReceptionChatActivity
B  app in the background (Home), SecondActivity on top
   START …/sh.reception.ui.ReceptionNotificationActivity → Chat opens once an activity is resumed → Chat presented
   task again MainActivity, SecondActivity, ReceptionChatActivity
C1 process killed (am kill), task kept: Configured → Chat opens once an activity is resumed → Chat presented,
   SecondActivity restored under the chat
C2 no task (swiped from Recents), process killed
   START …ReceptionNotificationActivity → Configured → Chat opens once an activity is resumed
   START {act=MAIN cat=[LAUNCHER] flg=0x10000000 …} → Chat presented    task: MainActivity, ReceptionChatActivity
   afterwards a launcher start (monkey, LAUNCHER category) reused that task, no second MainActivity
```

`n1-a-second-activity.png` (chat over the second screen), `n1-recents-after-cold-tap.png` (the app's task in Recents
after a cold-start tap, with the final manifest).

Repeated tap: the shade collapses on the first tap and `autoCancel` removes the notification, so a second tap of the
same notification could not be produced (two `adb shell input tap` 80 ms apart: one trampoline start in the log). The
repeat and "another notification opens again" are covered by `ReceptionNotificationActivityTest`; live, every new
notification (A, A2, B, C1–C4) opened the chat again.

K-1: chat open, photo picker up, `orientation landscape-left` (the chat recreated under the picker, picker RESUMED,
chat PAUSED), Home. Backend log: the open stream closed (`GET /v1/conversation/events 200`) and no events request
followed in the next 75 s. Returning from Recents, the picker was still up (the path fixes-4 could not repeat);
picking the photo and Done sent it (`Message sent`, `Event ImageSent`).

K-6: the device CLI's `press --count 2` did not land as a double tap (no change in 2 tries), so the double taps are
`adb shell 'input tap 540 1200 & sleep 0.12; input tap 540 1200'`: 7 of 7 toggled 1× ↔ 3×
(`k6-lightbox-1x.png`, `k6-lightbox-3x.png`).

StrictMode or `FATAL EXCEPTION` lines (`logcat -b all`) and crash buffer lines: 0.

## Deviations

1. N-1: the trampoline starts the launcher intent only when it is its task's root (no task of the app existed). The
   first live run started it every time and stacked a second MainActivity on top of SecondActivity: inside an
   existing task, `NEW_TASK` without `RESET_TASK_IF_NEEDED` adds the activity whenever the task's root intent
   differs from the launcher intent (here the task was started by `am start -n`). The tap already brings the
   existing task forward, so finishing reveals its top activity.
2. N-1: no `excludeFromRecents`. On a cold-start tap the trampoline is the task's root; its intent stays the task's
   base intent with `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`, and the whole app task vanished from Recents (verified,
   `dumpsys`: `intent={flg=0x10800000 … ReceptionNotificationActivity}`). `FLAG_ACTIVITY_CLEAR_TASK` on the launch did
   not replace that base intent. Without the attribute the task shows normally; `noHistory` keeps the trampoline out
   of history, and a Recents relaunch of an emptied task replays the trampoline with
   `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, which routes nothing and starts the app.
3. N-1/N-2 tests drive the trampoline and the resume through `ApplicationLifecycle.noteCurrentActivity`, the N-2 hook.

## Open questions

1. A sheet buried under another app activity still counts as presented (`ChatPresenter.isPresented` holds the sheet
   reference), so `openChat()` and a notification tap do nothing until that activity is closed. Seen live when the
   device CLI's `open` stacked a MainActivity above the chat. Bringing the sheet forward (`REORDER_TO_FRONT`) or
   treating a stopped sheet as not presented would fix it; iOS has no equivalent case.
2. The lightbox closes on rotation (its open state is not saved), as before this round.
