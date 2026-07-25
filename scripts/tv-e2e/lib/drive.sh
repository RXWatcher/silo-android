#!/usr/bin/env bash
# D-pad driving with focus assertions.
#
# Two rules this file exists to enforce, both learned by getting them wrong:
#   * uiautomator does NOT expose Compose text, so focus is asserted on
#     content-desc. Blind key presses overshoot and land on the wrong row.
#   * `input tap` is ignored on a TV device (no touchscreen). D-pad only.

: "${DEVICE:?DEVICE must be set}"
: "${OUT_DIR:?OUT_DIR must be set}"

STEP_N=0
FAILURES=0
CURRENT_SCENARIO="unknown"

adbx() { adb -s "$DEVICE" "$@"; }

step() {
  STEP_N=$((STEP_N + 1))
  CURRENT_STEP="$(printf '%03d' "$STEP_N")-$(echo "$1" | tr ' /' '__')"
  echo "  · $1"
}

shot() {
  mkdir -p "$OUT_DIR/steps"
  adbx exec-out screencap -p > "$OUT_DIR/steps/${CURRENT_STEP:-000-shot}.png" 2>/dev/null
}

fail() {
  FAILURES=$((FAILURES + 1))
  echo "    FAIL: $1"
  shot
  printf '{"scenario":"%s","step":"%s","error":"%s"}\n' \
    "$CURRENT_SCENARIO" "${CURRENT_STEP:-}" "$(echo "$1" | sed 's/"/\\"/g')" \
    >> "$OUT_DIR/failures.jsonl"
}

pass() { echo "    ok: $1"; }

press() { adbx shell input keyevent "$1" >/dev/null 2>&1; sleep "${2:-0.6}"; }
dpad()  { for _ in $(seq 1 "${2:-1}"); do press "KEYCODE_DPAD_$1" "${3:-0.6}"; done; }
center(){ press KEYCODE_DPAD_CENTER "${1:-1.5}"; }
back()  { press KEYCODE_BACK "${1:-1}"; }

# Focused element's content-desc, or "" when Compose exposes none.
focus_desc() {
  adbx shell uiautomator dump /sdcard/e2e.xml >/dev/null 2>&1
  adbx shell cat /sdcard/e2e.xml 2>/dev/null \
    | tr '<' '\n<' | grep 'focused="true"' \
    | grep -oE 'content-desc="[^"]*"' | tail -1 | sed 's/content-desc="//;s/"$//'
}

# Walk focus in $1 until content-desc matches $2 (case-insensitive), max $3 hops.
nav_to_desc() {
  local dir="$1" want="$2" max="${3:-8}"
  for _ in $(seq 1 "$max"); do
    local d; d="$(focus_desc)"
    if echo "$d" | grep -qi "$want"; then return 0; fi
    press "KEYCODE_DPAD_$dir" 0.5
  done
  return 1
}

# Open the player overlay and act before it auto-hides (~5s).
open_player_overlay() { press KEYCODE_DPAD_DOWN 1; }

# --- trace assertions -------------------------------------------------------
trace_clear() { adbx logcat -c >/dev/null 2>&1; }

trace_dump() {
  adbx logcat -d -s SUBDIAG:D 2>/dev/null | sed 's/^.*SUBDIAG *: //'
}

trace_save() {
  mkdir -p "$OUT_DIR/logcat"
  trace_dump > "$OUT_DIR/logcat/${CURRENT_STEP:-trace}.txt"
}

assert_trace() {
  if trace_dump | grep -qE "$1"; then pass "trace: $2"; else fail "expected trace $2 (/$1/)"; fi
}

assert_no_trace() {
  if trace_dump | grep -qE "$1"; then fail "unexpected trace $2 (/$1/)"; else pass "no $2"; fi
}

assert_focus() {
  local d; d="$(focus_desc)"
  if echo "$d" | grep -qi "$1"; then pass "focus=$d"; else fail "focus was '$d', wanted /$1/"; fi
}
