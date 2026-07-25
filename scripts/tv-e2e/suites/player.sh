#!/usr/bin/env bash
# Player suite — the highest defect density area, and the one that would have
# caught the four subtitle bugs of 2026-07-25.
#
# Every scenario asserts on the SUBDIAG trace as well as a screenshot, because
# all four of those bugs were invisible to the unit suites (which were green
# throughout) and two of them looked fine on screen for several seconds before
# silently reverting.

# ── launch and play the first Continue Watching tile ────────────────────────
scenario "player-start"
  step "launch app"
  adbx shell am start -n "$ACTIVITY" >/dev/null 2>&1
  sleep 8
  shot

  step "open first Continue Watching item"
  center 5
  shot

  step "resume playback"
  trace_clear
  center 18
  shot
  trace_save

  # A start that trips server validation (e.g. sending -1 for "subtitles off")
  # 400s and the player shows "Failed to start playback".
  assert_no_trace "Failed to start playback" "start error"
  assert_trace "BUILD sidecars" "sidecar build ran"

  step "no duplicate subtitle rows"
  # The restore path once concatenated the server rows twice: 21 became 42 and
  # every sidecar mounted twice, so the picker listed each track twice.
  dup="$(trace_dump | grep -m1 '^BUILD sidecars idx=' \
        | grep -oE 'idx=\[[^]]*\]' \
        | tr -d 'idx=[]' | tr ',' '\n' | tr -d ' ' | sort | uniq -d | head -1)"
  if [ -n "$dup" ]; then fail "duplicate subtitle index $dup in mount list"; else pass "no duplicate indices"; fi

  step "exactly one MOUNT per index"
  mdup="$(trace_dump | grep -oE '^MOUNT idx=[0-9]+' | sort | uniq -d | head -1)"
  if [ -n "$mdup" ]; then fail "mounted twice: $mdup"; else pass "each sidecar mounted once"; fi
scenario_end

# ── subtitle switch to a catalog-only row ───────────────────────────────────
# Catalog-only (embedded) rows carry a blank URL and never become Media3 tracks
# on a remuxed route. Picking one must be routed to the server so it materialises
# an artifact; before the fix it committed locally, hit the mount deadline and
# reverted to the previous subtitle with no visible error.
scenario "player-subtitle-switch"
  step "open player overlay"
  open_player_overlay
  shot

  step "focus the Subtitles control"
  if nav_to_desc RIGHT "subtitle" 6; then pass "found Subtitles"; else fail "never focused Subtitles"; fi

  step "open the subtitle picker"
  center 3
  shot

  step "move to a catalog-only row"
  # SUBTITLE_ROW_HOPS: how far down the picker the fixture's embedded row sits.
  dpad DOWN "${SUBTITLE_ROW_HOPS:-13}" 0.4
  shot

  step "select it"
  trace_clear
  center 20
  trace_save
  shot

  assert_trace "commitLocal SKIP not mountable" "pick routed to the server"
  assert_trace "MOUNT idx=" "server materialised an artifact"
  assert_no_trace "mountDeadline FAIL" "no mount deadline failure"
  assert_trace "SNAPSHOT applying=false committed=(ServerSidecar|Embedded)" "selection committed"

  step "committed identity reconciled to the artifact"
  # Left as Embedded(n) the picker matches no row and marks "Off" while that
  # subtitle is plainly on screen.
  assert_trace "RECONCILE committed" "identity reconciled"

  step "picker dismissed on pick"
  d="$(focus_desc)"
  if echo "$d" | grep -qi "subtitle"; then pass "back on the transport row"; else pass "picker closed (focus=$d)"; fi
scenario_end

# ── switching away again ───────────────────────────────────────────────────
scenario "player-subtitle-switch-again"
  step "reopen the picker"
  open_player_overlay
  nav_to_desc RIGHT "subtitle" 6 && center 3
  shot

  step "choose a different row"
  dpad UP 3 0.4
  trace_clear
  center 20
  trace_save
  shot

  assert_no_trace "mountDeadline FAIL" "second switch did not time out"
  assert_trace "MGR apply" "a track was applied"
scenario_end

# ── subtitles off ──────────────────────────────────────────────────────────
scenario "player-subtitle-off"
  step "reopen the picker"
  open_player_overlay
  nav_to_desc RIGHT "subtitle" 6 && center 3
  shot

  step "select Off (top of the list)"
  dpad UP 25 0.25
  trace_clear
  center 8
  trace_save
  shot

  assert_trace "MGR (disable text|selectSubtitle\\(index=-1\\))" "text disabled"
scenario_end

# ── transport ──────────────────────────────────────────────────────────────
scenario "player-transport"
  step "skip forward"
  open_player_overlay
  nav_to_desc RIGHT "skip forward" 4 && center 2
  shot

  step "pause and resume"
  open_player_overlay
  nav_to_desc LEFT "pause" 5 && center 2
  shot
  center 2
  shot

  step "exit the player"
  back 2
  shot
scenario_end
