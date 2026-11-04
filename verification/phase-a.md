# Phase A verification (2026-11-04)

Environment: `JAVA_HOME=/opt/homebrew/opt/openjdk@17`, `ANDROID_HOME=/Users/andi/Library/Android/sdk`, Gradle 8.9
wrapper, emulator `reception_pixel8` (Android 16) as `emulator-5554`.

## Build, lint, tests

```
$ ./gradlew :reception:assembleRelease :reception:lint :reception:test :example:assembleDebug --warning-mode all --rerun-tasks --no-build-cache
BUILD SUCCESSFUL in 6s
136 actionable tasks: 136 executed
$ grep -iE "warn|^w:|^e:|deprecat" <build output>
(no lines)
```

Lint report: `No issues found.` Unit tests: `ReceptionLocalizationTest` 3 tests, `ThemeTest` 1 test, 0 failures, 0
errors (debug and release unit-test variants).

## Strings

```
$ tool/sync_strings.py --check
strings up to date (51 files)
```

## Local publication

```
$ ./gradlew :reception:publishToMavenLocal
BUILD SUCCESSFUL in 24s
```

Produced `reception-1.0.0.aar`, `-sources.jar`, `-javadoc.jar`, `.module` and a `.pom` with the name, description,
URL, MIT license, developer and SCM from SPEC.md, unsigned (no signing properties present). The local copy was removed
afterwards.

## Example app on the emulator

```
$ adb -s emulator-5554 install -r example/build/outputs/apk/debug/example-debug.apk
Performing Streamed Install
Success
$ adb -s emulator-5554 shell am start -n sh.reception.receptionAndroidExample/.MainActivity
Starting: Intent { cmp=sh.reception.receptionAndroidExample/.MainActivity }
$ adb -s emulator-5554 exec-out screencap -p > verification/phase-a.png
```

`phase-a.png` shows the "Support" button centered with "Reception SDK 1.0.0" below it. The UI dump agrees:
`text="Support" … bounds="[475,1138][606,1191]"` and `text="Reception SDK 1.0.0"`. Tapping the button logged
`I ReceptionExample: Support tapped`. Logcat also shows
`I FirebaseInitProvider: FirebaseApp initialization successful`, so the google-services plugin was applied.
