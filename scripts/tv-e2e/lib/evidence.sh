#!/usr/bin/env bash
# Screen recording + report writing. A run must be reviewable without re-running
# it, and two runs must be diffable.

: "${DEVICE:?}"; : "${OUT_DIR:?}"

REC_PID=""

record_start() {
  CURRENT_SCENARIO="$1"
  mkdir -p "$OUT_DIR/video"
  adb -s "$DEVICE" shell screenrecord --time-limit 180 --bit-rate 3000000 \
    "/sdcard/e2e-$1.mp4" >/dev/null 2>&1 &
  REC_PID=$!
  sleep 1
}

record_stop() {
  [ -z "$REC_PID" ] && return 0
  adb -s "$DEVICE" shell pkill -INT screenrecord >/dev/null 2>&1
  wait "$REC_PID" 2>/dev/null
  sleep 2
  adb -s "$DEVICE" pull "/sdcard/e2e-$CURRENT_SCENARIO.mp4" \
    "$OUT_DIR/video/$CURRENT_SCENARIO.mp4" >/dev/null 2>&1
  adb -s "$DEVICE" shell rm -f "/sdcard/e2e-$CURRENT_SCENARIO.mp4" >/dev/null 2>&1
  REC_PID=""
}

scenario() {
  CURRENT_SCENARIO="$1"
  echo ""
  echo "▶ $1"
  record_start "$1"
}

scenario_end() { record_stop; }

write_report() {
  local total_fail=0
  [ -f "$OUT_DIR/failures.jsonl" ] && total_fail=$(wc -l < "$OUT_DIR/failures.jsonl" | tr -d ' ')
  {
    echo "# TV e2e run — $(basename "$OUT_DIR")"
    echo ""
    echo "- device: \`$DEVICE\`"
    echo "- app: $(adb -s "$DEVICE" shell dumpsys package org.siloserver.silo 2>/dev/null | grep -m1 versionName | tr -d ' \r')"
    echo "- suites: ${SUITES_RUN:-?}"
    echo "- failures: **$total_fail**"
    echo ""
    if [ "$total_fail" -gt 0 ]; then
      echo "## Failures"; echo ""
      sed 's/^/    /' "$OUT_DIR/failures.jsonl"
      echo ""
    fi
    echo "## Evidence"; echo ""
    echo "- screenshots: \`steps/\` ($(ls "$OUT_DIR/steps" 2>/dev/null | wc -l | tr -d ' ') files)"
    echo "- video: \`video/\` ($(ls "$OUT_DIR/video" 2>/dev/null | wc -l | tr -d ' ') files)"
    echo "- traces: \`logcat/\`"
  } > "$OUT_DIR/report.md"
  printf '{"failures":%s,"out":"%s"}\n' "$total_fail" "$OUT_DIR" > "$OUT_DIR/result.json"
  echo ""
  echo "report: $OUT_DIR/report.md  (failures: $total_fail)"
  return 0
}
