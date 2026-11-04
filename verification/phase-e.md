# Phase E: last behaviour items, release files, release check, verification matrix

Run 2026-11-04 on `main` from b1615ff. Emulator `emulator-5554` (`reception_pixel8`, Android 16, Google Play), local
backend `http://10.0.2.2:3123`, example debug build with StrictMode `penaltyDeath`, real FCM HTTP v1. Every launch, tap,
text entry, setting and screenshot went through the device CLI; adb only for install, logcat, `dumpsys`, `am kill`, the
setup-check `am start` (the SPEC command itself), the verification broadcast and the IME switch noted below. Team
messages, cards, the rate-limit budget, the push prompt and the automation toggle came from scratch scripts against the
local database (`createAgentMessage` + `sendPush` as the first member); identity tokens were minted by a scratch script
from `.local/identity-secret.txt` (HS256, `user_id`, `exp` in one hour) and staged with the new debug broadcast
`…IDENTIFY --es token <JWT>`.

## Commits

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| E-1 setup check from `noteCurrentActivity` | e42db5b | `SetupCheckHookTest.theActivityAPluginHandsOverStartsTheCheck`, `…anotherAppIdFailsWithAppIdMismatch` (2 of 3 fail: no check started); `…aReleaseHostIgnoresTheExtra` |
| E-2 restored sheet waits up to 3 s for configure | 9badb5b | `ChatPresentationTest.aRestoredSheetWaitsForAConfigureThatComesLate`, `…aRestoredSheetFinishesWhenNoConfigureComes` (2 fail: finished at once); `…aLateConfigureFollowedByALogoutDoesNotRestore` |
| E-3 `SheetGestures.kt` out of `ChatPresenter.kt` | ee36a52 | `SheetGesturesTest` (renamed `ChatPresenterTest`); full suite green |
| One version source, API dump | 7e7db67 | `apiCheck` |
| README, CHANGELOG, CONTRIBUTING | f13e333 | — |
| `tool/release_check.sh` | 3599328, 0fa1282 | the script |
| Coroutines `api` (found by the POM check) | 1e82210 | POM check |
| Embedded chat in a panning window (found by the matrix) | 552fa0b, c30cc3d | `EmbeddedKeyboardModeTest` (the unspecified case fails on 552fa0b) |
| Debug `…IDENTIFY` broadcast | e6e388f | — |
| Screenshots | 6eff9a1 | — |

- E-1: `ApplicationLifecycle.noteCurrentActivity` calls `SetupCheck.activityCreated(activity)` before treating it as
  resumed; the check still starts once per process, only on debuggable hosts, only after configure (in either order).
  SPEC's adb command is unchanged for every host.
- E-2: `ReceptionState.namespace` is backed by a `StateFlow`; `ChatPresenter.restored` waits for it with
  `withTimeoutOrNull(3 s)` when nothing is configured yet, draws nothing meanwhile, and then applies the same checks
  (`changes`, `presentation`, record); a logout or reconfiguration after the late configure still finishes the sheet.
- Version: `SDKVersion.current` in `Version.kt` is the only place; `reception/build.gradle.kts` reads it for the
  coordinates. The release check compares it with the top CHANGELOG entry and the README coordinates; the POM check
  compares the published version.
- API: binary-compatibility-validator 0.16.3, `reception/api/reception.api` (397 lines before the coroutines change,
  three Compose-compiler `ComposableSingletons` holders ignored by name).
- Coroutines: the POM check showed `kotlinx-coroutines-android` in runtime scope although `unreadCount`/`isChatOpen`
  are `StateFlow`s; it arrived on hosts' compile classpath only through Compose. Now `api`.
- Embedded keyboard: the example's `MainActivity` declares no `windowSoftInputMode` (like Android Studio's Compose
  template), so the window panned for the keyboard while the chat also applied the IME insets: the composer floated a
  keyboard height above the keyboard and the host's top bar left the screen (`14-embedded-keyboard-before-fix.png`).
  `ui/EmbeddedKeyboardMode` sets a panning window (`adjustPan`/`adjustUnspecified`) to `adjustResize` while a chat is
  embedded (counted per window) and restores it after the last one leaves. Android keeps the last adjust mode when a
  window asks for "unspecified" again (live: still `adjust=resize` after leaving), so an unspecified original comes back
  as what Android resolves it to (resize with a shown scroll container, else pan). Live: `sim={adjust=pan
  forwardNavigation}` → embedded `sim={adjust=resize}` → after `sim={adjust=pan}`; `14-embedded-keyboard.png`.
- THIRD_PARTY_NOTICES.md checked: the only vendored third-party files are the 34 `reception_symbol_*` drawables
  (Material Symbols, Apache 2.0), all listed; no ImageVector path data in Kotlin; OkHttp, Coil, kotlinx and androidx are
  Maven dependencies, not shipped in the AAR. Unchanged.

## Release check

`tool/release_check.sh` on the clean tree at 6eff9a1 (exit 0). Library step: 442 unit-test runs (debug and release
variants), 0 failures, `lint-results-debug.txt` "No issues found", no warning line in any Gradle log. Tail:

```
== version agreement
Version.kt: 1.0.0
CHANGELOG.md, top entry: 1.0.0
README.md: sh.reception:reception:1.0.0
…
BUILD SUCCESSFUL in 10s
29 actionable tasks: 6 executed, 23 up-to-date
coordinates: sh.reception:reception:1.0.0
packaging: aar
license: MIT License
scm: https://github.com/reception-sh/reception-android
  runtime  androidx.activity:activity-compose
  runtime  androidx.browser:browser
  compile  androidx.compose.foundation:foundation
  compile  androidx.compose.material3:material3
  compile  androidx.compose.ui:ui
  runtime  androidx.core:core-ktx
  runtime  androidx.exifinterface:exifinterface
  runtime  androidx.lifecycle:lifecycle-process
  runtime  com.squareup.okhttp3:okhttp
  runtime  com.squareup.okhttp3:okhttp-sse
  runtime  io.coil-kt:coil-compose
  compile  org.jetbrains.kotlin:kotlin-stdlib
  compile  org.jetbrains.kotlinx:kotlinx-coroutines-android
  runtime  org.jetbrains.kotlinx:kotlinx-serialization-json

== signing configuration
Skipped: no signing key configured (signingInMemoryKey or signing.keyId in /Users/andi/.gradle/gradle.properties).
The release machine needs one before ./gradlew publishToMavenCentral; see CONTRIBUTING.md.

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

The signing branch was not run end to end: this machine has no GPG. With `ORG_GRADLE_PROJECT_signingInMemoryKey` set,
`:reception:tasks --all` lists `signMavenPublication`, the task the script runs.

## Verification matrix

Fresh install (uninstalled first, notification permission revoked and its flags cleared).

| Step | Observed | Evidence |
| --- | --- | --- |
| Welcome | empty state, composer | `01-welcome.png` |
| Send → reply | `Device registered`, `Message sent`; team reply + FCM: `Push payload recognized`, `Notification not shown, chat open`, `Unread refresh, 1 unread`; the reply appears on the next load (the script's process does not reach the dev server's in-process event bus, as in fixes-8) | `02-send-reply.png`, `02-reply.png` |
| Photo | picked from the system photo picker, `Message sent`, events `MessageSent` + `ImageSent` | `03-photo.png` |
| Lightbox | opens full screen with close button, Back closes | `04-lightbox.png` |
| Cards | review, paywall, link and screen cards render | `05-cards.png` |
| Screen card | chat closes, `Screen order 1042`, event `LinkOpened` | `06-screen-card.png` |
| Paywall card | chat closes, `Paywall spring_sale` dialog, event `PaywallOpened` | `07-paywall-card.png` |
| Link card | opens Chrome (its first-run screen on this emulator); Back returns to the chat | `08-link-card.png` |
| Review card | Google Play (`UnauthenticatedMainActivity`), event `ReviewOpened`; Back returns to the chat | `09-review-card.png` |
| Language `de` | built-in texts, date ("Mi."), Play button in German; team-written card labels as sent | `10-german.png` |
| Dark mode | follows the system; accent pair switches | `11-dark.png` |
| Font scale | 1.35 (the CLI offers 1.24 or 1.35, no 1.3); text scales, no clipping | `12-font-1.35.png` |
| Rotation | landscape keeps the sheet and its content | `13-rotation.png` |
| Embedded + keyboard | after the fix: composer on the keyboard, host top bar stays; before: see above | `14-embedded-keyboard.png`, `14-embedded-keyboard-before-fix.png` |
| Sheet + keyboard | composer on the keyboard, draft shared with the embedded chat | `15-sheet-keyboard.png` |
| Airplane mode | "No connection"; a send fails with Retry (`Retry scheduled …`); airplane off → `Message sent` by itself | `16-offline.png`, `17-offline-send.png`, `18-reconnected.png` |
| Cooldown | the device's 10-minute budget used up by script (46 charges), next send: `Rate limited, HTTP 429, retry after 50s`, notice "Message limit reached. You can send again in 45 seconds." and the bubble's countdown | `19-cooldown.png` |
| Identity token | token for `phase-e-user-1` staged, foreground → backend `externalUserId phase-e-user-1 verified yes`; token for `phase-e-user-2` → `Signed-in user changed, previous session logged out`, `Session revocation completed`, chat reset; a send → `Device registered`, new device `externalUserId phase-e-user-2 verified yes` | `20-token-switch.png` |
| Notification card | dashboard "Ask for push notifications" set to replies (script), card after the message, Continue → system dialog → Allow → `Notification prompt answered, granted`, status line | `21-notification-card.png`, `22-permission-dialog.png`, `23-push-status.png` |
| Push, foreground (chat closed) | `Notification shown`; record `tag=reception:<id> id=1 channel=reception_replies` | logcat, `dumpsys notification` |
| Push, background | `Notification shown`; tap in the shade → chat on top | `24-shade.png`, `25-background-tap.png` |
| Push, killed | `am kill` (no pid) → reply → process started (pid 9593), `Configured`, `Notification shown` | logcat |
| Cold-start tap | `am kill` again, tap → `Configured`, `Chat opens once an activity is resumed`, `Chat presented`, all three replies | `26-cold-start-tap.png` |
| Automation `track` | automation enabled by script, relaunch, Track event → `Automation message received for android_example_event`, `Chat presented` | `27-automation.png` |
| Logout | `Logged out, session revocation queued`, `Session revocation completed`, backend push token cleared, chat back to welcome | `28-after-logout.png` |
| Delete data | after one message: `Data deleted`, event `DataDeleted`, welcome again | `29-after-delete.png` |
| Setup check | `adb shell am start -S -n …/.MainActivity --es ReceptionSetupCheck <App ID>` → `Setup check started`, `Setup check passed` | logcat |

StrictMode and crashes (`logcat -b all`, buffer from 20:53:51, the install, to the end): 0 `FATAL EXCEPTION`; the crash
buffer, cleared at install, empty; 514 `StrictMode` lines, all from `com.google.android.googlequicksearchbox` processes
(pids 1842, 8697), none from any of the example's nine processes (4826 … 10062).

Restored afterwards: the automation disabled, `pushPrompt` back to `OFF`, light mode, font scale medium, portrait, the
device CLI's IME.

## Deviations

1. Font scale 1.35 instead of 1.3: the device CLI's `text-size` rungs give 1.24 or 1.35.
2. The keyboard steps switched the IME to Gboard with `adb shell ime set` (as in phase C1): the device CLI's own IME
   draws no keyboard and produces no IME insets.
3. The cooldown was provoked by using up the device's budget in the local database through the backend's own
   `consumeLimits`, because the CLI cannot send five messages within 10 seconds (one every ~2.2 s). The 429, its
   `Retry-After` and the UI are the real path.
4. Team replies appear on the next load, not live (script outside the dev server's process, known since fixes-8).
5. Two fixes beyond the brief, both found by the checks: coroutines as `api`, and the embedded chat in a panning window.
6. `ReceptionState.kt` is 222 lines (was 210) with the configure signal for E-2.

## Open questions

1. Customer docs (`reception/content/docs/android.md`, not edited here): "The check needs `configure` in
   `Application.onCreate`" stays true for native apps; the Flutter and React Native pages can now say the same adb
   command works for them (E-1), and a restored sheet survives process death there too (E-2) once their plugins use
   this SDK version.
2. The example's dark accent `#B0A8FF` with the default white `onAccentColor` has low contrast on card buttons; an
   example choice, not SDK behaviour (iOS has the same default).
