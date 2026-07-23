# Android Performance Audit Corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct the four audit blockers on `perf/android-client-performance-stability-v2` without adding security-hardening scope to that PR.

**Architecture:** Capture a credential-free playback identity when a player session starts and carry it through the final-position queue. Make Home request reuse validate and prune identity-scoped entries. Keep TV structural state clock-free while routing every duration consumer to the dedicated playback clock or Media3 controller.

**Tech Stack:** Kotlin 2.1.20, Kotlin coroutines/Flow, Koin, Room, Media3, Kotlin Test, Android JVM unit tests.

## Global Constraints

- Work on `perf/android-client-performance-stability-v2`.
- Do not include the security design/plan commits from `security/android-project-hardening`.
- Do not change SiloCast.
- Final-position submission must remain non-blocking on the main thread.
- Queued identity objects must contain no access, refresh, or profile token.
- A stale identity write must be dropped, never redirected to the active profile.
- Use red-green-refactor for every production change.

---

### Task 1: Identity-bound final playback positions

**Files:**
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/FinalPlaybackPositionWriter.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/di/PlayerInfraModule.kt`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/port/UserItemStatePort.kt`
- Modify: `android-shared/src/androidMain/kotlin/org/siloserver/silo/common/data/repository/RoomUserItemStateRepository.kt`
- Modify: `androidApp/src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt`
- Modify: phone/TV Koin constructor bindings
- Test: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/player/FinalPlaybackPositionWriterTest.kt`
- Test: `android-shared/src/androidUnitTest/kotlin/org/siloserver/silo/common/data/repository/RoomUserItemStateRepositoryTest.kt`

**Interfaces:**
- Produces:
  `PlaybackWriteScope(serverId, profileId, credentialGenerationId, identityGeneration)`.
- Produces:
  `suspend fun FinalPlaybackPositionWriter.captureScope(): PlaybackWriteScope?`.
- Produces:
  `UserItemStatePort.recordPosition(scope, contentId, fileId, positionSeconds, durationSeconds): Boolean`.

- [ ] **Step 1: Write failing queue identity tests**

Add tests proving different scopes do not coalesce and no token-shaped field is
present:

```kotlin
private val scopeA = PlaybackWriteScope("server", "profile-a", null, 4L)
private val scopeB = PlaybackWriteScope("server", "profile-b", null, 5L)

@Test
fun pendingSnapshotsFromDifferentProfilesNeverCoalesce() = runTest {
    val written = mutableListOf<FinalPlaybackPosition>()
    val writer = FinalPlaybackPositionWriter(
        scope = backgroundScope,
        scopeProvider = { null },
        write = { written += it },
    )

    writer.submit(FinalPlaybackPosition(scopeA, "movie", 7, 10.0, 100.0))
    writer.submit(FinalPlaybackPosition(scopeB, "movie", 7, 20.0, 100.0))
    runCurrent()

    assertEquals(listOf(scopeA, scopeB), written.map { it.scope })
}

@Test
fun playbackScopeContainsIdentifiersOnly() {
    assertEquals(
        setOf("serverId", "profileId", "credentialGenerationId", "identityGeneration"),
        PlaybackWriteScope::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet(),
    )
}
```

- [ ] **Step 2: Run the queue tests and verify RED**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests org.siloserver.silo.common.player.FinalPlaybackPositionWriterTest
```

Expected: compilation fails because `PlaybackWriteScope`, `scopeProvider`, and
the scoped payload do not exist.

- [ ] **Step 3: Add the credential-free scope and scoped queue key**

Implement:

```kotlin
data class PlaybackWriteScope(
    val serverId: String,
    val profileId: String,
    val credentialGenerationId: String?,
    val identityGeneration: Long,
)

data class FinalPlaybackPosition(
    val scope: PlaybackWriteScope,
    val contentId: String,
    val fileId: Int,
    val positionSeconds: Double,
    val durationSeconds: Double?,
)

private data class Key(
    val scope: PlaybackWriteScope,
    val contentId: String,
    val fileId: Int,
)
```

Give the writer a suspend `scopeProvider`. `captureScope()` converts
`AuthScopeSnapshot` to `PlaybackWriteScope` and rejects a missing profile.
`submit()` remains synchronous and uses the already captured scope.

- [ ] **Step 4: Run the queue tests and verify GREEN**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Write failing repository stale-scope tests**

Add tests to `RoomUserItemStateRepositoryTest`:

```kotlin
@Test
fun scopedPositionRejectsProfileThatIsNoLongerCurrent() = runTest {
    val captured = PlaybackWriteScope("server", "profile-a", null, 4L)
    currentSnapshot = authScope("server", "profile-b", generation = 5L)

    assertFalse(repository.recordPosition(captured, "movie", 7, 42.0, 100.0))
    assertNull(db.userItemStateDao().get("server", "profile-a", "movie", 7))
    assertNull(db.userItemStateDao().get("server", "profile-b", "movie", 7))
}

@Test
fun scopedPositionWritesOnlyToMatchingCapturedIdentity() = runTest {
    val captured = PlaybackWriteScope("server", "profile-a", null, 4L)
    currentSnapshot = authScope("server", "profile-a", generation = 4L)

    assertTrue(repository.recordPosition(captured, "movie", 7, 42.0, 100.0))
    assertEquals(
        42.0,
        db.userItemStateDao().get("server", "profile-a", "movie", 7)?.positionSeconds,
    )
}
```

- [ ] **Step 6: Run the repository tests and verify RED**

Run:

```bash
./gradlew :android-shared:testDebugUnitTest \
  --tests org.siloserver.silo.common.data.repository.RoomUserItemStateRepositoryTest
```

Expected: compilation fails because the scoped overload is missing.

- [ ] **Step 7: Implement the scoped repository write**

Add the scoped overload to `UserItemStatePort`, returning `Boolean`. In the
Room implementation:

```kotlin
override suspend fun recordPosition(
    scope: PlaybackWriteScope,
    contentId: String,
    fileId: Int,
    positionSeconds: Double,
    durationSeconds: Double?,
): Boolean {
    val current = snapshotProvider() ?: return false
    if (current.serverId != scope.serverId ||
        current.profileId != scope.profileId ||
        current.credentialGenerationId != scope.credentialGenerationId ||
        current.identityGeneration != scope.identityGeneration
    ) return false

    return recordPositionOwned(
        serverId = scope.serverId,
        profileId = scope.profileId,
        contentId = contentId,
        fileId = fileId,
        positionSeconds = positionSeconds,
        durationSeconds = durationSeconds,
    )
}
```

Extract the existing transaction into `recordPositionOwned`. Keep the existing
unscoped method for periodic foreground writes.

- [ ] **Step 8: Run repository tests and verify GREEN**

Run the command from Step 6. Expected: PASS.

- [ ] **Step 9: Capture scope at session start and submit it at teardown**

At the start of each phone/TV `loadContent` session:

```kotlin
private var finalPositionScope: PlaybackWriteScope? = null

finalPositionScope = finalPlaybackPositionWriter.captureScope()
```

All teardown submissions use a local copy:

```kotlin
val scope = finalPositionScope
if (scope != null && contentId.isNotBlank() && fileId != null) {
    finalPlaybackPositionWriter.submit(
        FinalPlaybackPosition(scope, contentId, fileId, position, duration),
    )
}
```

Wire `scopeProvider = { tokenManager.snapshotCurrentScope() }` and invoke the
scoped repository write in `PlayerInfraModule`. Request sync only when the
write returns `true`.

- [ ] **Step 10: Run phone/TV player and repository tests**

Run:

```bash
./gradlew \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add android-shared shared androidApp androidTvApp
git commit -m "fix(android): bind final playback writes to identity"
```

---

### Task 2: Home cache identity validation and credential removal

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/HomeSectionsRequestGate.kt`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/SectionRepository.kt`
- Test: `shared/src/commonTest/kotlin/org/siloserver/silo/repository/HomeSectionsRequestGateTest.kt`

**Interfaces:**
- `HomeRequestScope` contains only server ID, profile ID, credential generation
  ID, and identity generation.
- `execute` validates identity before returning either cached or fetched data.

- [ ] **Step 1: Write failing cached-return and secret-free-key tests**

```kotlin
@Test
fun cachedResultIsRejectedAfterScopeBecomesStale() = runTest {
    val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
    var current = true
    assertEquals(
        "cached",
        firstId(gate.execute(scopeA, NORMAL, { current }) { success("cached") }),
    )

    current = false
    assertIs<ApiResult.NetworkError>(
        gate.execute(scopeA, NORMAL, { current }) { success("must-not-run") },
    )
}

@Test
fun homeScopeContainsNoProfileToken() {
    val scope = HomeRequestScope(
        serverId = "server-a",
        profileId = "profile-a",
        credentialGenerationId = "credential-generation",
        identityGeneration = 4L,
    )
    assertFalse(scope.toString().contains("profileToken", ignoreCase = true))
    assertFalse(scope.toString().contains("profile-secret"))
}
```

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew :shared:testDebugUnitTest \
  --tests org.siloserver.silo.repository.HomeSectionsRequestGateTest
```

Expected: cached result is returned and token-field assertion fails.

- [ ] **Step 3: Remove credentials and validate all return paths**

Remove `profileToken`. Before returning `Selection.Ready`, call
`isScopeCurrent`. After awaiting `Selection.Pending`, validate again at the
caller boundary:

```kotlin
val result = when (selection) {
    is Selection.Ready -> selection.result
    is Selection.Pending -> selection.result.await()
}
return if (runCatching { isScopeCurrent() }.getOrDefault(false)) {
    result
} else {
    ApiResult.NetworkError(HomeRequestScopeChangedException())
}
```

Build scope keys in `SectionRepository` from non-secret generations.

- [ ] **Step 4: Add failing physical-pruning test**

Expose an internal `entryCountsForTest()` or injected maximum-count hook and
assert that advancing beyond freshness removes expired entries:

```kotlin
clock += 11.seconds
gate.execute(scopeB, NORMAL) { success("b") }
assertEquals(HomeGateEntryCounts(inFlight = 0, cached = 1), gate.entryCountsForTest())
```

- [ ] **Step 5: Run and verify RED**

Run the test command from Step 2. Expected: cached count remains 2.

- [ ] **Step 6: Prune expired and superseded entries**

Inside the gate mutex, before selection:

```kotlin
cached.entries.removeAll { (_, entry) ->
    (entry.completedAt + freshnessWindow).hasPassedNow()
}
cached.keys.removeAll { key ->
    key.serverId == scopeKey.serverId &&
        key.profileId == scopeKey.profileId &&
        key.identityGeneration != scopeKey.identityGeneration
}
```

Only remove completed in-flight entries through their `finally` block; never
cancel shared work while a waiter still owns it.

- [ ] **Step 7: Run all shared tests and commit**

```bash
./gradlew :shared:testDebugUnitTest
git add shared
git commit -m "fix(android): keep home cache identity current"
```

---

### Task 3: Route every TV duration consumer through the playback clock

**Files:**
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerPresentationStateTest.kt`

**Interfaces:**
- Structural `state` may contain zeroed legacy clock fields.
- `clock.position` and `clock.duration` are the only Compose clock values.

- [ ] **Step 1: Write a failing source-policy regression test**

```kotlin
@Test
fun structuralStateIsNeverUsedForPlaybackDuration() {
    val source = playerScreenSource()
    listOf(
        "durationSeconds = state.duration",
        "durationSec = state.duration",
    ).forEach { forbidden ->
        assertFalse(source.contains(forbidden), "forbidden: $forbidden")
    }
}
```

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew :androidTvApp:testDebugUnitTest \
  --tests org.siloserver.silo.tv.ui.screens.player.TvPlayerPresentationStateTest
```

Expected: FAIL on media mount, subtitle refresh, or hold-seek duration.

- [ ] **Step 3: Replace structural clock reads**

Wrap the affected UI/effects in `TvPlayerClockScope` where recomposition is
required. For mount/refresh, use a non-Compose controller/raw-state snapshot:

```kotlin
durationSeconds = viewModel.uiState.value.duration.takeIf { it > 0.0 }
    ?: mediaController?.duration
        ?.takeIf { it > 0L }
        ?.div(1000.0)
```

Use `clock.duration` for hold-seek and HUD rendering. Do not restore root
collection of the high-frequency `uiState`.

- [ ] **Step 4: Run TV tests and verify GREEN**

```bash
./gradlew :androidTvApp:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add androidTvApp
git commit -m "fix(tv): preserve duration outside presentation state"
```

---

### Task 4: Performance-branch verification and handoff

**Files:**
- No production files unless verification exposes a regression.

- [ ] **Step 1: Verify the full branch**

```bash
./gradlew \
  :shared:testDebugUnitTest \
  :android-shared:testDebugUnitTest \
  :androidApp:testDebugUnitTest \
  :androidTvApp:testDebugUnitTest \
  :androidApp:assembleDebug \
  :androidTvApp:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run lint and classify only pre-existing failures**

```bash
./gradlew :androidApp:lintDebug :androidTvApp:lintDebug
```

Expected: no new errors relative to base. Any remaining inherited lint failure
is documented with exact file and rule.

- [ ] **Step 3: Confirm security/SiloCast exclusion and clean state**

```bash
git diff --name-only 428e9678e7dfe47bb43a9b7dad4c67320d00a023..HEAD \
  | rg 'SiloCast|android-project-security-hardening' && exit 1 || true
git status --short
```

Expected: no SiloCast/security-plan paths and no uncommitted changes.

- [ ] **Step 4: Rebase the security branch onto this verified head**

From the security worktree:

```bash
git rebase perf/android-client-performance-stability-v2
```

Expected: the design/plan commits replay cleanly above the corrected
performance head.
