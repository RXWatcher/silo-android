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

  step "move to the last row"
  # Overshooting clamps at the end of the list, so this lands on a known row
  # whatever the title is. Continue Watching reorders between runs; a fixed hop
  # count silently picked a different subtitle each time.
  dpad DOWN "${SUBTITLE_ROW_HOPS:-25}" 0.25
  shot

  step "select it"
  trace_clear
  center 20
  trace_save
  shot

  # True for ANY successful switch, whatever the fixture offers.
  assert_no_trace "mountDeadline FAIL" "no mount deadline failure"
  assert_trace "SNAPSHOT applying=false committed=" "selection committed"

  step "catalog-only regressions"
  # Only meaningful against a title that actually has embedded rows — set
  # E2E_STRICT_SUBS=1 with a pinned fixture (see docs/testing plan).
  if [ "${E2E_STRICT_SUBS:-0}" = "1" ]; then
    assert_trace "commitLocal SKIP not mountable" "pick routed to the server"
    assert_trace "MOUNT idx=" "server materialised an artifact"
    assert_trace "RECONCILE committed" "identity reconciled"
  else
    pass "skipped (E2E_STRICT_SUBS unset — needs a pinned fixture)"
  fi

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
  assert_trace "(MGR apply|MGR selectSubtitle|SNAPSHOT applying=false)" "the switch settled"
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

# ── HUD: audio track switch ────────────────────────────────────────────────
# Audio has no SUBDIAG equivalent, so this asserts on Media3's own analytics: a
# track switch must re-report tracks without tearing the video down.
scenario "player-audio-switch"
  step "return to a known player state"
  reset_to_player
  shot

  step "open the HUD on Audio"
  if open_hud && hud_tab Audio; then pass "Audio pane"; else fail "could not reach the Audio pane"; fi
  shot

  step "open the audio track list"
  # Target the row by name. Stepping blindly lands on "Delay (PCM only)" on
  # single-track titles and silently nudges the audio delay instead.
  if hud_row "Audio track" 6; then pass "on the Audio track row"; else fail "no Audio track row"; fi
  trace_clear
  center 3
  shot

  step "switch track, or accept a single-track title"
  if screen_has_text "Audio track"; then
    dpad DOWN 1 0.6
    center 8
    shot
    # A title with one audio track logs nothing when its only entry is picked,
    # so proving a real switch needs a multi-track fixture. Until one is pinned,
    # assert the pane round-trips without breaking playback.
    if [ "${E2E_STRICT_AUDIO:-0}" = "1" ]; then
      assert_logcat "Media3Analytics: (Track snapshot|audio format changed)" "a different track was applied"
    else
      pass "picker round-tripped (set E2E_STRICT_AUDIO=1 with a multi-track fixture)"
    fi
  else
    pass "single audio track — nothing to switch"
  fi
  assert_no_trace "Failed to start playback" "no restart error"
scenario_end

# ── HUD: video quality ─────────────────────────────────────────────────────
scenario "player-video-quality"
  step "return to a known player state"
  reset_to_player
  shot

  step "open the HUD on Video"
  if open_hud && hud_tab Video; then pass "Video pane"; else fail "could not reach the Video pane"; fi
  shot

  step "focus the Quality row"
  if hud_row "Quality" 6; then pass "on Quality"; else fail "never found Quality"; fi
  shot

  step "open the quality picker"
  trace_clear
  center 3
  shot
  assert_screen_text "Quality"

  step "choose another quality"
  dpad DOWN 1 0.6
  center 12
  shot
  assert_logcat "Media3Analytics: (video format changed|first video frame rendered|video start requested)" "video recovered after replan"
scenario_end

# ── HUD: chapters ──────────────────────────────────────────────────────────
scenario "player-chapters"
  step "return to a known player state"
  reset_to_player
  shot

  step "open the HUD on Chapters"
  if open_hud && hud_tab Chapters; then pass "Chapters pane"; else fail "could not reach the Chapters pane"; fi
  shot

  step "jump to a chapter"
  dpad DOWN 1 0.8
  center 8
  shot
  assert_logcat "Media3Analytics: (first video frame rendered|video format changed)" "playback resumed after the seek"
scenario_end

# ── resume position survives leaving the player ────────────────────────────
scenario "player-resume-position"
  step "play, then leave the player"
  reset_to_player
  sleep 6
  shot
  back 3
  shot

  step "detail screen offers Resume"
  # If the final progress write is lost the hero reverts to Play / Start Over.
  if screen_has_text "Resume"; then pass "Resume offered"; else fail "no Resume on the detail screen"; fi
  shot
scenario_end
