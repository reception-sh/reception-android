#!/usr/bin/env bash
# Runs every automated check that must pass before a release, in order, and stops at the first failure.
# Run from the repository root on a clean working tree: tool/release_check.sh
# Needs JDK 17 and the Android SDK (JAVA_HOME, ANDROID_HOME) and python3. Publishes to Maven Local (~/.m2) to inspect
# the POM; nothing leaves the machine. Manual checks that this script does not cover are listed in CONTRIBUTING.md.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
[ -f settings.gradle.kts ] && [ -d reception ] || { echo "Run from the reception-android repository root." >&2; exit 1; }

logs="$root/build/release_check"
# The newest Compose BOM on Google Maven; the Compose compatibility step runs the SDK on it. Bump it as CONTRIBUTING.md
# (Compose compatibility) describes.
newest_compose_bom=2026.09.00
summary=""
current=""
finish() {
  status=$?
  if [ "$status" -ne 0 ] && [ -n "$current" ]; then
    summary="$summary
  FAIL  $current"
  fi
  echo
  echo "== Summary"
  if [ -n "$summary" ]; then printf '%s\n' "$summary" | sed '1{/^$/d;}'; fi
  if [ "$status" -eq 0 ]; then echo "All checks passed."; else echo "Release check failed." >&2; fi
}
trap finish EXIT

step() {
  current="$1"
  echo
  echo "== $1"
}
pass() {
  summary="$summary
  ok    $current"
  current=""
}

# Runs Gradle with its output in $logs/<name>.log and fails on any warning line, Kotlin's (`w: `), lint's, AGP's or
# Gradle's deprecations. The one line allowed is the SDK-manager notice that newer command-line tools wrote the SDK's
# package XML; it is about the machine's SDK installation, not the build.
gradle() {
  local name=$1
  shift
  local log="$logs/$name.log"
  ./gradlew --console=plain --warning-mode all "$@" 2>&1 | tee "$log"
  local warnings
  warnings=$(grep -inE '^w: |warning|deprecat' "$log" |
    grep -vE 'This version only understands SDK XML versions up to [0-9]+ but an SDK XML file of version [0-9]+ was encountered' |
    grep -vE '^[0-9]+:(> Task|BUILD)' || true)
  if [ -n "$warnings" ]; then
    echo "Warnings in the build output ($log):" >&2
    echo "$warnings" >&2
    exit 1
  fi
}

mkdir -p "$logs"

step "clean working tree"
dirty=$(git status --porcelain)
if [ -n "$dirty" ]; then
  echo "The working tree has uncommitted changes; commit or stash them first:" >&2
  echo "$dirty" >&2
  exit 1
fi
pass

step "version agreement"
version=$(sed -nE 's/.*const val current: String = "([^"]+)".*/\1/p' reception/src/main/kotlin/sh/reception/Version.kt)
[ -n "$version" ] || { echo "SDKVersion.current not found in Version.kt." >&2; exit 1; }
echo "Version.kt: $version"
changelog=$(sed -nE 's/^## \[([^]]+)\].*/\1/p' CHANGELOG.md | head -1)
echo "CHANGELOG.md, top entry: $changelog"
[ "$changelog" = "$version" ] || { echo "The top CHANGELOG.md entry must be [$version]." >&2; exit 1; }
readme=$(grep -oE 'sh\.reception:reception:[0-9A-Za-z.+-]+' README.md | sort -u)
echo "README.md: $readme"
[ "$readme" = "sh.reception:reception:$version" ] || { echo "README.md must install sh.reception:reception:$version." >&2; exit 1; }
pass

step "tool/sync_strings.py --check"
python3 tool/sync_strings.py --check
pass

step "library: clean, release build, lint, unit tests, API check"
gradle library clean :reception:assembleRelease :reception:lint :reception:test :reception:apiCheck
lint_report=reception/build/reports/lint-results-debug.txt
if [ -f "$lint_report" ]; then
  grep -q "No issues found" "$lint_report" || { cat "$lint_report" >&2; exit 1; }
fi
pass

step "example: release build (R8) with Firebase and Billing"
gradle example :example:assembleRelease
pass

step "example: bare release build (R8) without Firebase and Billing"
gradle example-bare -PreceptionExampleBare=true :example:assembleRelease
pass

step "Compose compatibility: unit tests and example on BOM $newest_compose_bom"
# The library compiles against its own BOM; hosts run it on theirs. A signature that a newer Compose removed fails a
# test or the example here with NoSuchMethodError (see the root build.gradle.kts); the binary check finds such a call
# also on paths the tests do not reach.
gradle compose-compatibility -PreceptionComposeBom="$newest_compose_bom" :reception:test :example:assembleDebug \
  :reception:composeCompatibilityClasspath
python3 tool/compose_binary_check.py reception/build/tmp/kotlin-classes/debug \
  reception/build/compose-compatibility/classpath.txt
pass

step "publishToMavenLocal and the POM"
gradle publish :reception:publishToMavenLocal
artifacts="$HOME/.m2/repository/sh/reception/reception/$version"
for file in "reception-$version.aar" "reception-$version-sources.jar" "reception-$version-javadoc.jar" \
  "reception-$version.module" "reception-$version.pom"; do
  [ -s "$artifacts/$file" ] || { echo "Missing in Maven Local: $artifacts/$file" >&2; exit 1; }
done
python3 - "$artifacts/reception-$version.pom" "$version" <<'PYTHON'
import sys
import xml.etree.ElementTree as ET

path, version = sys.argv[1], sys.argv[2]
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
pom = ET.parse(path).getroot()
text = lambda element, query: (element.findtext(query, namespaces=ns) or "").strip()
problems = []

def expect(label, actual, wanted):
    print(f"{label}: {actual}")
    if actual != wanted:
        problems.append(f"{label} is {actual!r}, expected {wanted!r}")

expect("coordinates", f"{text(pom, 'm:groupId')}:{text(pom, 'm:artifactId')}:{text(pom, 'm:version')}",
       f"sh.reception:reception:{version}")
expect("packaging", text(pom, "m:packaging"), "aar")
expect("license", text(pom, "m:licenses/m:license/m:name"), "MIT License")
expect("scm", text(pom, "m:scm/m:url"), "https://github.com/reception-sh/reception-android")
for field in ("m:name", "m:description", "m:url", "m:developers/m:developer/m:name",
              "m:scm/m:connection", "m:scm/m:developerConnection"):
    if not text(pom, field):
        problems.append(f"{field} is missing")

dependencies = {}
for dependency in pom.findall("m:dependencies/m:dependency", ns):
    key = f"{text(dependency, 'm:groupId')}:{text(dependency, 'm:artifactId')}"
    dependencies[key] = text(dependency, "m:scope") or "compile"
for key, scope in sorted(dependencies.items()):
    print(f"  {scope:8} {key}")

# ReceptionChatView exposes Modifier, so the Compose artifacts are `api` (compile scope), and so are coroutines
# (unreadCount and isChatOpen are StateFlows) and the Kotlin standard library the Kotlin plugin adds; everything else
# stays runtime. Firebase and Billing are compileOnly and must not appear; nor may test libraries.
api = {"androidx.compose.ui:ui", "androidx.compose.foundation:foundation", "androidx.compose.material3:material3",
       "org.jetbrains.kotlinx:kotlinx-coroutines-android", "org.jetbrains.kotlin:kotlin-stdlib"}
for key in api:
    if dependencies.get(key) != "compile":
        problems.append(f"{key} must be a compile (api) dependency, is {dependencies.get(key)}")
for key, scope in dependencies.items():
    if key not in api and scope != "runtime":
        problems.append(f"{key} must be a runtime (implementation) dependency, is {scope}")
    if key.startswith(("com.google.firebase:", "com.android.billingclient:", "junit:", "org.robolectric:")) \
            or key.endswith(":mockwebserver") or key.endswith(":kotlinx-coroutines-test"):
        problems.append(f"{key} must not be a dependency")
boms = [d for d in pom.findall("m:dependencyManagement/m:dependencies/m:dependency", ns)]
if not any(text(d, "m:artifactId") == "compose-bom" and text(d, "m:scope") == "import" for d in boms):
    problems.append("the Compose BOM is not imported in dependencyManagement")

if problems:
    print("\n".join(f"POM: {p}" for p in problems), file=sys.stderr)
    sys.exit(1)
PYTHON
pass

step "signing configuration"
# The Central Portal requires signed artifacts. Without a key (every machine but the release machine) this only says
# so; with one in ~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_* variables, the publication is signed locally.
properties="$HOME/.gradle/gradle.properties"
if { [ -f "$properties" ] && grep -qE '^(signingInMemoryKey|signing\.keyId)=' "$properties"; } ||
  [ -n "${ORG_GRADLE_PROJECT_signingInMemoryKey:-}" ] || [ -n "${ORG_GRADLE_PROJECT_signing_keyId:-}" ]; then
  gradle signing :reception:signMavenPublication
  ls reception/build/libs/*.asc reception/build/publications/maven/*.asc 2>/dev/null || true
else
  echo "Skipped: no signing key configured (signingInMemoryKey or signing.keyId in $properties)."
  echo "The release machine needs one before ./gradlew publishToMavenCentral; see CONTRIBUTING.md."
fi
pass
