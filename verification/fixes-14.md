# Fixes 14: the SDK on a host's newer Compose (FlowRow NoSuchMethodError)

Run 2026-11-05 on `main` from 527d095. Emulator `emulator-5556` (`reception_pixel8_rn`, Android 16), local backend on
port 3123. UI through the device CLI; adb for install, logcat, airplane mode and `adb reverse`.

The bug: the SDK compiles against Compose BOM 2024.12.01 (foundation 1.7.6). Its "Not delivered" line called
`FlowRow(Modifier, Horizontal, Vertical, Int, Int, FlowRowOverflow, content)`, an overload Compose 1.8 removed. A host
on Compose 1.8 or newer (the React Native example resolves foundation-layout 1.9.3) crashed with `NoSuchMethodError` at
`ui/SendingStatus.kt:44` as soon as a failed message rendered, in the sheet and in the embedded view.

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| 1 `FlowRow` replaced by `FittingRow` | 2eac4d7 | `DeliveryStatusTest` (Retry, and the automatic countdown with Cancel, compose; row vs. stacked positions) |
| 2a a composition test per screen | 16b2c4c | `ChatScreensTest`: with the pre-fix SDK on BOM 2026.09.00, `theHistoryComposes`, `theEmbeddedChatComposes` and `theSheetComposes` fail with `NoSuchMethodError: …FlowLayoutKt.FlowRow(…FlowRowOverflow…)` |
| 2b `-PreceptionComposeBom` and the release check step | 34d8459 | release check, step "Compose compatibility" |
| 2c binary reference check | bcb18c6 | `tool/compose_binary_check.py` on the pre-fix classes: "Missing at runtime: method …FlowLayoutKt.FlowRow(…FlowRowOverflow…) called from sh/reception/ui/SendingStatusKt", exit 1 |

- 1: `ui/FittingRow` is a plain `Layout` that ports iOS's `ViewThatFits` in `MessageBubble.swift` (an `HStack` with
  spacing `small`, else a trailing `VStack` with `deliveryStatusSpacing`): one vertically centered row when the
  children's max intrinsic widths plus gaps fit, otherwise a column aligned to the end. `FlowRow` wrapped item by item;
  the new layout matches iOS instead. `ExperimentalLayoutApi` is gone from `SendingStatus.kt`.
- 2a: `ChatScreensTest` composes the embedded chat, the sheet (`ChatSheet`), the history with one message of every
  kind (text, photo, review, link and paywall cards, sent, failed with Retry), the composer, the cards and the
  lightbox against `ChatHarness`; each asserts only that it composed and was laid out.
- 2b: the root `build.gradle.kts` forces the given BOM with `eachDependency` on `*UnitTestRuntimeClasspath` of every
  module and on all runtime classpaths of `:example`. The library's compile and release classpaths keep 2024.12.01
  (checked with `:reception:dependencies`: compile/runtime 1.7.6 and Material 3 1.3.1, unit-test runtime 1.12.1 and
  1.4.0); the POM still imports 2024.12.01. The BOM pins Material 3, so no separate Material 3 property is needed.
  Compose 1.12's AAR metadata asks for compileSdk 37 and AGP 9.1, so `check*AarMetadata` is off for that run only.
  `newest_compose_bom=2026.09.00` in `tool/release_check.sh`; CONTRIBUTING.md (Compose compatibility) says how to bump it.
- 2c: tests only catch signatures on paths they compose. The script reads every androidx method and field reference
  in the SDK's compiled classes and resolves it, through supertypes, in the jars of the forced BOM
  (`:reception:composeCompatibilityClasspath`, only with the property).

## API survey (item 1's method)

- Source compile of a scratch copy against BOM 2026.09.00 (ui/foundation 1.12.1, Material 3 1.4.0) with deprecation
  warnings visible: no warnings, no errors. A removed overload does not show at source level, because the source
  binds to the new one.
- Binary check of the classes compiled against 1.7.6, against BOMs 2025.05.00 (foundation 1.8.1, M3 1.3.2),
  2025.10.01 (1.9.4, 1.4.0), 2026.01.00 (1.10.1, 1.4.0), 2026.05.00 (1.11.1, 1.4.0) and 2026.09.00 (1.12.1, 1.4.0):
  in every one, the only missing member is the `FlowRow` overload. After the fix, 2616 androidx references resolve
  against 26850 runtime classes of 2026.09.00.
- Checked by hand, since no bytecode check covers them: the two names the SDK reads by string. `EmbeddedKeyboardMode`'s
  `androidx.compose.ui.window.DialogLayout` still exists in Compose UI 1.12.1. `AppLocale`'s AppCompat reflection is
  optional and catches `ReflectiveOperationException`/`LinkageError`.
- So nothing else was replaced. Still in use and resolving on 1.12.1: `ModalBottomSheet`/`rememberModalBottomSheetState`
  (Material 3, experimental), `WindowInsets.isImeVisible` (`ExperimentalLayoutApi`), `BasicTextField`, `LazyColumn`.

## Checks

- `tool/release_check.sh` on the clean tree (bcb18c6): all checks passed, signing skipped (no key). Tail:

  ```
  == Summary
    ok    clean working tree
    ok    version agreement
    ok    tool/sync_strings.py --check
    ok    library: clean, release build, lint, unit tests, API check
    ok    example: release build (R8) with Firebase and Billing
    ok    example: bare release build (R8) without Firebase and Billing
    ok    Compose compatibility: unit tests and example on BOM 2026.09.00
    ok    publishToMavenLocal and the POM
    ok    signing configuration
  All checks passed.
  ```

  The compatibility step printed "2616 androidx references checked against 26850 runtime classes".
- Five `./gradlew :reception:test --rerun-tasks` runs: all green, 540 tests each (270 per variant, debug and release;
  520 before this round), 0 failures, 0 errors.

## Live

**React Native example** (`reception-react-native/example`, Expo 57 / RN 0.86.3, foundation-layout 1.9.3 in the APK).
The SDK at bcb18c6 was published to `~/.m2` from a scratch copy (`git archive HEAD`); the AAR has `FittingRowKt` and
no `FlowRow` reference, and so does the APK's `classes8.dex`. `./gradlew :app:installDebug` with
`ANDROID_SERIAL=emulator-5556`, Metro on 8081, `adb reverse tcp:3000 tcp:3123`.

1. Configure, Open chat, airplane mode on, send "Offline message two": the bubble shows
   the failure icon and "Not delivered  Retry" in one row, while automatic attempts continue in the background
   ([rn-not-delivered.png](fixes-14/rn-not-delivered.png)). This is the screen that crashed before.
2. Close the sheet and open it again (still offline): the failed message with its Retry line renders
   ([rn-reopened.png](fixes-14/rn-reopened.png)).
3. Airplane mode off. Once retries had paused after 8 attempts, a tap on Retry logged "Message sent" and the line
   reads "Sent" ([rn-retried.png](fixes-14/rn-retried.png)).
4. Embedded chat (the example's "Embedded chat" screen), offline: "Embedded offline two" failed, with "Not delivered
   Retry" ([rn-embedded-not-delivered.png](fixes-14/rn-embedded-not-delivered.png)). After reconnecting, the SDK's
   automatic attempt 4 sent it before a tap.

Logcat for the whole run: 0 `FATAL EXCEPTION`, 0 `NoSuchMethodError`.

**Android example**, built with `-PreceptionComposeBom=2026.09.00` (foundation-layout 1.12.1, Material 3 1.4.0 in the
APK; debug with StrictMode `penaltyDeath`). Same scenario in the sheet: offline send, "Not delivered  Retry"
([example-compose112-not-delivered.png](fixes-14/example-compose112-not-delivered.png)); Back, open again
([example-compose112-reopened.png](fixes-14/example-compose112-reopened.png)); retries paused after 8 attempts;
airplane mode off; Retry, "Event MessageSent", "Sent" ([example-compose112-retried.png](fixes-14/example-compose112-retried.png)).
0 `FATAL EXCEPTION`, 0 `NoSuchMethodError`, 0 StrictMode lines from the app.

## Deviations

- The newest BOM is 2026.09.00 (Compose 1.12.1), not 2025.10.01. It requires compileSdk 37 and AGP 9.1, which this
  build does not use, so the compatibility run turns off the AAR metadata checks rather than falling back to an older
  BOM. What matters there is the SDK's bytecode on that Compose.
- Added a static binary check (2c), which the brief did not ask for, because a test only covers the paths it composes.
- `ChatScreensTest` is one test class with one test per screen, not one class per screen.
- Environment: on this emulator, `cmd connectivity airplane-mode enable` takes a few seconds, so the first message of
  each offline run was still sent. Toggling airplane mode also dropped the `adb reverse` forwards, so the RN app sat on
  "Connecting…" until they were added again. Neither comes from the SDK.
- Device CLI: a press on the Retry ref (`press @e3`) was dispatched at (540, 66), not inside the node's rect
  (939, 2095, 126×126), so it hit nothing. A press at the text's coordinates worked each time. This is a tool issue.
- The Android example left installed on `emulator-5556` is the Compose 1.12 build; `./gradlew :example:installDebug`
  restores the stock one.
