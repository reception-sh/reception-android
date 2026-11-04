# Fixes 4: third-round audit (G-1..G-4) and decided items (H-1..H-4)

Run 2026-11-04 on `main` after 8b435ee. Emulator `emulator-5554` (`reception_pixel8`, Android 16), local backend
`http://10.0.2.2:3123` (branch `android-push`), example debug build with StrictMode `penaltyDeath`.

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| G-1 token before the registration is saved | 2329672 | `SessionAuthorizationTest.aCallerThatReadsDuringAnUnsavedRegistrationSendsNothing` (second caller returned no error and sent its request) |
| G-3 picker bound to its launcher | 908a325 | `PhotoPickerOwnershipTest` (4 tests; new API, like C1-5) |
| G-2 background under the picker | d3eb79b | live (below); no Compose UI test without `ui-test` |
| G-4 offline chat keeps the cached team | a4e8811 | `ChatTeamTest.aChatOpenedOfflineKeepsTheCachedNamesAndPhotos` (passes; fails with a8995bb reverted: timed out waiting for the photos) |
| H-2 flaky activation test | 4e506fe | `ChatActivationTest.anActiveChatListensOnceItsOpeningLoadFinishes` with the stream assertion back |
| H-4 `onPaywall` and the sheet | 586c5a1 | — (SPEC.md) |
| H-1 `Theme.symbolScale` | da61176 | screenshots (below) |
| H-3 paywall bodies out of `Reception.kt` | — | not needed, see below |

- G-1: `settledToken(replacing)` in `SessionAuthorization.kt` waits for any registration in flight, then runs
  `persistPendingCredentials()`, and repeats while a registration is still in flight. Only then does it return the
  token in memory. `token()` and `renewedToken()` use it on both fast paths, before and after the suspending credential
  read. If the save fails, every waiting caller fails with `secure_storage_unavailable`. In the test, the second
  caller's read is held on the storage thread, so the first caller's save queues behind it while its token is already
  in memory. Requests the server saw: `POST /v1/devices` only.
- G-2: live, the system photo picker (`com.google.android.photopicker`) runs in the chat's task and only pauses
  `ReceptionChatActivity` (`dumpsys`: picker RESUMED, chat PAUSED, MainActivity PAUSED), so the app stays started.
  `ChatLifecycle` now also observes `ProcessLifecycleOwner`. Its `ON_STOP` (Home, lock) always runs `disappear()`:
  `deactivate` → `stopPolling` (stream, read generation), `markVisible(false)` (`isChatOpen`), `ContrastMonitor.stop`,
  unread refresh. The chat's own `ON_START`/`ON_RESUME` show it and load again. I did not activate on the process's
  `ON_START` while the picker is still up: `load()` returns early unless `ApplicationLifecycle.isActive`, and that
  stays false while another app's activity is resumed. Activating there would have used up the foreground load before
  the picker closed. The resume after the picker's result does the load instead.
- G-3: `ComposerModel.picker` (`PhotoPickerOwnership`) holds the owning launcher, and `isPicking` is read-only.
  `rememberPhotoPicker` binds a remembered token in a `DisposableEffect` declared before the launchers. Disposal
  releases the picker, except during a configuration change, which hands it to the recreated launcher; the next
  `bound` takes it over. A result for the recreated launcher that arrives before it is bound still ends it.
- G-4: C2's fix holds. Through the production path (the session's `ChatModel` created by `ReceptionState.chatModel()`
  after its cache was written, then `activate()` with `/v1/conversation*` disconnecting), the cached name and photo
  stay, `team` stays empty and the cache keeps the photo. `ChatHarness.start(beforeChat)` seeds storage before the
  model exists.
- H-2, root cause: the test, not the SDK. The harness's default `/v1/conversation/unread` answer is
  `hasConversation:false`, while the test's conversation GET returns a conversation. When the unread refresh landed
  after the opening load, it set `hasConversation` back to false, and `startStream()` declined. With the old stream
  assertion and 40 iterations: 5 failures (`active=true polling=false conv=false`). With a consistent answer: 40 of
  40 passed. Production follows iOS here (`ChatModel.swift` `refreshUnread`: the unread answer is authoritative); see
  the open questions.
- H-1: `Theme.symbolScale = 1.15f`, applied with `Modifier.scale` to `AppearanceIconView`'s symbol branch (cards
  incl. accessories, welcome icon, close button incl. the lightbox one) and to the composer's photo glyph. Layout boxes
  are unchanged, so row heights keep the iOS metrics. Host and remote images are not scaled. Remote card styles that
  name a symbol draw the same Material glyph, so they scale too.
- H-3: nothing to move. The paywall members in `Reception.kt` already are one-line delegations to the internal
  `ReceptionPaywalls` (88 lines). The file's length comes from the public nested types `Appearance` (~110 lines),
  `CloseIcon`, `FontDesign`, `ColorScheme` and `WelcomeIcon`. They must stay nested, because Kotlin has no nested
  typealiases (as in B2). The file is still 354 lines.
- H-4: SPEC.md "Paywall cards, screens, links" and the STATUS entry (docs phase).

## Build

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all --rerun-tasks
BUILD SUCCESSFUL in 35s
116 actionable tasks: 116 executed
(no warnings: grep "w: |warning:|deprecat" → 0; lint "No issues found."; unit tests 151 per variant, 302, 0 failures)
$ tool/sync_strings.py --check
strings up to date (49 files)
$ ./gradlew :reception:test --rerun-tasks      five times
runs 1–5: exit 0, 302 tests, 0 failures each
```

## Live run (StrictMode `penaltyDeath`)

Example rebuilt with all commits and installed (`adb install -r`: Success). Backend request log
`/private/tmp/reception-dev-3123.log` (a line is written when a request completes, so an open stream appears only when
it closes):

```
open chat, type "Fixes 4 picker check", send
  PATCH /v1/devices/me 200 · GET /v1/conversation/unread 200 · POST /v1/conversation/messages 201
  GET /v1/conversation/messages?after=0 200 · POST /v1/conversation/read 200
  logcat 17:09:01.022 D Message sent
tap Add photos → picker up (dumpsys: photopicker RESUMED, ReceptionChatActivity PAUSED)
  (no requests: no reload under the picker)
Home with the picker up
  GET /v1/conversation/events 200 in 8.0s      ← the stream closed (before: stayed open, one ran 12.4 min)
  GET /v1/conversation/unread 200              ← disappear()'s unread refresh
return to the app
  logcat 17:09:14.965 I Event ChatOpened       ← the chat had disappeared and appeared again
  PATCH /v1/devices/me · GET /v1/conversation/unread · GET /v1/conversation 200 · POST /v1/conversation/read
  GET /v1/conversation/messages?after=487 · POST /v1/conversation/read
  (the system picker had finished itself while in the background, so the chat resumed and loaded at once)
seed cards, relaunch, open (cards screenshot), close
  17:15:27.479 D Chat dismissed · 17:15:28.005 D Unread refresh, 0 unread
Log out
  17:15:30.554 I Logged out, session revocation queued · 17:15:30.584 D Session revocation completed
relaunch, open (welcome screenshot)
  17:15:36.864 I Configured, SDK 1.0.0 · 17:15:37.586 I Device registered · 17:15:42.246 D Chat presented
StrictMode / FATAL EXCEPTION lines in logcat: 0
```

In this run the picker did not survive Home: it was gone on return. An earlier run, on the build before d3eb79b,
returned with the picker still on top (chat STARTED, picker RESUMED). On that path the chat's `ON_START` shows it and
the resume after the picker closes loads it. That path was not repeated on the final build.

## Side by side (H-1)

Same published appearance as Phase C2 (`#635BFF`/`#A59FFF`, close button `xmark`, welcome `sparkles`, no push
status), published to the Android App ID for the shots and removed again afterwards. The cards were seeded with
`createAgentMessage` (review, paywall `spring_sale`, web link, `reception-screen:order?value=1042`), twice because a
first run's Telegram-mirror error hid its success. iOS shots from Phase C2.

| Pair | Image |
| --- | --- |
| Welcome | `fixes-4/side-by-side-01-welcome.png` (`android-01-welcome.png` vs `phase-c2/ios-01-welcome.png`) |
| Cards | `fixes-4/side-by-side-03-cards.png` (`android-03-cards.png` vs `phase-c2/ios-03-cards.png`) |

The sparkles, star, gift, link and chevron now match the SF Symbols' optical size. Material's chevron and arrow are
still lighter in stroke than SF's semibold weight. A scale cannot change that.

## Open questions

1. A stale unread answer can switch off the stream. An unread refresh sent before the conversation existed, but
   answered after the opening load, sets `hasConversation` back to false, and the chat then does not listen until the
   next load. iOS has the same order (`refreshUnread` takes the answer as authoritative). A generation check like the
   read generation would close it on both platforms. Not changed, because the brief says not to invent behaviour.
2. Material's chevron and arrow stay lighter in stroke than SF Symbols. Drawable variants with a heavier weight would
   match better than a scale.
3. The picker-still-open-after-Home path was observed only before d3eb79b. On the final build the system picker had
   finished itself while the app was in the background.
