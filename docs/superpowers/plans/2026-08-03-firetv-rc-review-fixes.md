# Fire TV rc.1+4 Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve every actionable review finding on Android PR #161 without regressing reanchored playback, track persistence, or TV focus behavior.

**Architecture:** Keep layout constants shared, model exit-position and source-start decisions as testable pure logic, and use the established focus-scoped re-anchor loop for Compose focus relocation. Preserve the existing player timeline mapping while bypassing only the transient presentation gates during final Stop capture.

**Tech Stack:** Kotlin 2.1, Jetpack Compose for TV, Media3, Kotlin/JUnit tests, Gradle.

## Global Constraints

- Preserve compatibility with legacy long pairing codes and newly bounded server codes.
- Preserve source/movie-time mapping for reanchored HLS playback.
- Keep explicit subtitle Off (`-1`) distinct from unresolved/keep-current (`null`).
- Do not alter phone behavior.

---

### Task 1: Pairing-code shared width

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/auth/TvServerSetupScreen.kt`
- Test: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/auth/TvLoginMatchCodeLayoutTest.kt`

- [ ] Verify `matchCodeTileWidthDp` budgets `MATCH_CODE_SEPARATOR_WIDTH_DP` while `MatchCodeCard` renders 12dp.
- [ ] Render separators with `MATCH_CODE_SEPARATOR_WIDTH_DP.dp`.
- [ ] Run the match-code layout tests.

### Task 2: Nullable track-selection persistence

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailViewModel.kt`
- Test: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvTrackSelectionPersistenceTest.kt`

- [ ] Add a failing test proving a null keep-current subtitle retains the previous explicit selection.
- [ ] Make `rememberPlaybackReturn` fall back to `previous?.subtitle` only for null; retain `-1` and nonnegative values.
- [ ] Run the focused persistence tests.

### Task 3: Final Stop snapshot

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt`
- Test: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlaybackExitSnapshotTest.kt`

- [ ] Add failing behavioral tests for supplied final player position, missing samples, and reanchored timeline mapping.
- [ ] Extract a pure exit-snapshot resolver that maps player time to source time and clamps to server duration.
- [ ] Apply that snapshot directly to `_uiState` before persistence, without invoking seek/mount presentation gates.
- [ ] Run the focused exit-snapshot tests.

### Task 4: Rewound source-start metadata

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvVideoPlaybackStarter.kt`
- Test: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlaybackSourceStartTest.kt`

- [ ] Add a failing behavioral test showing a 600-second resume rewound to 593 seconds must adopt 593 as source start.
- [ ] Resolve source start from `startRequestPosition`, server source start, then player start.
- [ ] Verify Start Over zero and no-request server anchors remain intact.

### Task 5: Focus-scoped For You re-anchor

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvMediaRow.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvRecommendationsScreen.kt`

- [ ] Add a row-level focus callback to `TvMediaRow`.
- [ ] Track first-row focus and repeatedly re-anchor while focus relocation leaves the list below item zero, matching the existing library control-row pattern.
- [ ] Keep an already-top list as a no-op and stop the loop immediately when row focus leaves.

### Task 6: Verification and PR update

**Files:**
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvFireTvRcFeedbackOwnershipTest.kt`

- [ ] Remove superseded source-text assertions for Kotlin behavior while retaining workflow/Gradle contract checks.
- [ ] Run focused tests, then the complete shared/TV unit and APK build gate with `1.0.0-rc.1+4` display version.
- [ ] Push the follow-up commit and reply to each review thread with the verification evidence.
