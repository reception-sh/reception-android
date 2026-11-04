# Fixes 6: audit of Phase D (P-1..P-7), API additions (A-1..A-3), open questions (Q-1, Q-2), Flutter finding (R-1)

Run 2026-11-04 on `main` after 89cedab. Emulator `emulator-5554` (`reception_pixel8`, Android 16, Gboard), local
backend `http://10.0.2.2:3123` (branch `android-push`), example debug build with StrictMode `penaltyDeath`. Team
replies: a scratchpad script run with the backend's environment (`createAgentMessage` as the first member, then
`sendPush` over real FCM HTTP v1); `pushPrompt` set with a second one and set back to `OFF` afterwards.

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| P-1, P-2 paid access | e8b0038 | `PaidAccessTest.aFailedReadAfterASuccessfulOneMakesPaidAccessUnknownAndOmitsIt` (`subscribed` stays true and is sent), `…onlyTheLatestReadPublishesAndAWaitingCallerGetsItsAnswer` (the older read, finishing last, publishes false) |
| P-3 unbadged channel | 2e5517b | `PushNotificationsTest.aHostThatKeepsTheBadgePostsToAChannelWithoutBadges` |
| P-6 collision-free keys | 311d5c5 | `PushNotificationsTest.conversationsWhoseIdsShareAHashKeepTheirOwnNotificationAndTap` (fails without the intent data: one PendingIntent) |
| P-7 channel language before configure | 766b463 | `AppLocaleTest.beforeConfigureTheSuppliedContextNamesTheAppsLanguage` |
| P-4 dismissed dialog | 5231526 | `ChatPushPromptTest.aDismissedDialogHidesTheCardOnlyUntilTheNextLaunch` (DENIED after the relaunch), `…aDenialIsKept` |
| P-5 observable `pushActive` | c83d3bf | `ChatPushPromptTest.theStatusLineFollowsALaterServerAnswer` (derived slot stays None) |
| A-1 unread listeners | e9050e9 | `ReceptionUnreadListenerTest` |
| A-2 `ReceptionColor` | 38c7f97 | `HostAppearanceReportTest.aLightAndDarkPairIsReportedAsBothVariantsAndDrawnPerMode`, existing report/resolution tests on `ReceptionColor` |
| A-3 drawable resources | f809910 | `RemoteAppearanceTest` (resource close image, one close image), `HostAppearanceReportTest` (resource icons reported as images) |
| Q-1 covered sheet | 666c72f, d1e97c6 | `ChatPresentationTest.aSheetCoveredByAnotherActivityIsBroughtToTheFront` (same task) |
| Q-2 lightbox through rotation | df0a43c | live |
| R-1 task affinity | c431f5d | `ReceptionNotificationActivityTest.aTaskWhoseProcessDiedIsBroughtBackInsteadOfASecondOne` |

- P-1/P-2: `PaidAccess` keeps one `Deferred` read with a generation; a new read cancels the previous one, only the
  latest publishes (also when a read ignores cancellation), `refreshNow` follows a superseding read and returns its
  answer, and a failure (null) clears `FacadeState.subscribed`, so the payload omits it. `onChange` fires only for a
  known value. One commit for both, because both are the same twenty lines.
- P-3: `reception_replies` (badges) and `reception_replies_unbadged` (`setShowBadge(false)`), both "Support replies";
  only the channel in use is created.
- P-6: tag `reception:<conversationId>`, id 1; content intent data `reception://conversation/<id>`; `cancelAll` matches
  the tag prefix.
- P-7: the post waits for `AppLocale.prepared(context)` (the declared locales read on the storage thread) and names the
  channel through `ReceptionLocalization.language(applicationContext)`.
- P-4: after the dialog, `NotificationPermission.isEstablished` (granted, `areNotificationsEnabled`, or the rationale
  flag) decides whether the answer is stored; a dismissal logs `Notification prompt dismissed`.
- P-5: `DeviceStore.pushActiveState` (Compose state) loaded in `open()`, written by the `pushActive` setter, cleared on
  chat reset and deletion.
- A-2: `ReceptionColor` (`ReceptionColor.kt`): a resource is read once with `createConfigurationContext` (night no/yes)
  through the application context and cached on the instance; before configure it counts as unset.
- Q-1: a stopped sheet in the host's task comes back with `FLAG_ACTIVITY_REORDER_TO_FRONT`; in another task of the app
  its task is moved to the front (`client/AppTasks`, shared with the trampoline). See Deviations.
- R-1: the trampoline, as a new task's root, moves another app task with activities to the front and finishes with
  `finishAndRemoveTask()`; the launcher intent only starts when none exists. The example's `MainActivity` now declares
  `android:taskAffinity=""` permanently (Flutter's template), and debug builds export `SecondActivity`.

## Build

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all --rerun-tasks
BUILD SUCCESSFUL in 58s
116 actionable tasks: 116 executed
(grep "w: |warning:|deprecat" → 0; lint "No issues found."; unit tests 392 (both variants), 0 failures)
$ tool/sync_strings.py --check
strings up to date (49 files)
```

## Live run (StrictMode `penaltyDeath`)

A-2, after Delete data (`a2-light.png`, `a2-light-accent.png`, `a2-dark-accent.png`, `a2-dark-icon.png`): system light
mode draws the welcome icon in `#2E7D32` (resource) and the sent bubble in `#5B4FE0`; "Chat appearance: Dark" draws
`#A5D6A7` (the `values-night` variant) and `#B0A8FF`. The device's report in the database:

```
{"colors":{"accent":{"dark":"#B0A8FF","light":"#5B4FE0"}},"welcomeIcon":{"color":{"dark":"#A5D6A7","light":"#2E7D32"}}}
```

Q-1, chat open, `adb shell am start -n …/.SecondActivity` (it ran in its own task t90/t92 because the launcher's
affinity is empty), then Support on the second screen:

```
19:40:09.654 Chat presentation requested
19:40:09.658 Chat brought to the front with its task      topResumedActivity …ReceptionChatActivity t91
```

and with the second screen on top again, a real FCM reply tapped in the shade:

```
19:40:44.854 Chat opens once an activity is resumed
19:40:44.902 Chat brought to the front with its task      topResumedActivity …ReceptionChatActivity t91
```

The same-task path (`REORDER_TO_FRONT`) is covered by the unit test only: from the example there is no way to put an
activity of the chat's own task above the sheet.

P-6 (same reply): `NotificationRecord(… id=1 tag=reception:cmw2gn0zu008t00n40c4nc4mv … channel=reception_replies …
color=0xff5b4fe0 … groupKey=cmw2gn0zu008t00n40c4nc4mv)`, content intent `dat=reception://conversation/...`.

Q-2: a sent photo opened in the lightbox, `orientation landscape-left`: the lightbox is still open in landscape
(`q2-lightbox-portrait.png`, `q2-lightbox-landscape.png`).

R-1: Recents held task #91 (MainActivity, affinity "") and an emptied task #92. Home, `am kill` (no pid), a reply:
`Start proc … for broadcast FirebaseInstanceIdReceiver` → `Configured` → `Notification shown`. Tap:

```
19:43:14.985 START {dat=reception://conversation/… cmp=…/sh.reception.ui.ReceptionNotificationActivity}
19:43:15.115 Chat opens once an activity is resumed
19:43:16.347 START {cmp=…/sh.reception.ui.ReceptionChatActivity}   (from the restored MainActivity, sr=19928311)
19:43:16.374 Chat presented
Recents: #0 Task #91 I=…/.MainActivity, then the launcher and other apps; no second card (#92 was removed too)
Hist: t91 ReceptionChatActivity, MainActivity
```

No launcher intent was started (`r1-after-tap.png`).

P-3: "Update app badge" off, reply: the record is on `channel=reception_replies_unbadged`; `dumpsys notification` lists
`reception_replies_unbadged` (`mName=Support replies`, `mShowBadge=false`) beside `reception_replies` (`mShowBadge=true`).

P-4, fresh install without the permission, `pushPrompt=REPLIES`: send → card; Continue → system dialog
(`p4-dialog.png`); Back → `Notification prompt dismissed`, card hidden, `POST_NOTIFICATIONS: granted=false` without
`USER_SET`. Relaunch, open the chat: the card is back (`p4-card-after-relaunch.png`). Continue → Allow →
`Notification prompt answered, granted` → `Device updated` → the status line (`p5-status-after-grant.png`, P-5: it
appeared with the update's answer without reopening the chat).

Not live: P-1/P-2 (no Google account on the emulator, billing answers `BILLING_UNAVAILABLE`), P-7 (the example
configures in `Application.onCreate`, so a push never arrives before configure), A-1 and A-3 (unit tests).

StrictMode or `FATAL EXCEPTION` lines (`logcat -b all`) in every dump and the crash buffer: 0; the process restarted
only for the kills and reinstalls above.

## Deviations

1. Q-1: `REORDER_TO_FRONT` alone did not cover the live case. With the launcher's empty task affinity, an activity
   started with `NEW_TASK` (here by `am start`) runs in its own task, and reordering only works inside the starting
   task (a new sheet without a presentation number would have finished at once). A covered sheet in another task of
   the app is brought back with its task (`AppTask.moveToFront`, no permission needed).
2. P-1 and P-2 share one commit (same function, same tests).
3. P-6 uses the tag variant (no stored counter), id fixed at 1.
4. The example keeps `taskAffinity=""` permanently and exports `SecondActivity` in debug builds only.

## Open questions

1. `Reception.kt` is 432 lines (the nested public `Appearance` grew by the resource members). Splitting needs the public
   nested types moved out of the object, which changes the API shape (`Reception.Appearance`).
2. A Java host cannot call `ReceptionColor(Color)` (Compose `Color` is a value class); Java hosts use
   `ReceptionColor.resource`. An `argb(light: Int, dark: Int)` factory would close that gap if wanted.
3. One unit run (a filtered subset) failed `SessionAuthorizationTest.registersWithTheAndroidPayloadAndSendsThePushTokenWithTheUpdate`
   with an empty request body; it passed in every later run, alone and in the full suite. Same family as the
   "missing stream request" noted in Phase C2.
