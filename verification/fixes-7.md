# Fixes 7: plugin routing hook (S7-1), message-id idempotency (S7-2), `ReceptionColor.argb` (S7-3), flaky payload test (S7-4), AGENTS.md exception (S7-5)

Run 2026-11-04 on `main` after 006ae5d. Emulator `emulator-5554` (`reception_pixel8`, Android 16), local backend
`http://10.0.2.2:3123` (branch `android-push`), example debug build with StrictMode `penaltyDeath`. Team replies: a
scratchpad script run with the backend's environment (`createAgentMessage` as the first member, then `sendPush` over
real FCM HTTP v1). Plugin calls and duplicate deliveries come from the example's debug-only `VerificationReceiver`
(`adb shell am broadcast`), which this run added.

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| S7-1 routing hook | 9915fea | `ReceptionNotificationActivityTest.turningRoutingOffDiscardsTheTapThatWaitsForConfigure` (the drain opens the chat), `…aTapWhileRoutingIsOffOnlyBringsTheAppForwardAndTurningItOnRoutesTheNext` (the chat opens on the disabled tap); both checked against a no-op hook |
| S7-2 message ids | c347965 | `ReceptionPushMessageTest.aRepeatedMessageIdIsHandledOnce` (second notification and unread refresh; checked with the lookup disabled) |
| S7-3 `argb` | ff9c38c | `ReceptionColorTest` (variants), `ReceptionColorJavaTest` (both overloads callable from Java) |
| S7-4 flaky test | 8f0278f | `SessionAuthorizationTest.registersWithTheAndroidPayloadAndSendsThePushTokenWithTheUpdate` |
| S7-5 AGENTS.md | 006ae5d | — |

- S7-1: `Reception.setNotificationRoutingEnabled(enabled)` (`@ReceptionInternalApi`, `@JvmStatic`, main thread) →
  `ReceptionPush.setRoutingEnabled`. Off: the drain job is cancelled, `NotificationRouting.discardPendingTap()`, a
  routed tap still waiting for a resumed activity (`opensWhenResumed`) is dropped, `configured()` starts no drain, and
  `notificationTapped` logs `Notification tap not routed, the app handles notifications` and records nothing (no
  pending tap before configure either). The trampoline is unchanged, so the app still comes forward (task restored or
  launcher started). The host's own `handlePushNotification(data, openChat)` stays routed.
- S7-2: `client/PushMessageIds` keeps the last 64 `google.message_id` values as a JSON list in SharedPreferences
  `sh.reception.push`, key `<namespace>.messageIds` (the App ID's storage namespace, `ReceptionState.namespace`) or
  `global.messageIds` before configure; outside the session namespace, so logout and deletion keep it. The check runs
  on the storage thread (one thread in submission order, so a repeat always sees the first record), then the main
  thread handles a new message. `handlePushMessage` still recognizes the payload synchronously and returns true for a
  repeat. A message without the field is always handled. Unreadable stored ids count as none.
- S7-4, the cause: configure starts the subscription check (`GET /v1/automations`, no token) on its own connection, in
  parallel with the registration. The test took `requests[0]` as the registration and `requests[1]` as the update;
  when the GET reached MockWebServer first, `requests[0].body` was empty. Reproduced deterministically by letting the
  automations request arrive before `token()`: the old assertions fail with exactly that
  `JsonDecodingException … JSON input: ` (empty), the new ones pass. The test now filters out the automations read,
  asserts the remaining order (`POST /v1/devices`, `PATCH /v1/devices/me`) and reads the bodies from those. Not the
  C2 "missing stream request" (that was the activation test's self-contradicting server, fixed in Fixes 4 H-2).

## Build

```
$ for i in 1..5: ./gradlew :reception:test --rerun-tasks
run 1 rc=0  tests 402 failures 0
run 2 rc=0  tests 402 failures 0
run 3 rc=0  tests 402 failures 0
run 4 rc=0  tests 402 failures 0
run 5 rc=0  tests 402 failures 0
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all
BUILD SUCCESSFUL in 17s
118 actionable tasks: 25 executed, 93 up-to-date
(grep "w: |warning:|deprecat" → 0; lint "No issues found.")
$ tool/sync_strings.py --check
strings up to date (49 files)
```

## Live run (StrictMode `penaltyDeath`)

S7-2, app in the foreground, the same data three times with `google.message_id` `0:fake-s72-1`, `0:fake-s72-1`,
`0:fake-s72-2`:

```
19:59:45.642 I/ReceptionExample handlePushMessage returned true
19:59:45.645 D/Reception Push payload recognized, conversation cmw2gn0zu008t00n40c4nc4mv
19:59:45.693 D/Reception Notification shown, conversation cmw2gn0zu008t00n40c4nc4mv
19:59:45.694 I/ReceptionExample handlePushMessage returned true
19:59:45.694 D/Reception Push message ignored, handled already
19:59:45.730 I/ReceptionExample handlePushMessage returned true
19:59:45.730 D/Reception Push payload recognized, conversation cmw2gn0zu008t00n40c4nc4mv
19:59:45.733 D/Reception Notification shown, conversation cmw2gn0zu008t00n40c4nc4mv
19:59:46.176 D/Reception Unread refresh, 0 unread
```

(The backend had nothing unread for that conversation, so the refresh removed both notifications, as specified.)
After `am force-stop`, the first id again, delivered into a fresh process:

```
19:59:55.497 I/Reception Configured, SDK 1.0.0
19:59:55.524 I/ReceptionExample handlePushMessage returned true
19:59:55.528 D/Reception Push message ignored, handled already
```

S7-1, routing off (`…ROUTING --ez enabled false`), Home, a real FCM reply, tap in the shade:

```
20:00:42.776 D/Reception Notification shown, conversation cmw2guazl009300n4wn1yvxkz
20:00:54.432 START {dat=reception://conversation/… cmp=…/sh.reception.ui.ReceptionNotificationActivity}
20:00:54.503 D/Reception Notification tap not routed, the app handles notifications
topResumedActivity …/.MainActivity t97          (no ReceptionChatActivity; s7-1-routing-off-tap.png)
```

Routing on again (`--ez enabled true`), Home, another reply, tap:

```
20:01:20.214 D/Reception Chat opens once an activity is resumed
20:01:20.574 D/Reception Chat presented
topResumedActivity …/sh.reception.ui.ReceptionChatActivity t97      (s7-1-routing-on-tap.png)
```

StrictMode or `FATAL EXCEPTION` lines (`logcat -b all`) and the crash buffer: 0; process 1266 ran from the restart
above through the whole S7-1 run.

Not live: the pending-tap discard (the example configures in `Application.onCreate`, so no tap arrives before
configure, and the flag is per process); S7-3 (unit tests).

## Deviations

1. S7-1 disabling also drops a tap that was already routed and waits for the next resumed activity, not only the
   pending pre-configure tap; otherwise a tap just before the plugin's opt-out would still open the chat.
2. A disabled tap is not recorded in `NotificationRouting` and refreshes nothing (no `received`); the foreground's own
   unread refresh follows anyway.
3. S7-2 dedupes under the App ID's storage namespace (`DeviceStore.namespace`, which includes a test service's
   address), not the bare App ID, so a test backend and the hosted service keep separate lists like every other
   per-App ID value.
4. The live S7-2 run uses the simulated duplicate (the brief's first option), so the example gained the debug-only
   `VerificationReceiver` (exported in debug builds only).

## Open questions

1. `Reception.kt` is 441 lines (the new hook's KDoc); now recorded in AGENTS.md as the one exception.
2. The routing flag is not persisted: a background FCM start or a cold tap before the plugin's configure routes as
   enabled until the plugin calls the hook. With Flutter/RN the plugin's configure runs before any drain only if it
   calls the hook before `Reception.configure`; the plugins should call `setNotificationRoutingEnabled(false)` first.
