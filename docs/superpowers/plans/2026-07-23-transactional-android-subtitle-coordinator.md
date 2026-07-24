# Transactional Android Subtitle Coordinator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android TV and mobile subtitle selection transactional, identity-safe, and consistent from user intent through session replacement, Media3 mounting, refresh, and persistence.

**Architecture:** A pure shared reducer owns committed and pending subtitle intent. Subtitle-backed replans use a staged `PlaybackSessionManager` transaction: the candidate session is validated before the active attempt changes or the old session stops. Android TV and mobile adapt UI and Media3 events into the shared reducer; stable typed identities replace display-label matching.

**Tech Stack:** Kotlin Multiplatform, Kotlin coroutines/StateFlow, AndroidX Media3, existing protocol-v3 playback APIs, kotlin.test/JUnit, Gradle.

## Global Constraints

- Work only on Android shared, Android mobile, and Android TV playback.
- Do not modify SiloCast.
- Do not modify server code or API contracts.
- Do not add or modify observability, GlitchTip, or Sentry.
- Do not modify workflows or trigger remote builds.
- Do not install or launch a device build without explicit user approval.
- Preserve pre-playback subtitle selection and existing subtitle appearance behavior.
- The committed subtitle remains active while a server-backed replacement displays `Applying…`.
- A branch is ready only after the full suite, both debug assemblies, prohibited-scope audit, and a whole-range review with zero Critical and zero Important findings.

---

### Task 1: Typed subtitle identity and pure transition reducer

**Files:**
- Create: `shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/SubtitleTransition.kt`
- Create: `shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/SubtitleTransitionTest.kt`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoices.kt`
- Test: `shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/PlaybackSubtitleChoicesTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface SubtitleIdentity`
  - `data class SubtitleMediaIdentity`
  - `data class SubtitleTransitionState`
  - `sealed interface SubtitleTransitionEvent`
  - `sealed interface SubtitleTransitionEffect`
  - `fun reduceSubtitleTransition(state, event): SubtitleTransitionResult`
  - `fun rebaseDownloadedSubtitleUrl(url, targetSessionId): String`
- Consumes: existing `PlayerSubtitleInfo`, `SubtitleTrack`, and downloaded-source conventions.

- [ ] **Step 1: Write failing reducer tests**

Cover these exact event sequences:

```kotlin
@Test
fun `A remains committed while B applies then B commits`() {
    val initial = SubtitleTransitionState.committed(serverSidecar(3))
    val applying = reduceSubtitleTransition(initial, SelectSubtitle(serverSidecar(4)))
    assertEquals(serverSidecar(3), applying.state.committed.identity)
    assertEquals(serverSidecar(4), applying.state.pending?.identity)
    assertTrue(applying.effects.single() is StageSubtitleReplan)

    val committed = reduceSubtitleTransition(
        applying.state,
        StagedSubtitleValidated(applying.state.pending!!.generation, candidate("s2")),
    )
    assertEquals(serverSidecar(4), committed.state.committed.identity)
    assertNull(committed.state.pending)
    assertEquals(
        listOf(CommitStagedSubtitleReplan::class, PersistSubtitleSelection::class),
        committed.effects.map { it::class },
    )
}
```

Add equivalent tests for A→B→C, A→Off, failure, superseded response, content reset,
local synchronous commit, burn-in validation, downloaded identity, and independent
audio/quality preference merging.

- [ ] **Step 2: Run the shared tests and confirm RED**

Run:

```bash
./gradlew :shared:testDebugUnitTest \
  --tests org.siloserver.silo.model.playback.SubtitleTransitionTest \
  --no-parallel
```

Expected: compilation failure because the transition types do not exist.

- [ ] **Step 3: Implement the typed model and reducer**

Use these public shapes:

```kotlin
sealed interface SubtitleIdentity {
    data object Off : SubtitleIdentity
    data class ServerSidecar(val serverIndex: Int) : SubtitleIdentity
    data class ServerBurnIn(val serverIndex: Int) : SubtitleIdentity
    data class Embedded(val serverIndex: Int, val media: SubtitleMediaIdentity) : SubtitleIdentity
    data class Downloaded(val downloadId: Int, val media: SubtitleMediaIdentity) : SubtitleIdentity
    data class LocalMedia3(val media: SubtitleMediaIdentity) : SubtitleIdentity
}

data class SubtitleMediaIdentity(
    val trackId: String?,
    val label: String?,
    val language: String?,
    val codecFamily: String?,
    val forced: Boolean?,
    val hearingImpaired: Boolean?,
)

data class SubtitleTransitionState(
    val committed: CommittedSubtitle,
    val pending: PendingSubtitle?,
    val nextGeneration: Long,
)

data class SubtitleTransitionResult(
    val state: SubtitleTransitionState,
    val effects: List<SubtitleTransitionEffect>,
)
```

The reducer must never replace `committed` on `SelectSubtitle`; only a matching
validated generation may emit `CommitStagedSubtitleReplan` and
`PersistSubtitleSelection`. Failure and supersession retain committed state.

- [ ] **Step 4: Add downloaded URL rebasing**

Implement a path-only replacement that changes exactly the session segment in:

```text
/stream/{session}/subtitles/{track}.{extension}
```

It must preserve absolute URL prefixes, query strings, indices, and extensions.
Nonmatching URLs remain unchanged.

- [ ] **Step 5: Run focused and shared module tests**

```bash
./gradlew :shared:testDebugUnitTest \
  --tests org.siloserver.silo.model.playback.SubtitleTransitionTest \
  --tests org.siloserver.silo.model.playback.PlaybackSubtitleChoicesTest \
  --no-parallel
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/org/siloserver/silo/model/playback \
        shared/src/commonTest/kotlin/org/siloserver/silo/model/playback
git commit -m "feat(subtitles): add transactional transition model [skip ci]"
```

---

### Task 2: Stage and commit playback replans transactionally

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackSessionManager.kt`
- Create: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackSessionManagerStagedReplanTest.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackSessionManagerSeekReanchorTest.kt`

**Interfaces:**
- Consumes: Task 1 subtitle validation results.
- Produces:
  - `data class StagedVideoReplan`
  - `suspend fun stageActiveVideoSessionReplan(...): ApiResult<StagedVideoReplan>`
  - `suspend fun commitStagedVideoReplan(staged): ApiResult<VideoSessionStartV3.Ready>`
  - `suspend fun discardStagedVideoReplan(staged)`
- Preserves: existing `replanActiveVideoSession` as an immediate stage-and-commit wrapper for non-subtitle callers.

- [ ] **Step 1: Write failing transaction tests**

Create tests proving:

```kotlin
@Test
fun `staging replacement does not swap attempt or stop old session`() = runTest {
    val staged = harness.manager.stageActiveVideoSessionReplan(
        classification = "subtitle_track_changed",
        positionSeconds = 42.0,
        audioTrackIndex = 0,
        subtitleTrackIndex = 4,
    )
    assertIs<ApiResult.Success<StagedVideoReplan>>(staged)
    assertEquals("s1", harness.manager.activeSessionIdForTest())
    assertEquals(emptyList(), harness.repository.stoppedSessions)
}
```

Also test: commit swaps once then stops S1; discard stops only S2; stale handle cannot
commit; content reset invalidates the handle; burn-in candidate can commit without a
sidecar; sidecar candidate cannot commit without exact artifact.

- [ ] **Step 2: Run focused manager tests and confirm RED**

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests org.siloserver.silo.common.player.PlaybackSessionManagerStagedReplanTest \
  --no-parallel
```

Expected: compilation failure for missing staged APIs.

- [ ] **Step 3: Extract candidate construction from immediate adoption**

`stageActiveVideoSessionReplan` performs the existing server request and Media3 plan
validation, but does not mutate `activeVideoAttempt`, begin passthrough suppression,
emit `plan_selected`, or stop the previous session.

`StagedVideoReplan` carries:

```kotlin
data class StagedVideoReplan(
    val basePlaybackAttemptId: String,
    val baseSessionId: String,
    val basePlanAttemptId: String,
    val candidate: VideoSessionStartV3.Ready,
    val candidateSessionId: String,
)
```

- [ ] **Step 4: Implement one-use commit and discard**

Commit reacquires `videoAttemptMutex`, verifies the active attempt still matches all
base identifiers, installs the candidate, emits `plan_selected`, and only then stops
the old session. A consumed or stale handle returns a nonfatal 409 and stops only the
candidate when it differs from the active session.

- [ ] **Step 5: Preserve existing immediate behavior**

Make `replanActiveVideoSession` call stage then commit. Existing seek/recovery tests
must remain unchanged except for harness access needed by the staged tests.

- [ ] **Step 6: Run the Android shared suite**

```bash
./gradlew :android-shared:testDebugUnitTest --no-parallel
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackSessionManager.kt \
        android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player
git commit -m "feat(playback): stage subtitle replans before commit [skip ci]"
```

---

### Task 3: Stable Media3 identity and mount resolution

**Files:**
- Create: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleMountResolver.kt`
- Create: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleMountResolverTest.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/SubtitleManager.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/SubtitleManagerTrackSelectionTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt`
- Test: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/PlayerTrackEntriesTest.kt`

**Interfaces:**
- Consumes: `SubtitleIdentity` and `SubtitleMediaIdentity`.
- Produces:
  - `fun subtitleArtifactTrackId(serverIndex: Int): String`
  - `fun resolveMountedSubtitle(identity, tracks): MountedSubtitleMatch?`
  - `MountedSubtitleTrack` containing ID, label, language, codec, forced, and hearing-impaired flags.

- [ ] **Step 1: Write failing identity tests**

Test exact `silo-subtitle:<index>` matching, same-label sidecars, exact local ID,
ID-change metadata fallback, forced/full PGS duplicates, ID-less legacy text,
embedded bitmap ordinary decoder IDs, and refusal to match a local non-server ID to
a server sidecar label.

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests org.siloserver.silo.common.player.SubtitleMountResolverTest \
  --no-parallel
```

- [ ] **Step 3: Implement stable sidecar IDs**

Every external `MediaItem.SubtitleConfiguration` receives:

```kotlin
.setId(subtitleArtifactTrackId(subtitle.index))
```

The resolver performs global exact-ID passes before type-specific metadata fallback.
Display labels never establish server identity by themselves.

- [ ] **Step 4: Extract complete Media3 metadata**

TV extraction and the shared resolver must retain `Format.id`, normalized codec/MIME,
`C.SELECTION_FLAG_FORCED`, and hearing-impaired role flags. Mobile selection must call
the same resolver rather than its independent label-first path.

- [ ] **Step 5: Run identity and existing subtitle suites**

```bash
./gradlew :android-shared:testDebugUnitTest :androidTvApp:testDebugUnitTest \
  --tests org.siloserver.silo.common.player.SubtitleMountResolverTest \
  --tests org.siloserver.silo.common.player.SubtitleManagerTrackSelectionTest \
  --tests org.siloserver.silo.tv.ui.screens.player.PlayerTrackEntriesTest \
  --no-parallel
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player \
        android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player \
        androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt \
        androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/PlayerTrackEntriesTest.kt
git commit -m "feat(subtitles): resolve Media3 tracks by typed identity [skip ci]"
```

---

### Task 4: Integrate the transactional coordinator on Android mobile

**Files:**
- Create: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapter.kt`
- Create: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleTransactionAdapterTest.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt`
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModelSharedCoordinatorTest.kt`
- Modify: `androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player/MobileSubtitleAutoSelectionTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3 reducer, staged manager, and mount resolver.
- Produces: mobile `pendingSubtitleIdentity`, `subtitleApplying`, and reducer-effect execution.

- [ ] **Step 1: Write adapter state-machine tests**

Use a fake staged manager and persistence port to exercise:

- pre-playback selection;
- A→B success;
- A→B→C before A responds;
- A→Off;
- missing sidecar and network failure;
- burn-in with no sidecar;
- downloaded URL rebasing;
- local/embedded synchronous selection;
- content/version reset;
- persistence only after commit.

- [ ] **Step 2: Run focused mobile tests and confirm RED**

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests org.siloserver.silo.android.ui.screens.player.MobileSubtitleTransactionAdapterTest \
  --no-parallel
```

- [ ] **Step 3: Replace optimistic ViewModel mutation**

`onSelectSubtitle` sends a typed intent to the adapter. While applying, keep
`selectedSubtitleIndex` mapped to the committed identity and expose the pending row
separately. Do not remount or persist the pending choice.

- [ ] **Step 4: Execute staged effects**

The adapter stages, validates, and commits only the latest generation. It discards
superseded candidates. A failed candidate clears applying state and leaves the current
stream, artifacts, mounted selection, and saved preference unchanged.

- [ ] **Step 5: Own refresh responses**

Mobile refresh ownership includes content generation, content/file/version/session
identity, refresh generation, and subtitle intent generation. Manual selection and
session replacement invalidate older refreshes. Auto-selection enters the same reducer
instead of writing `selectedSubtitleIndex` directly.

- [ ] **Step 6: Render pending state**

The subtitle menu keeps the committed row selected and displays `Applying…` on the
pending row. No other player controls change.

- [ ] **Step 7: Run the mobile suite**

```bash
./gradlew :androidApp:testDebugUnitTest --no-parallel
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player \
        androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/player
git commit -m "feat(mobile): apply subtitles transactionally [skip ci]"
```

---

### Task 5: Integrate the transactional coordinator on Android TV

**Files:**
- Create: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleTransactionAdapter.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleTransactionAdapterTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerHud.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModelSharedCoordinatorTest.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/SubtitleRemountReselectionTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3 reducer, staged manager, and mount resolver.
- Produces: TV pending HUD state, staged-effect execution, and typed remount restoration.

- [ ] **Step 1: Write TV adapter tests**

Mirror every mobile transition test and add:

- HUD catalog selection while controls are open;
- same-label forced/full external rows;
- duplicate English forced/full PGS rows;
- catalog B followed by embedded/local C;
- pending subtitle plus audio and quality changes in both orders;
- remount callback ordering;
- refresh from download, AI completion, and realtime notification;
- reset while refresh or staged replan is suspended.

- [ ] **Step 2: Run focused TV tests and confirm RED**

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests org.siloserver.silo.tv.ui.screens.player.TvSubtitleTransactionAdapterTest \
  --no-parallel
```

- [ ] **Step 3: Replace pending label/index fields**

Remove catalog-label latches and ViewModel-specific subtitle request IDs. The adapter
stores one shared `SubtitleTransitionState`; audio, quality, route, and subtitle fields
merge independently into the next staged request.

- [ ] **Step 4: Restore committed identity after mount**

After transport mount, resolve the committed identity against the reported Media3
snapshot and emit exactly one selection request. Superseded intent never arms a remount.
Distinct meaningful snapshots bound fallback attempts.

- [ ] **Step 5: Serialize refresh ownership**

Increment TV refresh generation per request. Only the newest owned response may merge
downloaded rows. Manual selection invalidates auto-select ownership. Downloaded URLs
are created for the staged/active target session.

- [ ] **Step 6: Render pending HUD state**

Keep the committed row checked. Show `Applying…` on the pending row. Failure clears the
pending marker and leaves playback untouched.

- [ ] **Step 7: Run the TV suite**

```bash
./gradlew :androidTvApp:testDebugUnitTest --no-parallel
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player \
        androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player
git commit -m "feat(tv): apply subtitles transactionally [skip ci]"
```

---

### Task 6: End-to-end regression matrix and release gate

**Files:**
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/SubtitleTransactionIntegrationTest.kt`
- Modify: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackSessionManagerStagedReplanTest.kt`
- Modify: `docs/superpowers/plans/2026-07-23-transactional-android-subtitle-coordinator.md`

**Interfaces:**
- Consumes all preceding tasks through the production TV adapter, staged manager
  port, playback manager, lifecycle, and typed Media3 remount resolver.
- Produces verification evidence only; no new production behavior. Mobile
  remains covered by its existing adapter, manager, lifecycle, and acceptance
  suites because there is no shared production effect executor that an
  `android-shared` test could faithfully instantiate.

- [x] **Step 1: Add cross-layer integration scenarios**

The integration harness uses the real TV transaction adapter, real staged manager
port, real manager and lifecycle, a MockEngine repository, and a fake Media3 edge
that resolves through the production typed remount path. It proves:

```text
S1/A -> request B -> staged/committed S2/B pending -> mount exact S2/B -> confirm -> stop S1
S1/A -> request B -> request Off -> discard S2 -> S1/A remains -> mount/confirm Off
S1/A -> request burn-in B -> staged S2 without sidecar -> confirm without text mount
S1/download D -> audio replan -> S2/download D URL -> exact mount -> confirm
S1/A -> capture refresh -> real content/session S2 -> stale S1 response ignored
```

- [x] **Step 2: Run focused integration tests**

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests org.siloserver.silo.tv.ui.screens.player.SubtitleTransactionIntegrationTest \
  --no-parallel
```

Observed: `BUILD SUCCESSFUL`; 5 tests, 0 failures, 0 errors.

- [x] **Step 3: Run the complete local test suite**

```bash
./gradlew test --no-parallel --console=plain --rerun-tasks
```

Observed: `BUILD SUCCESSFUL in 1m 11s`; 236 actionable tasks executed,
zero failed tests. The forced run exposed stale timing assumptions in five
existing manager assertions and one new mount-settlement assertion. Those
tests now await the real asynchronous boundaries while preserving exact
session identities and stop counts; both debug and release focused suites
were rerun before this complete gate.

- [x] **Step 4: Respect the current no-assembly/device constraint**

APK assembly/install and device launch are explicitly outside the authorized
Task 6 scope for this session and were not performed.

- [x] **Step 5: Audit the complete diff**

```bash
git diff --check 44976539885445fb0d7c753ee0ae4ba7ec11e70a..HEAD
git diff --check
git status --short
git diff --name-only 44976539885445fb0d7c753ee0ae4ba7ec11e70a..HEAD |
  rg -i 'silocast|observability|glitchtip|sentry|workflow'
```

Observed: no whitespace errors and no prohibited path matches. Before the
Task 6 commit, the worktree contains only the two verification test files and
this plan.

- [x] **Step 6: Request one whole-range release review**

Review the complete implementation range against the approved design. The gate passes
only with zero Critical and zero Important findings. Fixes require focused tests and a
fresh whole-range re-review.

Observed: two independent final reviews passed with zero Critical and zero
Important findings after the complete flake audit.

- [x] **Step 7: Commit verification tests and plan checkmarks**

```bash
git add android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/PlaybackSessionManagerStagedReplanTest.kt \
        androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/SubtitleTransactionIntegrationTest.kt \
        docs/superpowers/plans/2026-07-23-transactional-android-subtitle-coordinator.md
git commit -m "test(subtitles): verify transactional playback flow [skip ci]"
```

- [x] **Step 8: Stop before device actions**

Report the local release-gate result. Do not install or launch on Shield or Google
Streamer until the user explicitly approves device validation.
