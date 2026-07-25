#!/usr/bin/env bash
# Breadth sweep — every top-level area renders, navigates and survives.
#
# Deliberately shallow: this is the first pass over the areas that have no suite
# of their own yet, so a crash, an ANR or a screen that never loads is caught
# before anyone writes deep coverage for it. Depth belongs in per-area suites
# (see docs/testing/tv-emulator-e2e-plan.md).

crash_check() {
  if adbx logcat -d 2>/dev/null | grep -qE "FATAL EXCEPTION|ANR in $PKG|Force finishing activity $PKG"; then
    fail "crash or ANR in logcat"
  else
    pass "no crash"
  fi
}

# Relaunch and prove we are on Home before driving anything.
#
# Non-negotiable: an earlier version of this suite overshot the nav row into the
# avatar, opened the profile switcher, and the next blind CENTER hit Sign Out.
# Every scenario after that ran against the onboarding screen and one of them
# typed its search term into the server-address field. If Home is not up, the
# rest of the sweep is meaningless AND destructive, so stop.
ensure_home() {
  adbx shell am force-stop "$PKG" >/dev/null 2>&1
  adbx shell am start -n "$ACTIVITY" >/dev/null 2>&1
  sleep 8
  screen_has_text "Continue Watching" && return 0
  shot
  fail "not on Home — the app looks signed out; sign in and re-run"
  return 1
}

# Focus the top nav, then step right from the Search anchor.
#
# Order: Search Home Movies Series "For You" Calendar <avatar>. Search is the
# only nav item exposing a content-desc, so it is the only reliable anchor —
# counting from Home is what put a CENTER on the avatar. Stopping short of the
# avatar is deliberate: the profile scenario handles it, with a guard.
NAV_SEARCH=0; NAV_HOME=1; NAV_MOVIES=2; NAV_SERIES=3; NAV_FORYOU=4; NAV_CALENDAR=5
top_nav() {
  dpad UP 6 0.35            # clamps on the nav row from anywhere below it
  dpad LEFT 6 0.35          # clamps on Search, the leftmost item
  nav_to_desc RIGHT "search" 2 || { fail "never anchored on Search"; return 1; }
  [ "${1:-0}" -gt 0 ] && dpad RIGHT "$1" 0.5
  center 4
  sleep 2
  return 0
}

scenario "smoke-home"
  step "cold launch"
  adbx logcat -c >/dev/null 2>&1
  ensure_home || return 1
  shot
  assert_screen_text "Continue Watching"
  crash_check
scenario_end

# Each destination is asserted on content only IT renders. The tab labels are no
# use: the whole nav row is painted on every screen, so asserting "Calendar"
# passed even when the sweep was sitting on the profile switcher.
for tab in "$NAV_HOME:Home:Continue Watching" \
           "$NAV_MOVIES:Movies:Critically Acclaimed" \
           "$NAV_SERIES:Series:Recommended For You" \
           "$NAV_FORYOU:For You:Watchlist" \
           "$NAV_CALENDAR:Calendar:Following"; do
  idx="${tab%%:*}"; rest="${tab#*:}"; name="${rest%%:*}"; marker="${rest#*:}"
  scenario "smoke-nav-$(echo "$name" | tr ' A-Z' '-a-z')"
    step "open $name"
    ensure_home || return 1
    top_nav "$idx" || { scenario_end; continue; }
    shot
    assert_screen_text "$marker"
    crash_check
  scenario_end
done

scenario "smoke-search"
  step "open Search and type"
  ensure_home || return 1
  top_nav "$NAV_SEARCH" || { scenario_end; return 1; }
  adbx shell input text "the" >/dev/null 2>&1
  sleep 5
  shot
  # Proving results rendered needs a pinned library; a search screen that draws
  # and does not crash is what this sweep is for.
  crash_check
scenario_end

scenario "smoke-detail"
  step "open a detail screen from Continue Watching"
  ensure_home || return 1
  center 6            # first Continue Watching tile is focused on launch
  sleep 3
  shot

  step "hero actions present"
  if screen_has_text "Resume" || screen_has_text "Play"; then pass "hero action"; else fail "no hero action"; fi
  if screen_has_text "SUBTITLES"; then pass "track selectors"; else pass "no selectors on this title"; fi
  crash_check
scenario_end

scenario "smoke-profile-menu"
  step "open the profile menu"
  ensure_home || return 1
  dpad UP 6 0.35
  dpad RIGHT 8 0.4      # clamps on the avatar at the end of the nav row
  center 4
  shot
  # Sign Out lives on this screen with no confirmation step. Assert we got here,
  # then leave with BACK only — no CENTER is pressed while it is open.
  if screen_has_text "Who's watching" || screen_has_text "Change Server"; then
    pass "profile switcher opened"
  else
    fail "profile switcher did not open"
  fi
  crash_check

  step "back out cleanly"
  back 2
  shot
  if screen_has_text "Continue Watching"; then pass "returned to Home"; else fail "did not return to Home"; fi
  crash_check
scenario_end

scenario "smoke-stability"
  step "app survived the sweep"
  # One final look for anything fatal logged across every scenario above.
  crash_check
  if adbx shell pidof "$PKG" >/dev/null 2>&1; then pass "process alive"; else fail "process died"; fi
  # Still signed in? A sweep that logs itself out invalidates every run after it.
  if screen_has_text "Sign in" || screen_has_text "Connect this Android TV"; then
    fail "the sweep signed the app out"
  else
    pass "session intact"
  fi
scenario_end
