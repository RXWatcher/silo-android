#!/usr/bin/env bash
# Silo TV emulator sweep.
#
#   scripts/tv-e2e/run.sh player            # one suite
#   scripts/tv-e2e/run.sh all               # every suite
#   NO_INSTALL=1 scripts/tv-e2e/run.sh player
#
# Assumes the app is already signed in against the test server: the sweep drives
# playback, it does not provision an account.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/scripts/tv-e2e"
PKG="org.siloserver.silo"
ACTIVITY="$PKG/.tv.MainTvActivity"
APK="${APK:-$ROOT/androidTvApp/build/outputs/apk/debug/androidTvApp-arm64-v8a-debug.apk}"

DEVICE="${DEVICE:-$(adb devices | awk '/emulator/{print $1; exit}')}"
if [ -z "$DEVICE" ]; then
  echo "No emulator attached. Start one:  \$ANDROID_HOME/emulator/emulator -avd Silo_TV" >&2
  exit 2
fi

OUT_DIR="$HERE/out/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"
export DEVICE OUT_DIR PKG ACTIVITY

# shellcheck source=lib/drive.sh
. "$HERE/lib/drive.sh"
# shellcheck source=lib/evidence.sh
. "$HERE/lib/evidence.sh"

echo "device : $DEVICE"
echo "out    : $OUT_DIR"

if [ "${NO_INSTALL:-0}" != "1" ]; then
  [ -f "$APK" ] || { echo "APK missing: $APK (./gradlew :androidTvApp:assembleDebug)" >&2; exit 2; }
  echo "install: $(basename "$APK")"
  adbx install -r "$APK" >/dev/null 2>&1 || { echo "install failed" >&2; exit 2; }
fi

# SUBDIAG is read once per process and does not survive a reboot: set it, stop
# the app, and only then launch, or every trace assertion silently passes empty.
adbx shell setprop log.tag.SUBDIAG DEBUG
adbx shell am force-stop "$PKG"
[ "$(adbx shell getprop log.tag.SUBDIAG | tr -d '\r')" = "DEBUG" ] \
  || echo "warning: SUBDIAG gate not set; trace assertions will be blind" >&2

SUITES_RUN=""
run_suite() {
  local s="$1"
  [ -f "$HERE/suites/$s.sh" ] || { echo "no such suite: $s" >&2; return 1; }
  echo ""
  echo "═══ suite: $s"
  SUITES_RUN="$SUITES_RUN $s"
  # shellcheck disable=SC1090
  . "$HERE/suites/$s.sh"
}

case "${1:-all}" in
  all) for f in "$HERE"/suites/*.sh; do run_suite "$(basename "$f" .sh)"; done ;;
  *)   run_suite "$1" ;;
esac

export SUITES_RUN
write_report
exit $(( FAILURES > 0 ? 1 : 0 ))
