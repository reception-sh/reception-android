# Fixes 12: audit of fixes 11 (AA-1..AA-5, AB-1)

Run 2026-11-05 on `main` from 76b7b1c. Emulator `emulator-5554` (`reception_pixel8`, Android 16), local backend
`http://10.0.2.2:3123`, example debug build with StrictMode `penaltyDeath`. UI through the device CLI; adb for install,
logcat, `dumpsys`, `ime set`, `force-stop` and the verification broadcasts. `reception_api29` could not be booted (see
Deviations); the Android 10 and 11 behaviour is covered by Robolectric (`sdk = [29]`, `[30]`).

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| AA-3 icon read before the decisions | 9a37929 | `NotificationSmallIconTest.aChatOpenedWhileTheStoredIconIsReadGetsNoNotification` ("expected 0 but was 1") |
| AB-1 one cancellation line | 0382568 | `ChatPresentationTest.aScreenThatFinishesWhileTheRecordIsSavedGetsNoSheet` (log lines: reason line followed by a bare "Chat presentation cancelled") |
| AA-1 Compose `Dialog` rule only for Compose `Dialog`s | ae244af | `EmbeddedKeyboardWindowsTest.anotherDialogWindowProviderFollowsTheResizeRule` (`sdk = [29]`, "expected 16 but was 32"); `…aChatInAModalBottomSheetResizesItsWindowBeforeAndroid11` (real Material 3 sheet, see below) |
| AA-2 retained composition moved between windows | 6ab3950 | `EmbeddedKeyboardWindowsTest.aRetainedChatMovedToAnotherWindowAdjustsTheNewWindowAndReleasesTheOld` (activity kept `resize` after the move: "expected 32 but was 16") |
| AA-4 Compose `Dialog` parameter updates | f5a7b94 | `ComposeDialogModeGuardTest.aDialogParameterChangeKeepsThePanOnAndroid10` (`sdk = [29]`, "expected 32 but was 16"), `…KeepsAdjustNothingOnAndroid11` (`sdk = [30]`, "expected 48 but was 16"), `…aModeTheHostChoseWhileTheChatIsShownStays` |
| AA-5 example debug tools in `src/debug` | 0934a05 | release build (R8) and lint; live run below |

- AA-3: `ReceptionPush.handleMessage` resolves `NotificationSmallIcon` right after `AppLocale.prepared`; the chat-open
  check, `updatesAppBadge`, the badge number and the accent are read after it, with no suspension before `post`. The
  test prepares the locale first (so only the icon read is pending), holds the storage thread, delivers without a
  message ID, sets `isChatOpen` while the read waits, and releases. SPEC.md's push paragraph says so.
- AB-1: `ChatPresenter.dismiss(then, reason)`; `startIfWanted` passes its reason, so the line is "Chat presentation
  cancelled, <reason>" once. A plain `dismiss()` still logs "Chat presentation cancelled".
- AA-1: `KeyboardWindow` only finds the window and its `DialogWindowProvider` view (`provider`).
  `EmbeddedKeyboardMode` decides: a Compose `Dialog` is a provider whose class is `androidx.compose.ui.window.DialogLayout`
  (`DialogWrapper`'s content view in Compose UI 1.7.6); every other provider follows the resize rule. Material 3 1.3.1's
  `ModalBottomSheetDialogWrapper.updateParameters` sets `adjustResize` below API 30 and `adjustNothing` from 30
  (bytecode of `material3-android-1.3.1`), which is what the resize rule wants, so the SDK leaves a sheet's window alone.
  Host apps shrink with R8, so `consumer-rules.pro` has `-keepnames class androidx.compose.ui.window.DialogLayout`; the
  example's release mapping keeps it (`androidx.compose.ui.window.DialogLayout -> androidx.compose.ui.window.DialogLayout`).
  The real-sheet test passes on the old code too: the sheet's own `updateParameters` (a `SideEffect` after the chat's
  effect) rewrote the SDK's `pan` to `resize` in Robolectric, so the custom provider is the discriminating test.
- AA-2: `EmbeddedKeyboardAdjustment` keeps a `WindowAdjustment` per composed view: an `OnAttachStateChangeListener`
  releases the held window on detach and acquires `KeyboardWindow.of(view)` on attach; disposal removes the listener and
  releases at most once. The test moves a `DisposeOnLifecycleDestroyed` `ComposeView` from the activity into a
  `ComponentDialog` and back, then ends its composition while a second chat holds the same window and detaches it.
- AA-4: `ui/ComposeDialogModeGuard`, an `OnPreDrawListener` on the dialog's decor view while the SDK holds a Compose
  `Dialog` window below API 31 (Compose 1.7.6 `DialogWrapper.updateParameters` writes the window's whole mode there:
  `16` without `decorFitsSystemWindows`, else `defaultSoftInputMode`, the window's initial adjust bits from its theme).
  The rule: before each frame, a mode exactly equal to `adjustResize` or the dialog theme's `windowSoftInputMode` adjust
  bits goes back to the SDK's adjust mode; any other mode is the host's and stays. Probe in Robolectric: theme `32`
  (`adjustPan`) and Compose writes `32` with `decorFitsSystemWindows`, theme `0` and Compose writes `16` without, on
  API 29 and 30. A host that wants exactly one of those two values while a chat is in the dialog cannot keep it (SPEC).
- AA-5: `ExampleDebugTools.kt`, `ic_example_notification` and the labels (`chat_in_dialog`, `chat_in_bottom_sheet`,
  `app_notification_icon`) are in `example/src/debug`; `example/src/release` has a no-op `ExampleDebugTools` and
  `DebugTools()`. New debug button "Chat in a bottom sheet" (`ModalBottomSheet`, fully expanded, `ReceptionChatView`
  at 80 % height). `:example:assembleRelease` (R8) and `lintDebug`/`lintRelease` pass (warnings only of the existing
  kinds: `GradleDependency`, `AndroidGradlePluginVersion`, `DataExtractionRules`).

## Live run (Android 16, StrictMode `penaltyDeath`)

Gboard selected with `adb shell ime set` before each keyboard step (the device CLI's IME took over once and drew no
keyboard).

AA-1, "Chat in a bottom sheet", composer tapped:

```
sheet window:    sim={adjust=nothing} (fillxfill) ty=APPLICATION       (Material 3's own mode, untouched)
activity window: sim={adjust=pan forwardNavigation} ty=BASE_APPLICATION (untouched)
composer: y=2209 → y=1374 with Gboard (InputMethod isVisible=true)
```

Composer directly above Gboard (`fixes-12/aa1-sheet-keyboard-api36.png`).

Regression, "Chat in a dialog" (Compose `Dialog`, still recognized by class in the debug build):

```
dialog window:   sim={adjust=nothing} (wrapxwrap) ty=APPLICATION
activity window: sim={adjust=pan forwardNavigation}
composer: y=1374 with Gboard
after Back: only the activity window, sim={adjust=pan forwardNavigation}
```

(`fixes-12/aa1-dialog-keyboard-api36.png`).

AA-3 and AB-1 regressions, one process, then a new one (`VerificationReceiver` `…PUSH`):

```
00:11:39.935 Push payload recognized, conversation live-c1         (app in the background)
00:11:39.947 Notification shown, conversation live-c1
00:11:44.609 Chat presented                                        (Support tapped)
00:11:48.421 Push payload recognized, conversation live-c1
00:11:48.422 Notification not shown, chat open
-- am force-stop, broadcast with FLAG_INCLUDE_STOPPED_PACKAGES --
00:12:05.582 18143 Configured, SDK 1.0.0
00:12:05.606 18143 Push payload recognized, conversation live-c2
00:12:05.647 18143 Notification shown, conversation live-c2        (stored icon read first, 41 ms)
00:12:06.165 18143 Unread refresh, 0 unread                        (badge sync removes it: no real reply behind it)
```

Logs: `logcat -b all` (cleared before the run) 0 `StrictMode`, 0 `FATAL EXCEPTION`; crash buffer empty; no
`am_crash`/`am_proc_died` for the example.

## Build

Five `./gradlew :reception:test --rerun-tasks --continue`, reports copied per run:

```
run 1 rc=0 tests 514 failures 0
run 2 rc=0 tests 514 failures 0
run 3 rc=0 tests 514 failures 0
run 4 rc=0 tests 514 failures 0
run 5 rc=0 tests 514 failures 0
```

`tool/release_check.sh` runs on the clean tree after this file is committed; its summary is in the final report and
STATUS.md.

## Deviations

1. `reception_api29` crashed twice during emulator setup (`detected a hanging thread 'QEMU2 CPU… thread'`, process
   gone; once windowed, once `-no-window -memory 2048 -cores 2`) with about 80 MB of host memory free while
   `emulator-5554`, `emulator-5556` and an iOS simulator ran; neither of the latter two is this agent's to stop. No live
   Android 10 check of AA-1/AA-4; the Robolectric tests at `sdk = [29]` and `[30]` are the evidence.
2. AA-4 is not reproduced live: the example has no way to recompose a `Dialog` call while its chat is shown, and the
   behaviour only exists below Android 12.
3. The fresh-process push's notification was removed by the unread sync (0 unread for a made-up conversation), so its
   icon was not read from `dumpsys`; the stored icon name is unchanged
   (`sh.reception.receptionAndroidExample:drawable/ic_example_notification`) and the post path is X-5's.
4. The AA-1 real-sheet test does not fail on the old code (see above).
5. The AA-3 test sets `ReceptionState.isChatOpen` directly to model the chat opening during the read.
6. AA-5's commit also makes the example's debug sheet open fully expanded (`skipPartiallyExpanded`), found live: half
   expanded, the composer was off screen.

## Open questions

1. Detecting a Compose `Dialog` by class name ties the SDK to Compose UI's private `DialogLayout`; a Compose update
   that renames it silently turns dialogs back to the resize rule (overflow on Android 11+). A unit test against the
   pinned BOM would catch it at the next BOM bump (the existing `EmbeddedKeyboardWindowsTest` dialog tests do).
2. AA-4's rule cannot tell a host that sets exactly `adjustResize` (or the theme's mode) from Compose's update.
