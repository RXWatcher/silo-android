# Silo TV — emulator end-to-end test plan

Repeatable, evidence-producing sweep of the Android TV client against a real
server. Written after the 2026-07-25 subtitle session, where four bugs were only
found by driving the app and reading traces — not by unit tests, all of which
were green throughout.

## 1. Harness

`scripts/tv-e2e/` (to build):

| Piece | Purpose |
|---|---|
| `run.sh <suite>` | boots the AVD, installs the APK, arms tracing, runs suites, writes a report |
| `lib/drive.sh` | `dpad`, `press`, `focus_is`, `wait_for`, `tap_desc` — D-pad driving with focus assertions |
| `lib/evidence.sh` | per-step screenshot, per-scenario `screenrecord`, scoped logcat capture |
| `suites/*.sh` | one file per area below; each step declares expected UI + expected trace |
| `out/<timestamp>/` | `report.md`, `steps/NNN-*.png`, `*.mp4`, `logcat-*.txt`, `result.json` |

**Recording.** Each scenario wraps in `adb shell screenrecord --time-limit 180`
pulled on exit, plus a screenshot per step. Both land in `out/<timestamp>/`, so
a run is reviewable without re-running it, and two runs are diffable.

**Determinism rules learned the hard way:**
- `uiautomator dump` does **not** expose Compose text; assert on
  `content-desc` (buttons do have it) or on screenshots. Never navigate blind.
- The player overlay auto-hides in ~5s — open and act in one command.
- `input tap` is ignored on a TV device (no touchscreen). D-pad only.
- `log.tag.SUBDIAG` is read **once per process** and does not survive reboot:
  set it, then `am force-stop`, then launch.
- Fix the AVD to a known state: `hw.keyboard=yes`, wipe-data before a full run.

## 2. Fixtures required

Fill in before the first run — the sweep asserts against these by name.

| Fixture | Why |
|---|---|
| Movie, multi-version (4K/1080p) | version switch, quality ladder |
| Episode w/ external + embedded + forced subs | subtitle matrix (this session's bugs) |
| Title w/ multiple audio tracks (EAC3/AAC, 5.1/2.0) | audio switch, passthrough |
| Dolby Vision title | DV path, the unverified `bitstream-io` 4.10 RPU change |
| Audiobook, multi-part | audiobook player, chapters, bookmarks |
| Ebook | reader |
| Title with intro/credit markers | auto-skip |
| Series, partially watched | Continue Watching, Up Next, mark-watched |
| Collection + person | collections, people |
| Second profile, one PIN-protected | profile switch, parental gate |
| Admin-capable account | admin hub, scans, users |

## 3. Coverage — by screen area

Each row is a suite file. **P0** = must pass before any release; **P1** =
regression sweep; **P2** = opportunistic.

### player (27 files — highest risk, most defects)
- **P0** start/resume/start-over; seek ±30, scrubber, chapter skip
- **P0** subtitles: off → external → embedded → forced → downloaded → off;
  switch twice in a row; verify checkmark matches what renders; delay; appearance
- **P0** audio: switch track, verify no video restart; passthrough where offered
- **P0** quality/version switch mid-playback; resume position preserved
- **P1** PiP enter/exit, background/foreground, lock-screen controls
- **P1** up-next countdown, auto-advance, cancel on exit
- **P1** error recovery: kill network mid-stream, 404 session, server restart
- **P2** speed, sleep timer, stats overlay

### detail (18) / home (4) / browse / library / libraries
- **P0** hero actions: Resume, Start Over, watched, favourite, bookmark
- **P0** episode rail, season switch, version/audio/subtitle selectors pre-play
- **P1** Continue Watching correctness after partial play, after mark-watched
- **P1** rows: For You, Calendar, recommendations, alphabet rail, filters/sort

### auth (11) / servers / profiles (9)
- **P0** add server, sign in, wrong password, cleartext-consent path
- **P0** profile switch, PIN gate (correct + wrong), sign out
- **P1** multi-server switch, remove server (verify data purge)
- **P1** token expiry / refresh (force via server-side invalidation)

### audiobook (8)
- **P0** play, resume, chapter nav, multi-part boundary, bookmarks
- **P1** speed, sleep timer, Continue Listening

### admin (10) / requests (6) / notifications
- **P1** admin hub, trigger scan, user list, request create/approve
- **P2** notifications list, mark read

### search (3) / collections (4) / people (2) / personal / recommendations
- **P1** search by title/person, empty state, keyboard entry
- **P1** collection browse, person filmography

### cast / watchtogether (5)
- **P2** on emulator only as far as UI state — no real receiver; verify no crash,
  correct empty/unavailable states. Real cast stays a Shield/device test.

## 4. Method per step

1. Arrange: navigate by D-pad, asserting `content-desc` focus at each hop.
2. Act.
3. Assert **three ways**: screenshot (visual), trace (`SUBDIAG`/logcat markers),
   and where possible server-side effect (progress row, session stopped).
4. On failure: keep going, record, and continue the suite — a run produces a
   defect list, not a single stop.

## 5. Non-goals / known emulator gaps

DV and HDR output, real Chromecast, HDMI/passthrough audio, real remote quirks,
performance/thermals. These stay Shield tests; the plan flags them rather than
pretending the emulator covers them.

## 6. Execution order

1. Build harness + `player` suite (highest defect density).
2. Fixtures pinned, baseline run recorded, defects triaged.
3. Remaining suites in the P0/P1 order above.
4. Once green, keep as the pre-release sweep; re-run per release and diff
   against the previous `out/` run.
