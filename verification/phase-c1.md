# Phase C1: chat model, sheet and presenter, base chat UI

Run 2026-11-04 on `main` after 3790fff. Emulator `emulator-5554` (`reception_pixel8`, Android 16), local backend
`http://10.0.2.2:3123` (branch `android-push`, `/v1/health` → `{"ok":true}`), App ID from `.local/app-id.txt`,
example debug build (StrictMode `penaltyDeath` on main-thread disk access). Screenshots in `verification/phase-c1/`.

## Build and tests

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all
BUILD SUCCESSFUL in 27s
116 actionable tasks: 34 executed, 4 from cache, 78 up-to-date
(no other output; lint: "No issues found."; unit tests: 204 runs = 102 per variant, 0 failures)
```

New tests (the chat ones run against MockWebServer with the real main looper and storage thread, `ChatHarness`):

| Test | Covers |
| --- | --- |
| `ChatDeliveryTest.aConfirmedMessageKeepsItsClientIdAndIsSent` | POST body `{ clientId, text (trimmed), attachmentIds: [] }`; confirmation keeps the local ID, status Sent |
| `ChatDeliveryTest.aFailedMessageOffersRetryAndRetriesWithTheSameId` | 500 → Failed, `RetryAvailability.Now`; Retry posts the same client ID |
| `ChatDeliveryTest.anIncompleteUploadDiscardsOnlyTheUploadIds` | `409 upload_incomplete` clears `attachmentIds`, keeps photos, text, ID |
| `ChatReadTest.aGetOvertakenByARead_isDroppedAndReloaded` | a GET whose read generation changed is not merged; one coalesced reload follows; one read |
| `ChatReadTest.aBoundedReadSendsTheConversationAndTheHighestProcessedSequence` | `POST conversation/read` body `{"conversationId":"c1","throughSeq":3}`; unread from the response |
| `ChatStreamTest.aReconnectSignalOpensTheNextStreamWithoutBackoff` | `data: reconnect` → second stream at once, no `stream` retry state (stable over 4 reruns) |
| `CooldownTextTest` | "in 30 seconds / 5 minutes / 2 hours" in en and de through ICU, resend text |
| `MessageLayoutTest` | grouping (one author, same minute), 5-minute timestamps, status on the last outgoing, fade count |
| `ChatScrollStateTest` | first load, following, unseen counting without double counting, the 99+ label |
| `ChatPresenterTest` | `locksSheet(keyboardVisible, onHeader)` and the header band |

## Live run

| Step | Result | Screenshot |
| --- | --- | --- |
| `pm clear`, launch, Support | sheet with the welcome state; the dashboard's remote appearance applied (title "Android care", serif, sparkles, chevron close) | 01-welcome |
| fill "Hello from Android", Send | "Sent"; log below | 02, 03 |
| team reply (script below) while open | arrived through the stream's quiet refresh | 04-team-reply |
| Close, reply, reply again while closed | closing refreshed unread to 1; hidden-chat polling every 15 s raised it to 2; badge on Support | 05, 06 |
| photo: `adb push` JPEG + media scan, picker, Done, Send | chip in the composer, then the photo bubble, `MessageSent` + `ImageSent` | 07, 08 |
| keyboard up, drag over messages | keyboard closed (`mImeWindowVis=3` → `0`), sheet stayed | 09, 10 |
| keyboard up, drag from the header | keyboard and sheet closed | 11 |
| `agent-device settings appearance dark` | dark sheet | 12-dark |
| `user_rotation 1` | landscape sheet (max width), chat kept | 13-landscape |
| Embedded chat | `ReceptionChatView` under the example's `MaterialTheme` | 14-embedded |
| airplane mode on, send | "No connection" banner, "Not delivered · Retry" | 15, 16 |
| airplane mode off | "Connected" after 2 s; the message resent by itself on its backoff, "Sent" | 17, 18 |

```
11-04 15:32:03.740 D Reception: [Reception] DEBUG Chat presentation requested
11-04 15:32:03.806 D Reception: [Reception] DEBUG Chat presented
11-04 15:32:04.003 I ReceptionExample: Event ChatOpened
11-04 15:32:15.873 D Reception: [Reception] DEBUG Device updated
11-04 15:32:16.854 I ReceptionExample: Event MessageSent
11-04 15:32:16.854 D Reception: [Reception] DEBUG Message sent
11-04 15:34:28.455 D Reception: [Reception] DEBUG Unread refresh, 1 unread        (chat closed)
11-04 15:34:43.978 D Reception: [Reception] DEBUG Unread refresh, 1 unread
11-04 15:34:59.509 D Reception: [Reception] DEBUG Unread refresh, 2 unread        (reply sent 15:34:48)
11-04 15:35:46.405 I ReceptionExample: Event MessageSent
11-04 15:35:46.405 I ReceptionExample: Event ImageSent
11-04 15:40:11.888 E Reception: [Reception] ERROR Message send failed, network error   (airplane mode)
11-04 15:40:11.888 D Reception: [Reception] DEBUG Message send failed, network error, ConnectException
11-04 15:40:22.549 D Reception: [Reception] DEBUG Retry scheduled in 13.28s, attempt 5
11-04 15:40:36.604 I ReceptionExample: Event MessageSent                       (after reconnect)
```

Final run with the last build (open, rotate twice, drag closed, open, Close button):

```
11-04 15:45:34.353 D Reception: [Reception] DEBUG Chat presented
11-04 15:45:43.994 D Reception: [Reception] DEBUG Chat dismissed
11-04 15:45:46.204 D Reception: [Reception] DEBUG Chat presented
11-04 15:45:49.575 D Reception: [Reception] DEBUG Chat dismissed
StrictMode lines for the app's pid: 0; FATAL EXCEPTION: 0
```

Team replies: a script (not committed) in the scratchpad, run from `../reception`, calls `createAgentMessage` with
the first organization member, as `inbox/actions.ts reply` does, plus `notifyPush`:
`node --env-file=.env --conditions=react-server --import ./tests/server-runtime.mjs --import tsx .agent-reply.mts "…"`
(plain `npx tsx` fails on `server-only`). The backend's live wake-up (`lib/live.ts`) is in-process, so a reply
written by another process does not send `data: changed` to the dev server's streams; the reply arrived 14 s later
through the stream's once-a-minute quiet refresh (stream opened 15:32:17, reply 15:33:09, shown 15:33:23). A reply
from the dashboard itself would wake the stream at once.

Emulator notes: the device CLI installs its own invisible test IME, which produces no IME insets; the keyboard
steps ran with Gboard (`adb shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`
and `show_ime_with_hard_keyboard 1`).

Not done: the side-by-side with the iOS simulator; C2 does it together with the visual polish.
