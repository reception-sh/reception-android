# Phase C2: C1 audit, remaining chat UI, side-by-side with iOS

Run 2026-11-04 on `main` after 19cc62e. Emulator `emulator-5554` (`reception_pixel8`, Android 16), local backend
`http://10.0.2.2:3123` (branch `android-push`), example debug build with StrictMode `penaltyDeath`. iOS: iPhone 17 Pro
simulator (iOS 26.5) with the iOS SDK's own `Examples/Basic` app at 1.7.0, against the same backend. Screenshots in
`verification/phase-c2/`.

## Part 1: C1 audit

Each test below fails on the previous code; the main-source change was stashed or reverted to check.

| Item | Commit | Test (fails on the old code with) |
| --- | --- | --- |
| C1-1 opening work survives dismissal | 023a739 (+dd5dd1a, 87e175b) | `ChatActivationTest.aChatDismissedDuringItsOpeningLoadDoesNotStartListening` (events request sent, chat active) |
| C1-2 queued sheet after close/logout | d51bd03 | `ChatPresentationTest` (closed in the same callback, logout during the launch, no presentation: activity not finishing) |
| C1-3 draft overwrites typed text | b0b7948 | `ChatDraftTest.textWrittenBeforeRestorationWinsAndIsStored` |
| C1-4 empty team erases the cache | a8995bb | `ChatTeamTest.restorationKeepsTheCachedTeam` |
| C1-5 picks lost on rotation | 000fa5f | `ComposerPhotosTest.everyPickPreparesEvenAfterThePickingActivityIsGone` (new API; the old code also dropped a pick made while others prepared) |

- C1-1: `ChatActivation.kt` (`activate`/`deactivate`) keeps the job and an activation generation in the model; the
  generation is checked after restoration and after the load. A configuration change keeps polling and the new screen
  activates again (rotation still reloads once, as in C1).
- C1-2: `ChatPresenter` numbers presentations (`EXTRA_PRESENTATION`); `dismiss()` (closeChat, logout, reconfigure,
  delete) advances the number and cancels a requested sheet. `ReceptionChatActivity.onCreate` finishes at once when
  `attached()` refuses. Recreation keeps the launch intent and therefore its number.
- C1-3: `ComposerModel.isEdited`; restoration stores the written text instead of restoring.
- C1-5: `ComposerModel.add(context, uris)` queues URIs and prepares them in the model scope with the application
  context; a URI leaves the queue only once prepared.

## Part 2: chat UI

Commits 2e24b45 (paywall and screen API), bcabe6e (cards, links, lightbox, typing, jump-to-latest, avatars), a5e2363
(status bar strip), d940ff0 and a9ee85a (lightbox; a9ee85a also carries the example's `closeChat()` in `onPaywall`).

- Public API added: `paywalls`, `paywallAvailability`, `availablePaywalls`, `refreshPaywalls(invalidate)`,
  `onPaywall`, `screens`, `opensWebLinksInApp`, `ReceptionPaywall` (`Source`, `Product`), `ReceptionScreen`.
  Validation and generations from `ReceptionPaywall.swift`/`Reception.swift`; checks at configure, foreground, chat
  appearance, logout and successful deletion; changes go to `FacadeState` and `preferencesChanged()`.
- `viewmodel/ChatLinks.kt` ports `ChatModel+Links` (route via `LinkRoute`: screen → host → web → system),
  `LinkHandling` holds the replaceable platform calls. `ChatPresenter.dismiss(then)` runs the continuation from the
  sheet's `onDestroy`, when the host activity is resumed again. `ui/LinkOpener` (was `TextLinks`): claiming app,
  Custom Tab in the accent color, system with the Play Store app for `play.google.com/store/apps/details` links.
- Views: `ActionCard` + `ReviewCard`/`PaywallCard`/`LinkCard`, `RemoteAttachmentImage` (shared loader, Retry),
  `ImageLightbox`, `TypingIndicator`, `JumpToLatestButton`, `TeamAvatar`, `MessageActions` (row actions and the reply
  haptic), lightbox variant of `DismissButton`. `ChatScrollState`'s unseen set is Compose state now.
- Black strip above the light sheet: the activity window lacked `DRAWS_SYSTEM_BAR_BACKGROUNDS` (`dumpsys window`:
  the chat window had `FORCE_DRAW_STATUS_BAR_BACKGROUND` but not the draws flag, because `Theme.Translucent` predates
  it), so the system painted the status bar black. `android:windowDrawsSystemBarBackgrounds=true` in `Reception.Chat`
  fixes it (`android-00-status-bar-before/after.png`).

New tests: `ReceptionPaywallTest` (limits, trimming, duplicates, 20 paywalls, 10 products each, budget 40, details,
payload), `ReceptionPaywallsTest` (latest check wins, failing check, invalidation, `canOpen`), `ScreenLinkTest`
(parsing, decoding, invalid IDs and escapes, report limit and value-name cut), `LinkRouteTest` (screen/host/web/system,
Play Store and other schemes), `ChatLinksTest` (outcomes handled/opened/failed in the receipt, LinkOpened, chat closed
only for screen and host links, card label fallback and verbatim label, Play Store listing). The 99+ cap is covered by
`ChatScrollStateTest.theUnreadLabelIsCappedAtNinetyNine` (C1).

## Build

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all
BUILD SUCCESSFUL in 29s
116 actionable tasks: 25 executed, 91 up-to-date
(no other output; lint "No issues found."; unit tests 145 per variant, 0 failures)
$ ./gradlew :reception:test --rerun-tasks      (twice, after 87e175b)   no failures
$ tool/sync_strings.py --check
strings up to date (49 files)
```

One full run before 87e175b failed `ChatActivationTest.anActiveChatListensOnceItsOpeningLoadFinishes` once (release
variant): the chat had loaded but no `GET /conversation/events` reached the server within 5 s. The test now waits for
`isChatActive`, which is what the activation does; the missing stream request is listed under open questions.

## Live run (Android)

| Step | Result |
| --- | --- |
| Review, paywall, link (web), link (`reception-screen:order?value=1042`) seeded with `createAgentMessage` | four cards (`android-03-cards.png`) |
| Show order | `LinkOpened`, "Chat dismissed", then the example's dialog "Screen order 1042"; `POST /v1/conversation/action-click` 200 |
| View offer | `PaywallOpened`, `onPaywall("pro_monthly"/"spring_sale")` |
| Read the guide | `LinkOpened`, "Web page presented", Chrome Custom Tab on top (Chrome's first-run screen) |
| Rate on Google Play | `ReviewOpened`, top activity `com.android.vending/...UnauthenticatedMainActivity` |
| Typing (the inbox's `typing` server action over HTTP) | three-dot bubble, the list followed it (`android-06-typing.png`) |
| Photo tap | lightbox, pinch to 2.5×, drag down at 1× closes it (`android-09-lightbox.png`) |
| Final StrictMode run | open, send ("Message sent"), photo picker and back (no `GET /v1/conversation` reload: only `messages?after`, read, device update, unread), logout ("Session revocation completed"), relaunch ("Device registered"), open. `StrictMode`/`FATAL EXCEPTION` lines: 0 |

Not verified live: double tap in the lightbox (the device CLI's double tap did not reach Compose as one; a tap fix for
the zoomed state is in, unit-untestable without `ui-test`), predictive back over the lightbox.

## Part 3: side-by-side

Both apps had the same published appearance (Prisma, like `persistChatAppearance(publish)`):
`{"colors":{"accent":{"light":"#635BFF","dark":"#A59FFF"}},"showsCloseButton":true,"closeIcon":"xmark",
"welcomeIcon":{"symbol":"sparkles"},"visibility":{"pushStatus":false}}`; afterwards both App IDs were reset to no
published appearance (the Android one previously had the B2 "Android care" test appearance) and the scratch dashboard
sessions were deleted. The Android project's `androidPackageName` was set to the example's package for review cards.

iOS app: the brief's Flutter example could not run, because the Dart VM on this Mac hangs at start
(`dart --version` never returns, inside and outside the sandbox). The iOS SDK's own `Examples/Basic` was built instead
with a temporary, reverted edit (`configure(appId:serviceURL:)` with the Flutter test App ID and one `order` screen);
it registers the paywall `support_chat`.

| Pair | Android | iOS |
| --- | --- | --- |
| Welcome | android-01-welcome | ios-01-welcome |
| Conversation (outgoing, incoming, photo, timestamp, Sent) | android-02-conversation | ios-02-conversation |
| Review, paywall, web link, screen link cards | android-03-cards | ios-03-cards |
| Typing | android-06-typing | ios-06-typing |
| Dark | android-07-dark | — (the simulator stayed light: the device CLI and `simctl ui appearance dark` both reported dark, the app did not change) |
| Keyboard open | android-08-keyboard | — (the simulator's hardware keyboard kept the software keyboard hidden) |

Differences, with the decision:

1. Title chip and close button: iOS 26 draws Liquid Glass with a soft shadow; Android draws the flat secondary fill
   and the iOS-18 dismiss fill. Stays: Material has no glass material (C1 chose the flat look).
2. Composer: iOS 26 glass capsule and photo button without a border; Android white with a hairline, the iOS 18
   metrics. Stays, same reason.
3. Symbols: Material Symbols instead of SF Symbols (sparkles, photo, star, gift/redeem, link, chevron, arrow). The
   Material glyphs sit inside a 24-unit box with padding, so card icons at 12 dp read about 15 % smaller than SF
   Symbols at 12 pt and the chevron is lighter. Stays for now: a per-glyph optical scale would also change remote card
   icon sizes; see open questions.
4. System font: SF Pro vs Roboto, same sizes. Platform.
5. Review button text: "Rate on Google Play" vs "Rate on the App Store". By design (SPEC.md).
6. Sheet top: iOS leaves a small gap below the status bar and a larger corner radius; Android's Material sheet starts
   at the status bar. Platform convention. Behind the status bar both now show the dimmed host app (changed: the black
   strip, see Part 2).
7. Bubbles, radius 16, padding 10/14, grouping, the "Wed 4:43 PM" timestamp, Sent, the photo width (70 %), cards
   (button height ≈ 48, full width, text card around it), the typing bubble and the header fade: no visible
   difference.
8. Lightbox: Android's dialog window first stopped below the status bar (changed: `FLAG_LAYOUT_NO_LIMITS`, d940ff0).
   Android also closes by dragging, which iOS's full-screen cover does not offer (kept as the Android convention, as
   briefed).
9. Paywall handler: on Android the chat is an activity above the host's screens, so a host dialog opened from
   `onPaywall` appears under the sheet. The example now calls `Reception.closeChat()` first; iOS hosts present over the
   chat instead. Documentation item for the Android guide.

## Part 4: example

`ExampleFeatures.kt` registers `pro_monthly` (source App, one product) and `spring_sale`, screens `settings` and
`order` (value "Order number"); `onPaywall` and the screens show a dialog. Home has a "Set language override (de)"
switch and a "Chat appearance: System/Light/Dark" button (`appearance.preferredColorScheme`). StrictMode stays on.
