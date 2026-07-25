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

# --- HUD ("Info and options") ----------------------------------------------
# HUD tabs are exposed as TEXT, not content-desc (unlike the transport row and
# unlike picker rows, which expose neither). Order: Info Video Audio Subtitles
# Chapters Route Stats.
ui_text_dump() {
  adbx shell uiautomator dump /sdcard/e2e-t.xml >/dev/null 2>&1
  adbx shell cat /sdcard/e2e-t.xml 2>/dev/null | tr '<' '\n<' | grep -oE 'text="[^"]*"' | sed 's/text="//;s/"$//'
}

screen_has_text() { ui_text_dump | grep -qiF "$1"; }

assert_screen_text() {
  if screen_has_text "$1"; then pass "screen shows '$1'"; else fail "screen missing '$1'"; fi
}

open_hud() {
  open_player_overlay
  nav_to_desc RIGHT "info and options" 6 || return 1
  center 3
}

# Move to a HUD tab. Order is as the app renders it — verified on screen, not
# assumed: Info Stats Video Audio Subtitles Chapters. Tabs activate on focus.
hud_tab() {
  case "$1" in
    Info) n=0;; Stats) n=1;; Video) n=2;; Audio) n=3;;
    Subtitles) n=4;; Chapters) n=5;;
    *) return 1;;
  esac
  [ "$n" -gt 0 ] && dpad RIGHT "$n" 0.5
  sleep 1
  # Never act on a pane we did not land on.
  screen_has_text "$1" || return 1
  return 0
}

# Step down the pane's LEFT column only. Pressing RIGHT here lands in the
# secondary column (timers), where a blind CENTER once armed a sleep timer
# mid-run and polluted every scenario after it.
hud_row() {
  local want="$1" max="${2:-8}"
  dpad DOWN 1 0.8
  for _ in $(seq 1 "$max"); do
    if screen_has_text "$want"; then return 0; fi
    dpad DOWN 1 0.5
  done
  return 1
}

# Leave the player and come back to a known state, so one scenario's side
# effects cannot leak into the next.
reset_to_player() {
  back 2; back 2
  adbx shell am force-stop "$PKG" >/dev/null 2>&1
  adbx shell am start -n "$ACTIVITY" >/dev/null 2>&1
  sleep 8
  center 5      # first Continue Watching tile
  center 18     # resume
}

# Any-tag logcat assertion, for areas SUBDIAG does not cover (audio, video).
assert_logcat() {
  if adbx logcat -d 2>/dev/null | grep -qE "$1"; then pass "log: $2"; else fail "expected log $2 (/$1/)"; fi
}
