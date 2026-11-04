# Fixes 10: audit of fixes 9 (X-1..X-5)

Run 2026-11-04 on `main` from e8e7278. Emulators: `emulator-5554` (`reception_pixel8`, Android 16) and, for X-1 and
the Android 10 dialog check only, `emulator-5558` (`reception_api29`, Android 10, no Play; booted for the run and shut
down after). Local backend `http://10.0.2.2:3123`, example debug build with StrictMode `penaltyDeath`, real FCM HTTP v1.
Gboard selected with `adb shell ime set` before every keyboard step (the device CLI's own IME draws no keyboard and
produces no IME insets, as in phase E). UI through the device CLI; adb for install, logcat, `dumpsys`, `am kill`,
`run-as` and the one launch with an extra.

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| X-2 window from the composition | 618b2f9 | `EmbeddedKeyboardWindowsTest` (activity chat and Compose `Dialog` chat together; a `ComponentDialog` with a `ComposeView`): the dialog's window kept `pan`, the activity's was changed |
| X-3 host's newer mode stays | 8357369 | `EmbeddedKeyboardModeTest.anAdjustModeTheHostSetWhileTheChatWasShownStays`, `…stateFlagsTheHostSetWhileTheChatWasShownStay` (2 of 6 fail on 618b2f9) |
| X-1 `adjustNothing` before Android 11 | 217f58b | `EmbeddedKeyboardModeTest.aWindowThatAdjustsNothingResizesBeforeAndroid11` (`@Config(sdk = [29])`); `…KeepsItsModeFromAndroid11` (`sdk = [30]`) replaces the test that preserved NOTHING everywhere |
| X-4 paywall limits private | dee3a1a | `ReceptionPaywallTest`, `apiCheck` (`apiDump` drops `ReceptionPaywall.LIMIT` and `PRODUCT_BUDGET`) |
| X-5 icon across process death | 5f7e495 | `NotificationSmallIconTest` (set → stored name → new process → posted with it; null clears; set before any context is stored at configure; an unresolvable stored name gives the built-in bell) |
| X-2 follow-up, Compose `Dialog` | f106957 | `EmbeddedKeyboardWindowsTest.anActivityChatAndADialogChatEachAdjustTheirOwnWindow` (dialog now `nothing`), `…aComposeDialogPansBeforeAndroid11` |
| Example debug tools | 4c6632a | live run below |

- X-2: `ui/KeyboardWindow` resolves the window from `LocalView.current`: a `DialogWindowProvider` parent (Compose
  `Dialog`), else the activity's window when its decor is the view's root, else the root's window layout parameters
  (a platform dialog with a `ComposeView`; they are the same object as that window's attributes, written back with
  `WindowManager.updateViewLayout` while attached). `EmbeddedKeyboardMode` counts per root view.
  `EmbeddedKeyboardAdjustment()` is the composable effect `ReceptionChatView` calls.
- X-3: the hold remembers the adjust mode the SDK installed; release restores only the adjust bits and only while the
  window still carries the installed mode; state and other flags set meanwhile stay.
- X-1: below API 30 an `adjustNothing` window is switched to `adjustResize` while a chat is embedded.
- X-2 follow-up (found live, see below): Compose 1.7.6's `DialogLayout` measures its content against the display,
  never the window, so a resized dialog window clipped the chat, and a panning one lifted it twice. A Compose
  `Dialog`'s window is set to `adjustNothing` from Android 11 (the IME insets alone lift the composer) and `adjustPan`
  before (only a resize would send insets there). Activity and platform-dialog windows keep the resize rule.
- X-4: file-level `private const val`s; the API listing shrinks by two fields.
- X-5: `client/NotificationSmallIcon` replaces `ReceptionPush.smallIcon`. The setter stores
  `resources.getResourceName(id)` (package-qualified; null removes it) under `notificationSmallIcon` in
  `sh.reception.push` (the message-ID store, outside any session namespace) on the storage thread, as soon as the SDK
  has a context (configure, or the first push message for an icon set earlier). A notification posted while the app
  has not set the icon in this process reads the stored name on the storage thread and resolves it with
  `getIdentifier` (lint `DiscouragedApi` suppressed: resource IDs change between builds); 0 falls back to the bell.
  SPEC.md says the Flutter and React Native plugins need no persistence of their own.

## Build

`tool/release_check.sh` on the clean tree at 4c6632a (exit 0; signing skipped, no key on this machine):

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

Five `./gradlew :reception:test --rerun-tasks`:

```
run 1 rc=0 tests 488 failures 0
run 2 rc=0 tests 488 failures 0
run 3 rc=0 tests 488 failures 0
run 4 rc=1 tests 488 failures 1
run 5 rc=0 tests 488 failures 0
```

Run 4 failed one test; run 5 overwrote its reports, so the test is not named. Fourteen further `--rerun-tasks`
runs, stopping at the first failure with its reports kept, all passed (19 runs, 1 failure). The flake is not
reproduced and not attributed; see Open questions.

## Live run (StrictMode `penaltyDeath`)

X-1, Android 10 (`emulator-5558`), `am start -S … --ez adjustNothing true` (the example's debug extra), Embedded chat,
composer tapped:

```
before:   sim={adjust=nothing forwardNavigation}
embedded: sim={adjust=resize}
after Back: sim={adjust=nothing}
```

Composer directly above Gboard, the host's Back row stays (`x1-api29-adjust-nothing.png`).

X-2, Android 16, "Chat in a dialog" (a platform-sized Compose `Dialog`) on the home screen, composer tapped:

```
dialog window:   sim={adjust=nothing}  frame=[120,132][960,2337]
activity window: sim={adjust=pan forwardNavigation}   (untouched)
```

Composer on Gboard (`x2-dialog-keyboard.png`). The first version of the fix (resize the dialog's window) left the
composer below the shrunk frame (frame `[120,132][960,1517]`, composer laid out at y=2194, the area above the keyboard
blank); the dialog without the SDK's adjustment (its default `pan`) lifted the composer a keyboard height too far
(`x2-dialog-before-fix.png`, a build that skipped dialog windows). Hence f106957. Android 10: dialog window
`sim={adjust=pan}`, activity `adjust=nothing` untouched; the composer's text line and cursor are above Gboard, its
bottom edge is clipped (Android 10 pans to the cursor rectangle) (`x2-api29-dialog-keyboard.png`).

Regression, Android 16, the example's activity without `windowSoftInputMode`, Embedded chat: `adjust=pan` → embedded
`adjust=resize`, composer on Gboard, top bar stays (`x3-embedded-keyboard-api36.png`) → after Back `adjust=pan`.

X-5, Android 16: "App notification icon" on (stored at once, the app was configured):

```
shared_prefs/sh.reception.push.xml:
  <string name="notificationSmallIcon">sh.reception.receptionAndroidExample:drawable/ic_example_notification</string>
```

Home, `am kill` (no pid), then a team reply (scratchpad script from `../reception`: `createAgentMessage` as the first
member, then `sendPush`; `push delivered: true`; removed afterwards):

```
22:14:29.820 14851 Configured, SDK 1.0.0                 (process started by FCM)
22:14:29.854 14851 Push payload recognized, conversation …
22:14:29.890 14851 Notification shown, conversation …
NotificationRecord(… id=1 tag=reception:… channel=reception_replies …)
  icon=Icon(typ=RESOURCE pkg=sh.reception.receptionAndroidExample id=0x7f04001c)
aapt2: resource 0x7f04001c drawable/ic_example_notification
```

The shade shows the example's star, not the bell (`x5-shade-host-icon.png`). The example sets the icon only from the
switch, so nothing in the new process set it.

Logs: `emulator-5554` crash buffer empty, `FATAL EXCEPTION` 0, `StrictMode` 0 (`logcat -b all`, cleared at 22:04).
`emulator-5558`: crash buffer empty, `FATAL EXCEPTION` 0; 229 `StrictMode` lines, all from pid 1423 (not the
example: its processes were 3182 and 3405).

## Deviations

1. X-2 needed a second commit (f106957): the brief's fix (resize per window) is right for activity and platform-dialog
   windows but not for a Compose `Dialog`, see above. SPEC.md describes both.
2. The example's dialog is platform-sized (`Dialog` with default properties, `Surface(Modifier.fillMaxSize())`): with
   `usePlatformDefaultWidth = false` Compose 1.7.6 sizes the window to the display while the frame stops at the
   system bars, so the content overflows with or without a keyboard; that is outside the SDK.
3. The `adjustNothing` launch used `adb shell am start --ez adjustNothing true`, because the device CLI cannot pass an
   intent extra; every tap went through the device CLI.
4. The platform-dialog path of `KeyboardWindow` (window layout parameters) is covered by
   `EmbeddedKeyboardWindowsTest.aChatInAPlatformDialogAdjustsTheDialogsWindowOnly` only; the example has no platform
   dialog.
5. A stale device-CLI session from fixes 9 (`t3-1270a4cdc19c3d9d52c48a54`, last event 20:01, no process) held
   `emulator-5554` and was closed.
6. The reply script needs `server-only` (now imported by `src/lib/billing/access.ts`), which Next bundles and
   `node_modules` lacks: an empty stub package in the scratchpad on `NODE_PATH`; the backend was not changed.
7. The example on `emulator-5554` still has the notification icon switch on (stored icon).

## Open questions

1. Android 10 and older, chat in a Compose `Dialog`: the pan shows the cursor line but clips the composer's lower
   edge. A full fix would need the dialog's content to follow the window (Compose measures against the display) or
   the SDK to request a larger visible rectangle; not attempted.
2. Is it acceptable that the SDK overrides a host's explicit `adjustResize` on a Compose `Dialog` window (to
   `adjustNothing` on Android 11+, `adjustPan` before) while a chat is in it? It restores the mode afterwards.
3. A unit test failed once in 19 `--rerun-tasks` runs (run 4 above) and could not be named; the next round should
   keep failing reports (for example `--continue` with the reports copied per run) to find it.
