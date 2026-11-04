# Phase D: push, notification card, automations, paid access, setup check

Run 2026-11-04 on `main` after 9ecfb06. Emulator `emulator-5554` (`reception_pixel8`, Android 16, Google Play image,
no Google account), local backend `http://10.0.2.2:3123` (branch `android-push`), example debug build with StrictMode
`penaltyDeath`. Team replies: a scratchpad script run from `../reception` that calls `createAgentMessage` as the first
organization member and then awaits `sendPush` (real FCM HTTP v1 with the test app's service account):
`node --env-file=.env --conditions=react-server --import ./tests/server-runtime.mjs --import tsx agent-reply.mts "…"`.
StrictMode or `FATAL EXCEPTION` lines after the fix below: 0 in every dump.

## Build and tests

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all
BUILD SUCCESSFUL (no compiler or lint warnings)
$ ./gradlew :example:assembleDebug :example:assembleRelease -PreceptionExampleBare=true   # R8, no Firebase/Billing
BUILD SUCCESSFUL
$ tool/sync_strings.py --check
strings up to date (49 files)
```

New tests: `NotificationRoutingTest` (receive, receive-and-open, open, ignore, pending tap, 64-key bound),
`PushNotificationsTest` (payload recognition, badge rule, posted channel/group/number/intent extras, cancelling only
the SDK's notifications), `PushPromptDecisionTest` (resolve table, Android "not determined"), `AutomationsTest` (event
names, the 5-second rule, revision/open-chat guards, retry while refused, `takeOpenedChat` once), `PaidAccessTest`
(PURCHASED → subscribed, change-only callbacks, active/cancelled/expired states, events and silent first sight),
`SetupCheckTest` (outcome mapping and reasons). All unit tests pass.

## Push (real FCM)

Registration, fresh install with the permission granted:

```
17:38:40.997 I Reception: Configured, SDK 1.0.0
17:38:41.566 I Reception: Device registered
17:38:43.229 D Reception: Push token staged, 142 chars, production
DB: Device cmw2cclzy… pushToken length 142, PRODUCTION, updatesAppBadge t
```

(Staged twice on a fresh install: `registerForPushIfAuthorized` at configure and `onNewToken` from the SDK's service.)

| Scenario | Evidence |
| --- | --- |
| (a) chat open | `Push payload recognized` → `Notification not shown, chat open` → `Unread refresh, 1 unread`; `dumpsys notification`: 0 records |
| (b) foreground, chat closed | `Notification shown`; record `tag=reception importance=4 channel=reception_replies groupKey=<conversation> number=2 flags=AUTO_CANCEL`, `d-shade-b.png` |
| (b) tap, launcher activity on top | `Chat opens once an activity is resumed` → `Chat presented` (onNewIntent listener; receipt key → OPEN), `d-tap-b.png` |
| (c) background, tap | `number=1`, tap → `Chat presented`, `d-tap-c.png` |
| (d) `am force-stop` | Play services: `broadcast intent callback: result=CANCELLED` (Android's stopped state; no app code runs) |
| (d) process killed (`am kill`) | `Start proc … for broadcast FirebaseInstanceIdReceiver` → `Configured` → `Notification shown` (`ReceptionMessagingService` enabled in the example manifest) |
| (d) cold-start tap | `Start proc … for next-top-activity MainActivity` → `Configured` → `Push payload recognized` → `Chat opens once an activity is resumed` → `Chat presented`, `d-tap-d.png` |
| (e) badge | two background replies → `number=2`; opening the app keeps them (2 records); reading in the chat → 0 records |
| (f) `updatesAppBadge=false` | example switch → device update; script: `badge false`; record `number=0`; reading leaves the notification (2 records) |

## Notification card

`Project.pushPrompt = REPLIES` (`GET /v1/appearance?platform=android` → `"replies"`), fresh install without the
permission. After "Card test": the card (`d-card.png`). Continue hides it, the system dialog shows (`d-dialog.png`),
Allow → `Notification prompt answered, granted`, device update, status line (`d-card-granted.png`). Second fresh install,
No thanks → `Notification prompt declined`; after a relaunch the card stays hidden. `pushPrompt` was set back to `OFF`.

## Automations

Local automation `android-phase-d-automation` (event `android_example_event`, `open_chat` + message, enabled,
`duringConversation`, `repeat`), disabled again afterwards. A `track` right after creating it was unheard: configure's
subscription check had cached `GET /v1/automations` (5 minutes, as iOS). After a relaunch:

```
17:46:38.126 D Reception: Automation message received for android_example_event
17:46:38.155 D Reception: Chat presented
17:46:38.171 D Reception: Unread refresh, 1 unread
```

`d-auto-message.png`; the second run's recording frames (`d-automation-frames.png`, `d-automation-reveal.png`) show the
typing dots, then the message fading in.

## Paid access

The emulator has no Google account, so `startConnection` answers `BILLING_UNAVAILABLE` (3): `Paid access not read,
Play Billing setup 3`, `subscribed` omitted. The "no purchases → false" path needs a signed-in Play Store and was not
run live (unit-tested). Bare build: `Paid access not read, Play Billing not in the app` once;
`Push registration skipped, Firebase Messaging not in the app` once (second press silent). The bare R8 release build
starts and tracks without a crash.

## Setup check

```
$ adb shell am start -S -n sh.reception.receptionAndroidExample/.MainActivity --es ReceptionSetupCheck <App ID>
17:47:42.158 I Reception: [Reception] Setup check started
17:47:43.060 I Reception: [Reception] Setup check passed
$ … --es ReceptionSetupCheck app_0000000000000000000000
17:47:50.616 E Reception: [Reception] Setup check failed: app_id_mismatch
```

## Example buttons

Switch user (token) → `Identity token staged`, send → `Device updated`, `Message sent`; Delete data → `Data deleted`,
`Delete data succeeded`, then `Push token staged` for the new session.

## Found and fixed during the run

`appendInlineContent` refuses an empty alternate text: the status line crashed the app on first display (fixed before
committing the card's live evidence; the stored draft of that send survived the crash, as designed).
