# Fixes 2: Phase B2 audit and decided items

Run 2026-11-04 on `main` after c9b62c5. Emulator `emulator-5554` (Android 16), local backend `http://10.0.2.2:3123`
(`/v1/health` → `{"ok":true}`), App ID from `.local/app-id.txt`.

## Commits

| Item | Commit | Test |
| --- | --- | --- |
| 1 Host links per complete URL | a386068 | `LinkPolicyTest.answersAreCachedPerCompleteUrl`, `…theCacheKeepsTheMostRecentEntries`; `LinkPolicyHandlersTest` (Robolectric intent filters: a path-prefixed app link, a scheme-only filter) |
| 2 Team profiles off the main thread | 9d36b79 | `TeamProfilesTest.aRestoreOfTwoCachedPhotosPublishesBothWithoutBlocking`, `…teamDetailsBeforeTheRestoreKeepTheirNamesAndUseOnlyTheMatchingCachedPhoto` |
| 3 Dark mode resolution | a0d91dc | `ThemeModeTest` (explicit scheme, host theme, system) |
| 4 Increased contrast | 5a7e5ed | `ThemeModeTest.olderOutgoingMessagesFadeOnlyWithoutIncreasedContrast` |
| 5 Accent in the report | none needed | `HostAppearanceReportTest`; live row below reports no accent |
| 6 Splits | bd51840, 89f3473, 9973e4f, 2f3e7eb | full suite after each split: 80 tests, 0 failures |
| 7 Docs | 057bfbd | — |

Item 1: Android's `IntentFilter.AuthorityEntry.match` compares hosts with `compareToIgnoreCase` (checked in the
Android 15 framework jar Robolectric runs). Only schemes are case-sensitive, so the test asserts that `MYAPP://`
misses and `https://HOST.example/app/…` matches. The cache still keys the exact URL string.

Item 2: the restore was already off the main thread since b1104e8. This round makes the dispatchers injectable,
decodes in one Default block, and lets a late restore supply the photo a pending download is fetching. Cache writes
are chained after the load, so an `update()` that runs before the load cannot rewrite the index from empty entries
and delete the cached photos.

Item 5: no `HostAccent` and no XML theme read remain. `HostAppearanceReport` sends `accent` only from
`accentColor?.let`.

## Build

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all --rerun-tasks
BUILD SUCCESSFUL in 21s
116 actionable tasks: 116 executed
(no other output lines; test results: debug 80 tests, 0 failures; release 80 tests, 0 failures; lint: "No issues found.")
$ tool/sync_strings.py --check
strings up to date (49 files)
```

## Live run, full example, StrictMode with `penaltyDeath`

`adb install -r example-debug.apk`, `pm clear`, `logcat -c`, launch, tap "Identify test user", Back, reopen.
`adb logcat -d | grep -E "Reception:|StrictMode|FATAL|AndroidRuntime"`:

```
11-04 15:08:05.383 11250 11250 I Reception: [Reception] INFO Configured, SDK 1.0.0
11-04 15:08:05.467 11250 11250 D Reception: [Reception] DEBUG Device registration started
11-04 15:08:05.734 11250 11250 D Reception: [Reception] DEBUG Remote appearance refreshed, revision 1
11-04 15:08:05.767 11250 11250 I Reception: [Reception] INFO Device registered
11-04 15:08:05.839 11250 11250 D Reception: [Reception] DEBUG Device updated
11-04 15:08:05.896 11250 11250 D Reception: [Reception] DEBUG Unread refresh, 0 unread
11-04 15:08:11.045 11250 11250 D Reception: [Reception] DEBUG Identity staged
11-04 15:08:13.485 11250 11250 D Reception: [Reception] DEBUG Device updated
11-04 15:08:13.513 11250 11250 D Reception: [Reception] DEBUG Unread refresh, 0 unread
StrictMode lines: 0
```

The backend's newest Android Device row (`psql`):

```
2026-11-04 14:08:05.392|android|1.0.0|android-example-user|Google sdk_gphone64_arm64|{"usesRemoteAppearance": true}
```
