# Android TV Performance Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Android TV Home, detail, player, and teardown stalls without changing visible behavior.

**Architecture:** Bound speculative work through one shared concurrency helper, make route activity own secondary detail jobs, expose a structural player presentation flow that ignores clock-only changes, and move final progress persistence to an application-owned asynchronous writer. Preserve existing public UI and playback contracts.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, Jetpack Compose for TV, Media3, Room, Koin, kotlin.test/JUnit, Gradle.

## Global Constraints

- Preserve layout, focus, transitions, subtitles, controls, data, and resume semantics.
- Never launch unbounded work per section, recommendation, episode, or artwork row.
- Never block player navigation on Room or HTTP.
- Do not add observability or GlitchTip work to this change.

---

### Task 1: Bounded concurrency primitive

**Files:**
- Create: `shared/src/commonMain/kotlin/org/siloserver/silo/util/BoundedConcurrency.kt`
- Test: `shared/src/commonTest/kotlin/org/siloserver/silo/util/BoundedConcurrencyTest.kt`

**Interfaces:**
- Produces: `suspend fun <T, R> Iterable<T>.mapConcurrentBounded(maxConcurrency: Int, transform: suspend (T) -> R): List<R>`

- [ ] Write tests proving order is retained, maximum in-flight work is capped, and invalid limits fail.
- [ ] Run `./gradlew :shared:testDebugUnitTest --tests org.siloserver.silo.util.BoundedConcurrencyTest` and confirm the unresolved symbol failure.
- [ ] Implement the helper with `coroutineScope`, `Semaphore`, `withPermit`, `async`, and `awaitAll`.
- [ ] Re-run the focused test and commit the passing slice.

### Task 2: Bound Home and shell work

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/viewmodel/HomeViewModel.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineSectionFeed.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/shell/TvMainShell.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineSectionFeedSourceTest.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/shell/TvCollectionsPresenceCacheTest.kt`

**Interfaces:**
- Consumes: `mapConcurrentBounded`
- Produces: `TvCollectionsPresenceCache` keyed by the current ordered library IDs.

- [ ] Add failing tests requiring removal of page-entry hero/detail warming, retention of radius-two focus warming, four-at-a-time Home fallback, and reuse of collection-presence results for unchanged library IDs.
- [ ] Run the focused shared and TV tests and confirm their expected assertions fail.
- [ ] Delete the two page-entry warming effects and their first-two-row constants; keep focus-neighbor warming.
- [ ] Replace Home fallback `map { async }` with `mapConcurrentBounded(4)`.
- [ ] Save collection-presence IDs/results across root navigation and skip unchanged probes.
- [ ] Re-run focused tests and commit the passing slice.

### Task 3: Cancel and bound secondary detail work

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailViewModel.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/performance/TvDetailOpenPerformanceSourceTest.kt`

**Interfaces:**
- Produces: `onRouteResumed()` and `onRoutePaused()` lifecycle hooks.

- [ ] Add failing tests requiring bounded recommendation/favorite enrichment and cancellation on route pause.
- [ ] Run `./gradlew :androidTvApp:testDebugUnitTest --tests org.siloserver.silo.tv.ui.performance.TvDetailOpenPerformanceSourceTest` and confirm failure.
- [ ] Use `mapConcurrentBounded(3)` for recommendation and episode favorite resolution.
- [ ] Cancel `moreLikeThisJob` and `episodeFavoriteJob` when the route pauses; restart missing secondary content when resumed.
- [ ] Wire the hooks with `LifecycleResumeEffect`, generation-check late results, re-run tests, and commit.

### Task 4: Isolate the player clock

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerPresentationStateTest.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModelPlaybackPositionTest.kt`

**Interfaces:**
- Produces: `PlaybackClock(position: Double, duration: Double)`, `playbackClock`, and `presentationState`.

- [ ] Add failing tests proving position-only changes normalize to equal presentation states and the screen does not collect raw `uiState` at its root.
- [ ] Run focused player tests and confirm failure.
- [ ] Derive `presentationState` by normalizing position and derive `playbackClock` from raw state.
- [ ] Collect `presentationState` at the root and collect `playbackClock` only inside transport/HUD clock wrappers.
- [ ] Make cast snapshots read the live raw state and stop running `PlayerView` updates on clock ticks.
- [ ] Re-run focused tests and commit.

### Task 5: Make exit persistence non-blocking

**Files:**
- Create: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/FinalPlaybackPositionWriter.kt`
- Create: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/FinalPlaybackPositionWriterTest.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/di/PlayerInfraModule.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/di/AndroidTvModule.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt`

**Interfaces:**
- Produces: `FinalPlaybackPosition` and `FinalPlaybackPositionWriter.submit(snapshot)`.

- [ ] Add failing tests proving `submit` returns before a suspended write finishes, invalid positions are rejected, and newer same-file snapshots coalesce.
- [ ] Run the focused writer/player tests and confirm failure.
- [ ] Implement the singleton IO writer and inject it into the TV player.
- [ ] Replace `runBlocking` teardown with writer submission; ordinary Back navigates immediately and calls `sessionLifecycle.stopAsync`, while auto-advance retains ordered stop/start.
- [ ] Clear stale playable state synchronously, re-run focused tests, and commit.

### Task 6: Verification and device evidence

**Files:**
- Modify only files required by failures attributable to this plan.

- [ ] Run `git diff --check`.
- [ ] Run `./gradlew :shared:testDebugUnitTest :android-shared:testDebugUnitTest :androidTvApp:testDebugUnitTest --rerun-tasks`.
- [ ] Run `./gradlew :androidTvApp:assembleDebug`.
- [ ] Review `git diff --stat`, `git diff`, and `git status --short` against the design.
- [ ] Install the debug APK on Google TV Streamer `61071HFAG1FWQX` only after the user requests device installation; do not launch it automatically.
- [ ] During an approved device run, capture Home → detail → player → detail → Home and verify clock ticks no longer invoke the root `PlayerView` update and no Home preload burst occurs.
