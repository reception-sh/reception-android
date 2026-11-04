# Fixes 13: audit of fixes 12 (AC-1..AC-4)

Run 2026-11-05 on `main` from 728a9ce. No emulator this round (`emulator-5556` belongs to another agent, `emulator-5554`
was left alone); the unit tests are the evidence. Each test was run against the old main sources (`git stash` of
`reception/src/main` only) and failed as listed, then passed with the fix.

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| AC-1 `closeChat()` withdraws a pending automation open | a7101fd | `AutomationPresentationTest.aChatTheHostClosedWhileTheRecordIsSavedIsNotOpenedAgain` ("the automation reopened the chat the host closed expected null, but was: Intent { cmp=…ReceptionChatActivity }"); the screen-change retry stays covered by `…aPresentationCancelledByAnotherActivityIsTriedAgainOverItWithinTheWindow` |
| AC-2 a host request joins a tap's presentation waiting for its record | 5744b9d | `ReceptionNotificationActivityTest.aHostRequestDuringATapsRecordSaveKeepsThePresentationThroughARoutingDisable` ("host request 0 was dropped expected:<…ReceptionChatActivity> but was:<null>") |
| AC-3 guard for a Compose `Dialog` whose mode already matched | 0fd9d5f | `ComposeDialogModeGuardTest.aWindowThatAlreadyAdjustsNothingKeepsItThroughAParameterChange` (`sdk = [30]`, "expected:<48> but was:<16>") |
| AC-4 same-callback sheet test holds storage | c60dea0 | test-only |

- AC-1: `ReceptionState.closeRevision`, advanced by `Reception.closeChat()` (`ChatPresenter.close()`, after its
  configure check, whether or not a sheet was on screen). `Automations.openChatWhenSettled` captures it on entry and
  its `wanted` check requires both the presentation revision and the close revision unchanged, so a presentation that
  `closeChat()` cancels during the record save completes `false` and the loop ends ("Chat not opened, the app closed
  the chat"). `presentationRevision` itself is not advanced: `deleteData()` uses it as its generation, and a
  `closeChat()` during a deletion must not skip the local reset. Internal dismissals (`dismiss()` from a cancelled
  start, a screen change) do not touch the close revision, so the retry over a newly resumed activity is unchanged.
  SPEC.md (Automations) says so.
- AC-2: `ui/PendingPresentation` holds the presentation whose record is being saved. A `present` while it is the
  current presentation joins it (its check and its completion) instead of being dropped as "already open"; right before
  the sheet starts, the joined presentation is wanted while any joined request wants it (every check runs), and every
  joined request receives the outcome. So a host `openChat()` or `handlePushNotification(openChat = true)` during a
  tap's save makes it host-wanted, a routing disable no longer cancels it, and exactly one sheet starts. A presentation
  cancelled by a dismissal is no longer current, so a later request starts its own presentation; the cancelled one's
  callback still finishes its own joined requests (`false`). The test covers both host calls. SPEC.md (presentation
  record paragraph) says so.
- AC-3: `EmbeddedKeyboardMode.acquire` separates the desired mode (`target`) from the written one (`installed`, only
  when different, still what `release` restores); `ComposeDialogModeGuard` is installed for every Compose `Dialog`
  below API 31 with the desired mode. The test sets `adjustNothing` on the dialog window before the chat appears
  (`decorFitsSystemWindows = false`, so Compose's update writes `adjustResize`), shows the chat, changes the dialog's
  parameters, and expects `adjustNothing`; after the chat leaves, Compose's own `adjustResize` stays. SPEC.md (embedded
  chat paragraph) says so.
- AC-4: `ChatPresentationTest.aSheetClosedInTheSameCallbackAsItsOpeningIsNotStarted` now starts the harness, resumes
  the host, holds the storage thread across `open()` and `close()` and releases it before settling (the hold must come
  after `configure`, which needs the storage thread). Scan of the suite for the same assumption: every other call of
  `ChatPresenter.open/present`, `Reception.openChat`, `handlePushNotification(openChat = true)` and
  `openChatWhenSettled` either holds the storage thread (`theSheetStarts…`, `aScreenThatFinishes…`,
  `routingTurnedOff…WhileATapsPresentationRecordIsSaved…`, the new AC-1/AC-2 tests, `AutomationPresentationTest`),
  settles before acting (the restore, cover and reorder tests, `aPluginsActivity…`), acts before the open is posted
  (`routingTurnedOffAfterTheResumeButBeforeTheOpenRunsCancelsIt`), or does not depend on the order (host requests are
  always wanted; `AutomationsTest` uses a fake presenter). The push tests (`ReceptionPushMessageTest`,
  `PushMessageIdsAcrossConfigureTest`) settle storage before asserting; `NotificationSmallIconTest` holds it. No other
  test changed.

## Build

Five `./gradlew :reception:test --rerun-tasks --continue` at c60dea0, reports copied per run:

```
run 1 rc=0 tests 520 failures 0
run 2 rc=0 tests 520 failures 0
run 3 rc=0 tests 520 failures 0
run 4 rc=0 tests 520 failures 0
run 5 rc=0 tests 520 failures 0
```

(514 before, plus the three new tests in the debug and release variants.)

`tool/release_check.sh` runs on the clean tree after this file is committed; its summary is in the final report and
STATUS.md.

## Deviations

1. No live run: no emulator was to be used this round. AC-1 and AC-2 are timing races that Robolectric reproduces
   deterministically with a held storage thread; AC-3 only exists below Android 12.
2. AC-2 joins rather than queues: a host request that joins a presentation over an activity that then stops being the
   resumed one is cancelled with it (as before this change, where it was dropped as "already presented").
3. CHANGELOG.md is unchanged: 1.0.0 is unreleased and these correct unreleased behaviour.

## Open questions

None from this audit. The open questions of fixes 12 (Compose `Dialog` detection by class name; a host that wants
exactly `adjustResize` or the theme's mode in a Compose `Dialog` while a chat is shown) stand as documented limits.
