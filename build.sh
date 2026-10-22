#!/usr/bin/env bash
# build.sh — build the open-source caniOn Rebirth client from source.
#
# Requirements: a JDK (17+) and a Kotlin compiler.
#   * The Kotlin compiler can be on PATH, in $KOTLIN_HOME, or in ./tools/kotlinc
#   * If it is missing, this script downloads the official release (needs network)
#
# What it builds:
#   app/core  -> the protocol engine (pure JVM, no dependencies)
#   app/cli   -> a command-line client (pure JVM, no dependencies)
#   app/app   -> the Android application (only if the Android SDK is available)
#
# Usage:
#   ./build.sh              build core + cli, run the tests
#   ./build.sh --android    also build the Android APK (needs ANDROID_HOME)
#   ./build.sh --clean      remove build output first
#
# Nothing here needs the original caniOn APK.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$ROOT/build"
KOTLIN_VERSION="${KOTLIN_VERSION:-2.1.0}"
JUNIT_VERSION="4.13.2"
HAMCREST_VERSION="1.3"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
say()  { printf '%s==>%s %s\n' "$BOLD" "$OFF" "$*"; }
ok()   { printf '%s  ok%s %s\n' "$GREEN" "$OFF" "$*"; }
warn() { printf '%swarn%s %s\n' "$YELLOW" "$OFF" "$*"; }
die()  { printf '%sfail%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }

WANT_ANDROID=0
CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --android) WANT_ANDROID=1 ;;
    --clean)   CLEAN=1 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) die "unknown option: $arg" ;;
  esac
done

[ "$CLEAN" = 1 ] && rm -rf "$BUILD"

# --------------------------------------------------------------- toolchain
say "checking the toolchain"

command -v javac >/dev/null 2>&1 || die "javac not found — install a JDK 17+"
JAVA_MAJOR="$(javac -version 2>&1 | sed -E 's/javac ([0-9]+).*/\1/')"
[ "$JAVA_MAJOR" -ge 17 ] || die "JDK 17+ required (found $JAVA_MAJOR)"
ok "javac $JAVA_MAJOR"

KOTLINC=""
if command -v kotlinc >/dev/null 2>&1; then
  KOTLINC="$(command -v kotlinc)"
elif [ -n "${KOTLIN_HOME:-}" ] && [ -x "$KOTLIN_HOME/bin/kotlinc" ]; then
  KOTLINC="$KOTLIN_HOME/bin/kotlinc"
elif [ -x "$ROOT/tools/kotlinc/bin/kotlinc" ]; then
  KOTLINC="$ROOT/tools/kotlinc/bin/kotlinc"
else
  say "downloading the Kotlin compiler ($KOTLIN_VERSION)…"
  mkdir -p "$BUILD" "$ROOT/tools"
  URL="https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$URL" -o "$BUILD/kotlinc.zip"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$URL" -O "$BUILD/kotlinc.zip"
  else
    die "no curl or wget, and no Kotlin compiler found"
  fi
  unzip -q -o "$BUILD/kotlinc.zip" -d "$ROOT/tools"
  KOTLINC="$ROOT/tools/kotlinc/bin/kotlinc"
  rm -f "$BUILD/kotlinc.zip"
fi
ok "kotlinc: $KOTLINC"

# Kotlin needs to find its own standard library; KOTLIN_HOME must be set.
if [ -z "${KOTLIN_HOME:-}" ]; then
  KOTLINC_DIR="$(cd "$(dirname "$KOTLINC")/.." && pwd)"
  if [ -d "$KOTLINC_DIR/lib" ]; then export KOTLIN_HOME="$KOTLINC_DIR"; fi
fi

mkdir -p "$BUILD"

# ------------------------------------------------------------------- core
say "compiling the protocol core (app/core)"
"$KOTLINC" -nowarn -d "$BUILD/core" "$ROOT/app/core/src/main/kotlin" || die "core compilation failed"
ok "build/core"

# ------------------------------------------------------------------- tests
say "compiling the tests (app/core/src/test)"
TEST_LIBS="$BUILD/testlibs"
mkdir -p "$TEST_LIBS"
fetch() {
  local url="$1" out="$2"
  [ -f "$out" ] && return 0
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$url" -o "$out"
  elif command -v wget >/dev/null 2>&1; then wget -q "$url" -O "$out"
  else die "no curl or wget to fetch test dependencies"; fi
}
fetch "https://repo1.maven.org/maven2/junit/junit/$JUNIT_VERSION/junit-$JUNIT_VERSION.jar" "$TEST_LIBS/junit.jar"
fetch "https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/$HAMCREST_VERSION/hamcrest-core-$HAMCREST_VERSION.jar" "$TEST_LIBS/hamcrest.jar"
ok "test dependencies"

"$KOTLINC" -nowarn -cp "$BUILD/core:$TEST_LIBS/junit.jar" -d "$BUILD/test" \
  "$ROOT/app/core/src/test/kotlin" || die "test compilation failed"
ok "build/test"

# --------------------------------------------------------------- run tests
say "running the unit tests"
STDLIB="$(find "${KOTLIN_HOME:-$ROOT/tools/kotlinc}/lib" -name 'kotlin-stdlib.jar' | head -1)"
if [ -z "$STDLIB" ]; then
  STDLIB="$(find "$BUILD/core" -name 'kotlin-stdlib*.jar' | head -1)"
fi
CP="$BUILD/core:$BUILD/test:$TEST_LIBS/junit.jar:$TEST_LIBS/hamcrest.jar"
[ -n "$STDLIB" ] && CP="$CP:$STDLIB"
if command -v java >/dev/null 2>&1 && java -cp "$CP" org.junit.runner.JUnitCore org.caniorebirth.zev.ZevProtocolTest; then
  ok "all tests passed"
else
  die "tests failed"
fi

# -------------------------------------------------------------------- cli
say "compiling the command-line client (app/cli)"
"$KOTLINC" -nowarn -cp "$BUILD/core" -d "$BUILD/cli" "$ROOT/app/cli/src/main/kotlin" || die "cli compilation failed"
ok "build/cli"

cat > "$BUILD/caniOn-cli" <<EOF
#!/usr/bin/env bash
exec java -cp "$BUILD/cli:$BUILD/core${STDLIB:+:$STDLIB}" org.caniorebirth.cli.Main "\$@"
EOF
chmod +x "$BUILD/caniOn-cli"
ok "build/caniOn-cli"

# ----------------------------------------------------------------- android
if [ "$WANT_ANDROID" = 1 ]; then
  say "building the Android application (app/app)"
  if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
    warn "ANDROID_HOME / ANDROID_SDK_ROOT is not set — skipping the Android build."
    warn "set it and re-run with --android to produce the APK."
  elif [ ! -x "$ROOT/app/gradlew" ]; then
    warn "no Gradle wrapper in app/ — skipping the Android build."
  else
    (cd "$ROOT/app" && ./gradlew --no-daemon :app:assembleDebug) || die "Android build failed"
    ok "app/app/build/outputs/apk/debug/app-debug.apk"
  fi
fi

echo
printf '%sBuild complete.%s\n' "$GREEN" "$OFF"
printf '  library     : %s\n' "$BUILD/core"
printf '  cli         : %s\n' "$BUILD/caniOn-cli"
printf '  run it with : %s/caniOn-cli --help\n' "$BUILD"
