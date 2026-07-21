# Audit PR Reconstruction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace cumulative PRs #82–#85 with reviewable, independently tested PRs that ship the approved product fixes from `fix/usability-audit` without carrying any observability, Sentry, or GlitchTip work.

**Architecture:** Preserve the current branch and old PR branches as immutable recovery sources. Reconstruct each responsibility-specific branch from the latest `upstream/main`, opening independent PRs concurrently and dependent PRs only after their prerequisites merge. Mixed commits such as `29a258cb` and `ab590cd7` are split by file/hunk rather than carried wholesale into an unrelated PR, and all telemetry dependencies are removed from functional patches.

**Tech Stack:** Git, GitHub CLI, Kotlin Multiplatform, Jetpack Compose, Media3, Gradle, Kotlin/JUnit.

## Global Constraints

- Do not install or launch Silo on the Shield during reconstruction or verification.
- Preserve `fix/usability-audit`, `origin/fix/usability-audit`, and the four existing `pr/*` branches as recovery sources; do not force-push or delete them.
- Do not alter or close PRs #53, #73, #75, or #86.
- Exclude commits `6e63e6ce`, `801a2511`, `7da4b483`, `dbc4b702`, and the equivalent `1e859a8d` logcat telemetry commit from every replacement branch.
- A replacement diff must contain no `io.sentry`, `PlaybackTelemetry`, GlitchTip, Sentry Gradle plugin, Sentry dependency, DSN, telemetry initialization, navigation breadcrumb, or crash-capture additions.
- Do not close PRs #82–#85 until the current eight-file subtitle/remount work has a committed checkpoint and the replacement map is published.
- Every replacement branch starts from the then-current `upstream/main`; no replacement PR may contain commits already merged through another replacement PR.
- Open dependent PRs only after their prerequisite PRs merge and `upstream/main` is fetched again.
- Keep the subtitle timing/presentation PR in draft until timing is resolved and later approved for device validation.
- Each PR must pass its focused tests, all four debug unit-test modules, `git diff --check`, and the build targets listed for that PR.

Every replacement branch must also pass this exclusion gate before it is pushed:

```bash
if git diff --unified=0 upstream/main...HEAD | \
  rg -i '^\+.*(io\.sentry|PlaybackTelemetry|GlitchTip|sentry-android|libs\.sentry|SentryNavigationListener)'; then
  exit 1
fi
```

Expected: no matches and exit code 0.

---

### Task 1: Preserve the current final subtitle work

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerAppearanceTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleAppearanceOptions.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/SubtitleRemountReselectionTest.kt`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/model/settings/SubtitleAppearance.kt`
- Modify: `shared/src/commonTest/kotlin/org/siloserver/silo/model/settings/SubtitleAppearanceTest.kt`
- Modify: `docs/superpowers/specs/2026-07-21-larger-tv-subtitle-presets-design.md`

**Interfaces:**
- Consumes: the subtitle-selection and presentation implementation at `ab590cd7`.
- Produces: one checkpoint commit containing remount preservation plus the approved white/no-box/black-outline/9%-margin default appearance.

- [ ] **Step 1: Re-run the verified local gate**

```bash
git diff --check
./gradlew :shared:testDebugUnitTest :android-shared:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest :androidApp:testDebugUnitTest \
  :androidTvApp:assembleDebug :androidTvApp:assembleRelease
```

Expected: `BUILD SUCCESSFUL`, with no installation or ADB command.

- [ ] **Step 2: Commit only the eight implementation/spec files**

```bash
git add \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt \
  android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerAppearanceTest.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleAppearanceOptions.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/SubtitleRemountReselectionTest.kt \
  shared/src/commonMain/kotlin/org/siloserver/silo/model/settings/SubtitleAppearance.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/model/settings/SubtitleAppearanceTest.kt \
  docs/superpowers/specs/2026-07-21-larger-tv-subtitle-presets-design.md
git commit -m "fix(player): preserve subtitles and calibrate default presentation"
```

Expected: the working tree retains only this reconstruction plan as uncommitted documentation.

### Task 2: Publish the replacement map and retire the cumulative PRs

**Files:**
- Reference: `docs/superpowers/plans/2026-07-22-pr-reconstruction.md`

**Interfaces:**
- Consumes: PRs #82–#85 and their preserved `origin/pr/*` branches.
- Produces: closed superseded PRs with a durable explanation and a seven-PR replacement map.

- [ ] **Step 1: Post the same replacement map on all four old PRs**

```bash
for pr in 82 83 84 85; do
  gh pr comment "$pr" --body $'Superseded so the cumulative stack can be rebuilt as focused PRs without the excluded observability/GlitchTip work. Replacement series: accessibility foundations; downloads/reachability; TV navigation/detail UX; playback reliability; TV track-selection UX; Chromecast; subtitle rendering/presentation. The original branch remains intact as a recovery source.'
done
```

Expected: each old PR records why it is being withdrawn and what replaces it.

- [ ] **Step 2: Close only PRs #82–#85**

```bash
for pr in 82 83 84 85; do gh pr close "$pr"; done
```

Expected: #82, #83, #84, and #85 are closed; #53, #73, #75, and #86 remain unchanged.

### Task 3: Replacement PR 1 — accessibility foundations

**Files:**
- Source commits: `65bc93c1`, `36da05aa`
- Branch: `pr/accessibility-foundations-v2`

**Interfaces:**
- Consumes: `upstream/main`.
- Produces: TV/phone readable typography floors, touch targets, focus salience, and their guardrail tests.

- [ ] **Step 1: Build and verify the clean branch**

```bash
git fetch upstream main
git switch -c pr/accessibility-foundations-v2 upstream/main
git cherry-pick 65bc93c1 36da05aa
git diff --check upstream/main...HEAD
./gradlew :androidTvApp:testDebugUnitTest :androidApp:testDebugUnitTest \
  :androidTvApp:assembleDebug :androidApp:assembleDebug
```

Expected: typography/accessibility tests and both debug builds pass without telemetry files.

- [ ] **Step 2: Publish the PR**

```bash
git push -u origin pr/accessibility-foundations-v2
gh pr create --base main --head RXWatcher:pr/accessibility-foundations-v2 \
  --title "fix: raise typography, touch, and focus accessibility floors" \
  --body $'## Summary\n- raise readable typography floors on TV and phone\n- increase undersized interaction targets\n- improve TV focus salience\n- add source guardrails for the accessibility contract\n\n## Verification\n- TV and phone unit tests\n- TV and phone debug builds'
```

Expected: a non-draft PR with two commits and no player bug-sweep history.

### Task 4: Replacement PR 2 — downloads and reachability

**Files:**
- Source commits: `6a7b9db6`, `66e52f87`, `d9f1f138`
- Branch: `pr/download-reachability-v2`

**Interfaces:**
- Consumes: `upstream/main`.
- Produces: resilient downloads, fast pre-play reachability failure, and functional per-download quality.

- [ ] **Step 1: Build the branch directly from current `main`**

```bash
git fetch upstream main
git switch -c pr/download-reachability-v2 upstream/main
git cherry-pick 6a7b9db6 66e52f87 d9f1f138
```

Expected: no commits from the retired cumulative PRs appear in `git log upstream/main..HEAD`.

- [ ] **Step 2: Verify and publish**

```bash
git diff --check upstream/main...HEAD
./gradlew :shared:testDebugUnitTest :android-shared:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest :androidApp:testDebugUnitTest \
  :androidTvApp:assembleDebug :androidApp:assembleDebug
git push -u origin pr/download-reachability-v2
gh pr create --base main --head RXWatcher:pr/download-reachability-v2 \
  --title "fix: harden downloads and pre-play reachability" \
  --body $'## Summary\n- harden downloads against auth expiry, disk-full, duplicate, and truncation failures\n- fail quickly when the server is unreachable before playback\n- make per-download quality selection effective\n\n## Verification\n- all four debug unit-test modules\n- TV and phone debug builds'
```

Expected: a non-draft PR limited to download and connection behavior.

### Task 5: Replacement PR 3 — TV navigation and detail UX

**Files:**
- Source commits: `26dad298`, `512c1810`, `2f860068`, `59e04aa7`, `75cfc06b`, `841596c5`, `7b1ac296`, `38aeba4d`, `095170de`, `78bf2190`, `fdb978b0`, `305006e0`, `407d52af`, `2beea0fe`, `8bb5b69a`, `dae3f0e7`, `e31677bb`, `51ddae72`, `769883b2`
- Split source commits: TV navigation/detail/layout hunks from `29a258cb` and detail-only files from `ab590cd7`
- Branch: `pr/tv-navigation-detail-v2`

**Interfaces:**
- Consumes: accessibility foundations after it merges, then the refreshed `upstream/main`.
- Produces: fixed keyboard/PIN navigation, focus restoration, detail navigation/artwork, marquee behavior, empty states, and safe-area layout.

- [ ] **Step 1: Cherry-pick the single-purpose commits**

```bash
git fetch upstream main
git switch -c pr/tv-navigation-detail-v2 upstream/main
git cherry-pick 26dad298 512c1810 2f860068 59e04aa7 75cfc06b \
  841596c5 7b1ac296 38aeba4d 095170de 78bf2190 fdb978b0 \
  305006e0 407d52af 2beea0fe 8bb5b69a dae3f0e7 e31677bb \
  51ddae72 769883b2
```

Expected: all listed commits apply without player subtitle-state changes.

- [ ] **Step 2: Split mixed audit changes into this branch**

```bash
audit_paths=(${(f)"$(git show --name-only --format='' 29a258cb | rg -v '^$|/player/|SubtitleTrackDisplayLabel|TvSubtitleChoiceLabelsTest')"})
git diff 29a258cb^ 29a258cb -- $audit_paths | git apply -3
git diff ab590cd7^ ab590cd7 -- \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvPlaybackFormatting.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHeroArtworkTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvPlaybackFormattingTest.kt | git apply -3
git add androidTvApp/src
git commit -m "fix(tv): complete navigation and detail audit remediation"
```

Expected: the commit contains no `TvPlayerViewModel`, subtitle renderer, Cast, download, or phone-player changes.

- [ ] **Step 3: Verify and publish**

```bash
git diff --check upstream/main...HEAD
./gradlew :androidTvApp:testDebugUnitTest :androidTvApp:assembleDebug :androidTvApp:assembleRelease
git push -u origin pr/tv-navigation-detail-v2
gh pr create --base main --head RXWatcher:pr/tv-navigation-detail-v2 \
  --title "fix(tv): repair navigation, focus restoration, and detail presentation" \
  --body $'## Summary\n- repair D-pad and keyboard focus traps\n- restore focus across calendar, library, search, profile, and detail navigation\n- correct detail artwork, episode navigation, marquee, safe-area, and empty-state presentation\n\n## Verification\n- TV unit tests\n- TV debug and release builds'
```

Expected: a non-draft TV-only PR.

### Task 6: Replacement PR 4 — playback reliability

**Files:**
- Source commits: `c6ce4ee4`, `b7290a18`, `03c046c7`, `e8cabb49`
- Branch: `pr/playback-reliability-v2`

**Interfaces:**
- Consumes: reachability after it merges, then the refreshed `upstream/main`.
- Produces: libass initialization safety, recovery state machine, phone player polish, and correct progress/chapter behavior during server transcode.

- [ ] **Step 1: Build the clean branch**

```bash
git fetch upstream main
git switch -c pr/playback-reliability-v2 upstream/main
git cherry-pick c6ce4ee4 b7290a18 03c046c7 e8cabb49
```

Expected: no TV selector, Cast, typography, or download commits in the branch history.

- [ ] **Step 2: Verify and publish**

```bash
git diff --check upstream/main...HEAD
./gradlew :shared:testDebugUnitTest :android-shared:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest :androidApp:testDebugUnitTest \
  :androidTvApp:assembleDebug :androidApp:assembleDebug
git push -u origin pr/playback-reliability-v2
gh pr create --base main --head RXWatcher:pr/playback-reliability-v2 \
  --title "fix(player): harden playback recovery and transcode progress" \
  --body $'## Summary\n- guard libass initialization\n- harden the playback recovery state machine\n- correct progress and chapters during server transcode\n- apply the reviewed phone-player polish fixes\n\n## Verification\n- all four debug unit-test modules\n- TV and phone debug builds'
```

Expected: a non-draft cross-client playback PR.

### Task 7: Replacement PR 5 — TV track-selection UX

**Files:**
- Source commits: `d23ae71d`, `5e077655`, `52cfa150`, `8296f33d`, `b881ea00`, `ece9c2cd`, `1901cba0`, `ddfe1eb4`, `96949ed9`, `c06ba7c5`, `dca47745`, `263a20ff`, `5a9564f8`, `ca2663f8`, `3c2890df`, `f3a47335`, `e477d3df`, `03f9c073`, `9adf2471`
- Split source commit: selector/accessibility hunks from `29a258cb`
- Branch: `pr/tv-track-selection-v2`

**Interfaces:**
- Consumes: playback reliability after it merges, then the refreshed `upstream/main`.
- Produces: canonical server-index subtitle selection, descriptive audio/subtitle labels, one selected row, durable next-up choices, and correct HUD focus/selection semantics.

- [ ] **Step 1: Build the branch and resolve only dependency conflicts**

```bash
git fetch upstream main
git switch -c pr/tv-track-selection-v2 upstream/main
git cherry-pick d23ae71d 5e077655 52cfa150 8296f33d b881ea00 \
  ece9c2cd 1901cba0 ddfe1eb4 96949ed9 c06ba7c5 dca47745 \
  263a20ff 5a9564f8 ca2663f8 3c2890df f3a47335 e477d3df \
  03f9c073 9adf2471
git diff 29a258cb^ 29a258cb -- \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvAiTranslateDialog.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerHud.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleChoiceLabels.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleSearchDialog.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleChoiceLabelsTest.kt \
  shared/src/androidMain/kotlin/org/siloserver/silo/player/SubtitleTrackDisplayLabel.kt | git apply -3
git add androidTvApp shared
git commit -m "fix(tv): complete track-selector audit remediation"
```

Expected: any conflict resolution preserves the merged playback APIs and does not copy unrelated TV layout files.

- [ ] **Step 2: Verify and publish**

```bash
git diff --check upstream/main...HEAD
./gradlew :shared:testDebugUnitTest :android-shared:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest :androidTvApp:assembleDebug :androidTvApp:assembleRelease
git push -u origin pr/tv-track-selection-v2
gh pr create --base main --head RXWatcher:pr/tv-track-selection-v2 \
  --title "fix(tv): make audio and subtitle selection canonical and durable" \
  --body $'## Summary\n- use the server combined index space for subtitle choices\n- provide descriptive audio and subtitle labels\n- keep one canonical selected row\n- persist next-up track choices and align HUD focus with selection\n\n## Verification\n- shared, android-shared, and TV unit tests\n- TV debug and release builds'
```

Expected: a non-draft player-UX PR with no subtitle timing/default-style changes.

### Task 8: Replacement PR 6 — Chromecast

**Files:**
- Source commits: `e4b05513` through `3e926f65` from `upstream/pr-85`; exclude telemetry-only commit `3c4206b1`
- Branch: `pr/chromecast-v2`

**Interfaces:**
- Consumes: reachability and playback reliability after they merge, then the refreshed `upstream/main`.
- Produces: phone-side Google Cast playback, takeover UI, seeking, mini bar, notification actions, and subtitles without Sentry or `PlaybackTelemetry` integration.

- [ ] **Step 1: Cherry-pick only the original Cast range**

```bash
git fetch upstream main pull/85/head:refs/remotes/upstream/pr-85
git switch -c pr/chromecast-v2 upstream/main
git cherry-pick e4b05513^..3e926f65
```

Expected: `git log upstream/main..HEAD` contains 14 Cast-specific commits and none of #82–#84's history.

- [ ] **Step 2: Remove telemetry coupling from the Cast implementation**

Remove `io.sentry.SentryLogLevel` and `PlaybackTelemetry` imports and calls from `CastPlaybackPreparer.kt` and `SiloCastSessionManager.kt`. Preserve all control flow, returned values, state transitions, and user-visible error handling; only the telemetry side effects are removed.

Run:

```bash
if rg -n -i 'io\.sentry|PlaybackTelemetry|GlitchTip' \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/cast \
  androidApp/src/androidMain/kotlin/org/siloserver/silo/android/cast; then
  exit 1
fi
git add android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/cast androidApp/src/androidMain/kotlin/org/siloserver/silo/android/cast
git commit -m "refactor(cast): keep observability outside the feature PR"
```

Expected: no Cast source file imports or invokes Sentry or `PlaybackTelemetry`.

- [ ] **Step 3: Verify and publish**

```bash
git diff --check upstream/main...HEAD
if git diff --unified=0 upstream/main...HEAD | rg -i '^\+.*(io\.sentry|PlaybackTelemetry|GlitchTip|sentry-android|libs\.sentry)'; then exit 1; fi
./gradlew :shared:testDebugUnitTest :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleRelease
git push -u origin pr/chromecast-v2
gh pr create --base main --head RXWatcher:pr/chromecast-v2 \
  --title "feat: add Google Chromecast to the phone app" \
  --body $'## Summary\n- add phone-side Google Cast playback and takeover UI\n- support seeking, mini-bar and notification controls\n- support signed HLS playback and subtitle styling\n- harden Cast preparation, lifecycle, and telemetry\n\n## Verification\n- shared, android-shared, and phone unit tests\n- phone debug and release builds'
```

Expected: a non-draft phone/Cast PR with no cumulative TV audit history.

### Task 9: Replacement PR 7 — subtitle rendering and presentation

**Files:**
- Source commits: `55b4a53b`, `0ccceb0b`, `3096d721`
- Split source commit: subtitle timing/rendering/player-screen files from `ab590cd7`
- Checkpoint source: `fix(player): preserve subtitles and calibrate default presentation` from Task 1
- Branch: `pr/subtitle-presentation-v2`

**Interfaces:**
- Consumes: TV track-selection UX after it merges, then the refreshed `upstream/main`.
- Produces: preserved subtitle selection across remounts, standard size scale, white/no-box/black-outline defaults, 9% bottom margin, appearance controls, and corrected subtitle timing behavior.

- [ ] **Step 1: Build the draft branch from the merged selector foundation**

```bash
git fetch upstream main
git switch -c pr/subtitle-presentation-v2 upstream/main
git cherry-pick 55b4a53b 0ccceb0b 3096d721
git diff ab590cd7^ ab590cd7 -- \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SiloPlayerFactory.kt \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/VideoPlayerMediaMounter.kt \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/VideoPlayerMediaSpec.kt \
  android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/subtitle \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player \
  shared/src/commonMain/kotlin/org/siloserver/silo/model/playback \
  android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player \
  shared/src/commonTest/kotlin/org/siloserver/silo/model/playback | git apply -3
git add android-shared androidTvApp shared
git commit -m "fix(player): stabilize subtitle rendering and timing"
checkpoint=$(git log --grep='^fix(player): preserve subtitles and calibrate default presentation$' -1 --format=%H fix/usability-audit)
test -n "$checkpoint"
git cherry-pick "$checkpoint"
```

Expected: the final cherry-pick contains only Task 1's eight files.

- [ ] **Step 2: Run local verification without claiming timing resolution**

```bash
git diff --check upstream/main...HEAD
./gradlew :shared:testDebugUnitTest :android-shared:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest :androidApp:testDebugUnitTest \
  :androidTvApp:assembleDebug :androidTvApp:assembleRelease
```

Expected: all tests/builds pass locally; no ADB or Shield command runs.

- [ ] **Step 3: Publish as a draft**

```bash
git push -u origin pr/subtitle-presentation-v2
gh pr create --draft --base main --head RXWatcher:pr/subtitle-presentation-v2 \
  --title "fix(player): stabilize subtitle selection and presentation" \
  --body $'## Summary\n- preserve active subtitles across player remounts\n- stabilize subtitle parsing, offset handling, and start-position behavior\n- use the standard subtitle-size scale\n- default to white outlined text without a background box and use a 9% bottom safe margin\n\n## Remaining gate\n- resolve and verify the reported subtitle timing concern\n- perform device validation only after explicit approval\n\n## Local verification\n- all four debug unit-test modules\n- TV debug and release builds'
```

Expected: a draft PR explicitly listing subtitle timing/device validation as the remaining gate.

### Task 10: Final coverage audit

**Files:**
- Verify: all replacement branches and the preserved `fix/usability-audit` tree.

**Interfaces:**
- Consumes: merged replacement PRs plus the draft subtitle branch.
- Produces: proof that every original source change is present exactly once or intentionally documented as deferred.

- [ ] **Step 1: Compare the reconstructed aggregate tree**

```bash
git fetch upstream main
git diff --stat fix/usability-audit..pr/subtitle-presentation-v2
git diff --name-status fix/usability-audit..pr/subtitle-presentation-v2
```

Expected: the reconstructed aggregate tree contains every approved product change. Remaining tree differences consist of the intentionally excluded observability/GlitchTip/Sentry work and conflict resolutions that preserve newer `main` behavior.

- [ ] **Step 2: Run the repository-wide gate on the aggregate result**

```bash
git diff --check
if git diff --unified=0 upstream/main...HEAD | rg -i '^\+.*(io\.sentry|PlaybackTelemetry|GlitchTip|sentry-android|libs\.sentry)'; then exit 1; fi
./gradlew :shared:testDebugUnitTest :android-shared:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest :androidApp:testDebugUnitTest \
  :androidTvApp:assembleDebug :androidTvApp:assembleRelease \
  :androidApp:assembleDebug :androidApp:assembleRelease
```

Expected: `BUILD SUCCESSFUL`, with no device installation or launch.
