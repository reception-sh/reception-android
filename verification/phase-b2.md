# Phase B2 verification (2026-11-04)

Public appearance API, remote appearance, images and uploads, team profiles, link policy. No chat UI yet (Phase C).
Environment: `JAVA_HOME=/opt/homebrew/opt/openjdk@17`, `ANDROID_HOME=~/Library/Android/sdk`, emulator-5554
(`reception_pixel8`, Android 16), local backend on branch `android-push` at `http://localhost:3123`.

## Build, lint, unit tests

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all --rerun-tasks
BUILD SUCCESSFUL in 20s
116 actionable tasks: 116 executed
$ grep -ci warning build.log
0
$ tool/sync_strings.py --check
strings up to date (49 files)
```

Unit tests, debug and release variants each (all 0 failures):

| Suite | Tests | Covers |
| --- | --- | --- |
| RemoteAppearanceTest | 8 | documented example decodes, unknown fields ignored; wrong JSON type, `null` visibility value, schema 2, negative revision and > 64 KiB reject; visibility over legacy `hidden`, legacy `"none"`; explicit Show restores the built-in welcome icon over a local `hidden`, remote symbol/image precedence, undrawable symbol keeps local; remote `closeIcon` clears a local `closeImage`, invalid one keeps it, remote close image needs a UUID; `{x:0,y:0}` overrides a local offset, clamping, explicit radius 0; translations: app tag, then supported match, case-insensitive, no English fallback, blank/overlong skipped; per-mode color inheritance, explicit `fontDesign` clears `fontFamily`, `system` clears a host scheme, host value never mutated |
| HostAppearanceReportTest | 4 | every field name and nesting of `appAppearanceSchema`, values (`#RRGGBB` pairs, rounded ints, `fontFamily: "custom"`, `closeImage: true`), default report = setting + theme accent, hidden/image welcome icons; checked list compared with `../reception/src/lib/app-appearance.ts` |
| AppearanceSymbolsTest | 2 | a drawable for every dashboard symbol and built-in icon; checked list compared with `appearance-symbols.ts` (arrays plus `defaultSymbols`) |
| AppearanceConfigurationTest (Robolectric, MockWebServer) | 2 | `GET /v1/appearance?platform=android`; fetched snapshot inactive until `activate()`; a new process loads envelope + ETag and sends `If-None-Match`, 304 keeps it; 5-minute spacing; a 500 and an invalid envelope keep the snapshot |
| ImageUploaderTest (Robolectric native graphics) | 4 | 4000×3000 noise JPEG (> 1 MiB) → 2048×1536, ≤ 1 MiB, decodable; EXIF `ROTATE_90` 400×200 → 200×400 with the red half on top; reservation has exactly the six input fields, checksum and size match the PUT body, PUT carries exactly the returned headers (no `Authorization`), 412 with `If-Match` counts as uploaded, progress ends at 1.0; version-5 request IDs |
| AttachmentImageLoaderTest (Robolectric, MockWebServer) | 3 | storage 403 → one authenticated resolve → one download → second 403 → Failed (3 requests); Retry resolves first, then loads; 404 fails without resolving; last disappearing view cancels, a loaded photo stays, invalidation clears |
| LinkPolicyTest | 3 | iOS scheme allowlist and blocklist, web links excluding `play.google.com`, host links through the app's own handlers cached per scheme and host |

Earlier suites unchanged (ReceptionLocalization 3, ReceptionStrings 5, Theme 1, IdentityToken 5, RequestRules 8,
SecureStore 4, DeviceStore 4, SessionAuthorization 5): 61 tests per variant.

Mutation checks (reverted afterwards): without the "explicit Show restores the built-in icon" rule,
`explicitShowRestoresTheBuiltInWelcomeIconOverALocallyHiddenOne` fails; with a second resolve after the second 403,
`aSecond403AfterOneResolveFailsAndRetryResolvesFirst` fails (4 requests instead of 3).

The loader suite was flaky at first (about 1 in 3 full runs hung for a minute). The cause was the test, not the
loader: the loader holds its session weakly, like iOS, and the test kept no strong reference, so a GC between the 403
and the resolve made the session disappear. The test now holds its sessions; 8 consecutive `--rerun` runs passed.

## Live check on the emulator

Example rebuilt and installed (`adb install -r example-debug.apk` → `Success`), launched with the device CLI
(`agent-device open sh.reception.receptionAndroidExample --relaunch`), logcat filtered to `Reception`.

```
# first launch, empty cache: 200
I Reception: [Reception] INFO Configured, SDK 1.0.0
D Reception: [Reception] DEBUG Remote appearance refreshed, revision 0
D Reception: [Reception] DEBUG Access token refreshed
D Reception: [Reception] DEBUG Device updated
# Home, reopen within five minutes: no appearance request (5-minute spacing, as iOS)
D Reception: [Reception] DEBUG Device updated
D Reception: [Reception] DEBUG Unread refresh, 0 unread
# cold relaunch: If-None-Match from the persisted ETag, 304
I Reception: [Reception] INFO Configured, SDK 1.0.0
D Reception: [Reception] DEBUG Remote appearance unchanged
```

`shared_prefs/sh.reception.appearance.xml` then held the revision-0 envelope and its ETag under the SHA-256 of the App ID.

Published appearance: the dashboard signs in with Google only, which this run cannot complete, so the appearance was
published through Prisma exactly like `persistChatAppearance(…, "publish")` (draft and published set, version and
revision incremented), script `publish-appearance.mjs` in the session scratchpad, local database only:

```
published {"colors":{"accent":{"light":"#635BFF","dark":"#A59FFF"}},"text":{"title":{"en":"Android care","de":"Android-Hilfe"}},
  "showsCloseButton":true,"closeIcon":"chevron.down","fontDesign":"serif","welcomeIcon":{"symbol":"sparkles","offset":{"x":0,"y":0}},
  "visibility":{"pushStatus":false}}  → appearanceRevision 1
# relaunch
D Reception: [Reception] DEBUG Remote appearance refreshed, revision 1
# cache: the revision-1 envelope (with the server's mirrored "hidden":["pushStatus"]) and the new ETag
# next relaunch
D Reception: [Reception] DEBUG Remote appearance unchanged
```

Reported host appearance on the Device row (Prisma):
`{"usesRemoteAppearance":true,"colors":{"accent":{"light":"#F5F5F5","dark":"#F5F5F5"}}}`. The example's theme is
`android:Theme.Material.Light.NoActionBar`, whose `colorPrimary` is the platform's light grey; see the open questions in
STATUS.md.

How the published appearance looks cannot be checked before the chat UI exists (Phase C); the resolution is covered
by RemoteAppearanceTest.
