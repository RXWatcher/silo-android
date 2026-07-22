# Android Mobile Performance Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove mobile player lifecycle, recomposition, teardown, and detail-enrichment performance defects without changing visible behavior.

**Architecture:** Port the already-verified TV boundaries to mobile: a lifecycle-owned player ViewModel, separate structural and clock flows, application-owned final-position persistence, sequenced asynchronous stop, and lifecycle-bound detail enrichment. Keep the raw `PlayerUiState` authoritative internally while narrowing what each Compose subtree observes.

**Tech Stack:** Kotlin 2.1, AndroidX ViewModel and Navigation Compose, Jetpack Compose, Koin 4.1, coroutines/StateFlow, Kotlin test/JUnit, Gradle.

## Global Constraints

- Preserve mobile UI, controls, subtitles, navigation, download-badge semantics, and resume accuracy.
- Recommendation ranking and the twelve-item server request limit remain unchanged.
- Similar-item detail hydration has a maximum concurrency of three.
- Player exit is immediate and idempotent; no Room or HTTP work blocks `onCleared()`.
- Secondary detail work runs only while the detail route is active and resumes if incomplete.
- Do not install or launch the app as part of this implementation.

---

### Task 1: Isolate the mobile playback clock

**Files:**
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobilePlayerPresentationStateTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt`

**Interfaces:**
- Produces: `PlaybackClock(position: Double, duration: Double, bufferedPosition: Double)`
- Produces: `PlayerUiState.withoutPlaybackClock()`, `toPlaybackClock()`, and `withPlaybackClock(clock)`
- Produces: `PlayerViewModel.presentationState` and `PlayerViewModel.playbackClock`

- [ ] **Step 1: Write the failing presentation-state test**

```kotlin
package org.siloserver.silo.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MobilePlayerPresentationStateTest {
    @Test
    fun clockOnlyUpdatesLeaveStructuralStateEqual() {
        val first = PlayerViewModel.PlayerUiState(
            contentId = "movie-1",
            position = 12.0,
            duration = 100.0,
            bufferedPosition = 30.0,
        )
        val second = first.copy(position = 12.5, duration = 101.0, bufferedPosition = 35.0)

        assertEquals(first.withoutPlaybackClock(), second.withoutPlaybackClock())
        assertNotEquals(first.toPlaybackClock(), second.toPlaybackClock())
        assertEquals(second, second.withoutPlaybackClock().withPlaybackClock(second.toPlaybackClock()))
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*MobilePlayerPresentationStateTest'`

Expected: compilation fails because the clock type and conversion functions do not exist.

- [ ] **Step 3: Add clock conversion and derived flows**

Add after `PlayerViewModel`:

```kotlin
data class PlaybackClock(
    val position: Double,
    val duration: Double,
    val bufferedPosition: Double,
)

internal fun PlayerViewModel.PlayerUiState.withoutPlaybackClock(): PlayerViewModel.PlayerUiState =
    copy(position = 0.0, duration = 0.0, bufferedPosition = 0.0)

internal fun PlayerViewModel.PlayerUiState.toPlaybackClock(): PlaybackClock =
    PlaybackClock(position, duration, bufferedPosition)

internal fun PlayerViewModel.PlayerUiState.withPlaybackClock(clock: PlaybackClock): PlayerViewModel.PlayerUiState =
    copy(
        position = clock.position,
        duration = clock.duration,
        bufferedPosition = clock.bufferedPosition,
    )
```

Next to `uiState`, derive two eager flows using `map`, `distinctUntilChanged`, and `stateIn(viewModelScope, SharingStarted.Eagerly, ...)`, matching `TvPlayerViewModel`:

```kotlin
val presentationState: StateFlow<PlayerUiState> = uiState
    .map(PlayerUiState::withoutPlaybackClock)
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.withoutPlaybackClock())

val playbackClock: StateFlow<PlaybackClock> = uiState
    .map(PlayerUiState::toPlaybackClock)
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.toPlaybackClock())
```

- [ ] **Step 4: Narrow Compose observation**

Change root collection to `viewModel.presentationState.collectAsState()`. Use `viewModel.uiState.value.duration` when building or refreshing `VideoPlayerMediaSpec`, because the normalized presentation copy intentionally zeros duration. Add this helper:

```kotlin
@Composable
private fun PlayerClockScope(
    viewModel: PlayerViewModel,
    content: @Composable (PlaybackClock) -> Unit,
) {
    val clock by viewModel.playbackClock.collectAsState()
    content(clock)
}
```

Wrap only the `PlayerOverlay` invocation:

```kotlin
PlayerClockScope(viewModel) { clock ->
    PlayerOverlay(
        state = uiState.withPlaybackClock(clock),
        viewModel = viewModel,
        roomSnapshot = roomSnapshot,
        castSlot = {
            SiloCastButton(
                castManager = castManager,
                onStartCast = {
                    castScope.launch {
                        val spec = viewModel.prepareGoogleCastMedia()
                        if (spec != null) castManager.prepareMedia(spec)
                    }
                },
            )
        },
        isFastForwardHoldActive = fastForwardHoldActive,
        onBack = {
            exitRequested = true
            roomController?.leave(closeRoom = roomSnapshot?.isHost == true)
            viewModel.onExit()
            if (!navController.popBackStack()) activity?.finish()
        },
        onPlayPause = {
            if (roomController != null) roomController.onUserPlayPause()
            else viewModel.onPlayPause()
        },
        onSeek = { position ->
            if (roomController != null) roomController.onUserSeek(position)
            else viewModel.onSeek(position)
        },
        onToggleControls = { viewModel.onToggleControls() },
        onFastForwardHold = { active -> fastForwardHoldActive = active },
        onSelectSubtitle = { viewModel.onSelectSubtitle(it) },
        onSelectAudio = { viewModel.onSelectAudio(it) },
        onSelectVersion = { viewModel.onSelectVersion(it) },
    )
}
```

Do not wrap the video `AndroidView`, cast overlay, Media3 mounting effects, or root player surface in `PlayerClockScope`.

- [ ] **Step 5: Run the test and mobile player source tests**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*MobilePlayerPresentationStateTest' --tests '*PlayerScreen*Test'`

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobilePlayerPresentationStateTest.kt
git commit -m "perf(android): isolate mobile playback clock"
```

### Task 2: Correct player ownership and make teardown idempotent

**Files:**
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/performance/MobilePlayerLifecyclePerformanceSourceTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/di/AndroidModule.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`

**Interfaces:**
- Consumes: shared `FinalPlaybackPositionWriter`, `FinalPlaybackPosition`, and `PlaybackSessionLifecycle.stopAsync()`
- Produces: lifecycle-owned `PlayerViewModel` and idempotent `onExit()`

- [ ] **Step 1: Write the failing lifecycle-boundary test**

Create a source test that reads the three production files and asserts:

```kotlin
assertTrue(module.contains("viewModel {\n        PlayerViewModel("))
assertTrue(screen.contains("viewModel: PlayerViewModel = koinViewModel()"))
assertTrue(viewModel.contains("private val exitPrepared = AtomicBoolean(false)"))
assertTrue(viewModel.contains("if (!exitPrepared.compareAndSet(false, true)) return"))
assertTrue(viewModel.contains("finalPlaybackPositionWriter.submit("))
assertTrue(viewModel.contains("sessionLifecycle.stopAsync()"))
assertTrue(!viewModel.contains("runBlocking("))
assertTrue(!viewModel.contains("viewModelScope.launch {\n                playbackSessionManager.stopSession(sessionId)"))
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*MobilePlayerLifecyclePerformanceSourceTest'`

Expected: FAIL on factory ownership, injection, writer, asynchronous stop, and blocking teardown assertions.

- [ ] **Step 3: Change Koin ownership**

Change the mobile DI declaration from `factory { PlayerViewModel(...) }` to `viewModel { PlayerViewModel(...) }`. Add `finalPlaybackPositionWriter = get()` to the constructor call. In `PlayerScreen`, import `org.koin.compose.viewmodel.koinViewModel` and replace the default `koinInject()` ViewModel parameter with `koinViewModel()`.

- [ ] **Step 4: Implement one-shot teardown**

Inject `FinalPlaybackPositionWriter` into `PlayerViewModel`, add `AtomicBoolean(false)`, and replace the suspend `onExit()` body with synchronous preparation:

```kotlin
fun onExit() {
    if (!exitPrepared.compareAndSet(false, true)) return
    resetPlaybackRecoveryState()
    val state = _uiState.value
    val cid = state.contentId.takeIf { it.isNotBlank() }
    val fid = currentFileId()
    if (cid != null && fid != null) {
        finalPlaybackPositionWriter.submit(
            FinalPlaybackPosition(cid, fid, state.position, state.duration.takeIf { it > 0.0 }),
        )
    }
    sessionLifecycle.stopAsync()
    controlsHideJob?.cancel()
    introObserverJob?.cancel()
    searchJob?.cancel()
    aiJobHandle?.cancel()
    introAutoSkipController.reset()
    _uiState.update {
        it.copy(
            isLoading = false,
            sessionId = null,
            playMethod = null,
            playbackPlan = null,
            delivery = null,
            streamUrl = null,
            container = null,
            subtitleTracks = emptyList(),
            isPaused = true,
            isPlaying = false,
        )
    }
}
```

In `onCleared()`, call `onExit()` as the safety net before or after local non-blocking cleanup. Delete the `runBlocking` final write and the `viewModelScope.launch { playbackSessionManager.stopSession(...) }` block. Keep `DisposableEffect.onDispose { viewModel.onExit() }`; the atomic gate makes disposal safe after an explicit exit.

- [ ] **Step 5: Run the lifecycle and shared writer/lifecycle tests**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*MobilePlayerLifecyclePerformanceSourceTest' :android-shared:testDebugUnitTest --tests '*FinalPlaybackPositionWriterTest' --tests '*PlaybackSessionLifecycle*Test'`

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add androidApp/src/androidMain/kotlin/org/siloserver/silo/android/di/AndroidModule.kt androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/performance/MobilePlayerLifecyclePerformanceSourceTest.kt
git commit -m "perf(android): make player teardown lifecycle safe"
```

### Task 3: Bound and lifecycle-scope similar-item hydration

**Files:**
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/performance/MobileDetailOpenPerformanceSourceTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/SimilarRail.kt`

**Interfaces:**
- Consumes: shared `mapConcurrentBounded(maxConcurrency = 3)`
- Produces: route-active, cancellable recommendation hydration

- [ ] **Step 1: Add failing assertions**

Extend `MobileDetailOpenPerformanceSourceTest` with a test requiring `LifecycleResumeEffect(contentId)`, `delay(300)`, a route-active guard, and exactly one `mapConcurrentBounded(maxConcurrency = 3)` in `SimilarRail.kt`; also assert `.map { ref -> async` and `.awaitAll()` are absent.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*MobileDetailOpenPerformanceSourceTest'`

Expected: FAIL because hydration is immediate and unbounded.

- [ ] **Step 3: Implement lifecycle-bound bounded hydration**

Add `var routeActive by remember(contentId) { mutableStateOf(false) }` and:

```kotlin
LifecycleResumeEffect(contentId) {
    routeActive = true
    onPauseOrDispose { routeActive = false }
}

LaunchedEffect(contentId, routeActive) {
    items = emptyList()
    if (!routeActive) return@LaunchedEffect
    delay(300)
    val loaded = loadSimilar(contentId, recommendationRepository, catalogRepository)
    if (routeActive) items = loaded
}
```

Replace the `coroutineScope/map/async/awaitAll` block with:

```kotlin
return scored.mapConcurrentBounded(maxConcurrency = 3) { ref ->
    (catalogRepository.getItemDetail(ref.mediaItemId) as? ApiResult.Success)?.data
}.filterNotNull()
```

- [ ] **Step 4: Run the focused test and detail tests**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*MobileDetailOpenPerformanceSourceTest' --tests '*ItemDetail*Test'`

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/SimilarRail.kt androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/performance/MobileDetailOpenPerformanceSourceTest.kt
git commit -m "perf(android): bound similar item hydration"
```

### Task 4: Pause and resume series download roll-up with the detail route

**Files:**
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/performance/MobileDetailOpenPerformanceSourceTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/ItemDetailViewModel.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/ItemDetailScreen.kt`

**Interfaces:**
- Produces: `ItemDetailViewModel.onRoutePaused()` and `onRouteResumed()`
- Produces: private `EpisodeRollupRequest` retained until the roll-up is complete

- [ ] **Step 1: Add failing route-lifecycle assertions**

Require the screen to contain `LifecycleResumeEffect(viewModel)`, `viewModel.onRouteResumed()`, and `onPauseOrDispose { viewModel.onRoutePaused() }`. Require the ViewModel to retain `EpisodeRollupRequest`, cancel `allEpisodeFileIdsJob` from `onRoutePaused()`, guard loading with `routeActive`, and restart `pendingEpisodeRollup` from `onRouteResumed()`.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*MobileDetailOpenPerformanceSourceTest'`

Expected: FAIL because the season crawl has no route lifecycle.

- [ ] **Step 3: Retain and gate the roll-up request**

Add:

```kotlin
private data class EpisodeRollupRequest(
    val seriesId: String,
    val seasons: List<Season>,
    val seedEpisodes: List<EpisodeListItem>,
    val skipSeasonNumber: Int?,
)

private var routeActive = true
private var pendingEpisodeRollup: EpisodeRollupRequest? = null
```

At the start of `loadAllEpisodeFileIds`, save the request, cancel the previous job, and return before launching when inactive. Initialize the working file-id set from both `_uiState.value.allEpisodeFileIds` and the seed episodes. Clear `pendingEpisodeRollup` only after a fully executed crawl publishes its final state.

Add:

```kotlin
fun onRoutePaused() {
    routeActive = false
    allEpisodeFileIdsJob?.cancel()
}

fun onRouteResumed() {
    val wasPaused = !routeActive
    routeActive = true
    refreshOnReturn()
    if (wasPaused && !_uiState.value.allEpisodeIdsComplete) {
        pendingEpisodeRollup?.let { request ->
            loadAllEpisodeFileIds(
                request.seriesId,
                request.seasons,
                request.seedEpisodes,
                request.skipSeasonNumber,
            )
        }
    }
}
```

Before publishing from the crawl, verify `routeActive`; cancellation must not mark the roll-up complete.

- [ ] **Step 4: Bind the detail screen lifecycle**

Replace the existing manual `ON_RESUME` observer with:

```kotlin
LifecycleResumeEffect(viewModel) {
    viewModel.onRouteResumed()
    onPauseOrDispose { viewModel.onRoutePaused() }
}
```

This retains refresh-on-return while giving secondary work a matching pause boundary.

- [ ] **Step 5: Run focused detail tests**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*MobileDetailOpenPerformanceSourceTest' --tests '*ItemDetail*Test'`

Expected: PASS.

- [ ] **Step 6: Commit Task 4**

```bash
git add androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/ItemDetailViewModel.kt androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/ItemDetailScreen.kt androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/performance/MobileDetailOpenPerformanceSourceTest.kt
git commit -m "perf(android): pause detail enrichment off route"
```

### Task 5: Full verification

**Files:**
- No production files unless verification exposes a regression.

- [ ] **Step 1: Run formatting and diff checks**

Run: `git diff --check HEAD~4..HEAD && git status --short`

Expected: no whitespace errors; only intended tracked changes.

- [ ] **Step 2: Run shared and Android-shared tests**

Run: `./gradlew :shared:allTests :android-shared:testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the complete mobile unit suite**

Run: `./gradlew :androidApp:testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Assemble the mobile APK**

Run: `./gradlew :androidApp:assembleDebug`

Expected: BUILD SUCCESSFUL and a debug APK under `androidApp/build/outputs/apk/debug/`.

- [ ] **Step 5: Inspect final history and worktree**

Run: `git log --oneline -6 && git status --short`

Expected: the design/plan and four focused implementation commits are present; the worktree is clean. Do not install or launch the APK.
