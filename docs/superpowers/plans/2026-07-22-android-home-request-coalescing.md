# Android Home Request Coalescing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Coalesce overlapping Home requests and reuse successful responses for ten seconds without weakening explicit refreshes or server/profile isolation.

**Architecture:** A focused request gate inside the shared repository owns single-flight and the in-memory freshness window. It keys work by server/profile identifiers captured from `TokenManager`, while `HomeViewModel` distinguishes normal background refreshes from forced user/playback-return refreshes and Android TV suppresses only the initial navigation resume callback.

**Tech Stack:** Kotlin Multiplatform, Kotlin coroutines, Ktor, Koin, AndroidX Lifecycle, Jetpack Compose for TV, kotlin.test, kotlinx-coroutines-test.

## Global Constraints

- The freshness window is exactly ten seconds from successful response completion.
- Only `ApiResult.Success` is reusable; failures never extend freshness.
- Forced refreshes bypass completed cache entries but join matching in-flight work.
- Cache and in-flight work are isolated by `(serverId, profileId)`.
- Missing auth scope disables reuse rather than risking cross-user data.
- Access, refresh, and profile tokens are never retained in the request gate.
- Room remains the durable offline cache; this feature is memory-only.
- No remote push, CI run, device installation, or app launch is part of this plan.

---

## File Structure

- Create `shared/src/commonMain/kotlin/org/siloserver/silo/repository/HomeSectionsRequestGate.kt`: single-flight and ten-second response policy.
- Create `shared/src/commonTest/kotlin/org/siloserver/silo/repository/HomeSectionsRequestGateTest.kt`: deterministic concurrency, freshness, failure, and scope tests.
- Modify `shared/src/commonMain/kotlin/org/siloserver/silo/repository/SectionRepository.kt`: expose normal/forced Home fetch through the gate.
- Modify `shared/src/commonMain/kotlin/org/siloserver/silo/di/RepositoryModule.kt`: provide the active auth scope to the repository.
- Modify `shared/src/commonMain/kotlin/org/siloserver/silo/viewmodel/HomeViewModel.kt`: separate forced resume/user refresh from normal startup/realtime refresh.
- Create `shared/src/commonTest/kotlin/org/siloserver/silo/viewmodel/HomeViewModelRefreshPolicySourceTest.kt`: lock the normal/forced repository wiring.
- Create `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/home/HomeResumeRefreshPolicy.kt`: suppress only the first navigation resume.
- Create `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/home/HomeResumeRefreshPolicyTest.kt`: exercise first and subsequent resume behavior.
- Modify `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/home/TvHomeScreen.kt`: use the resume policy and forced refresh method.
- Modify `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/home/TvHomeScreenSourceTest.kt`: verify lifecycle wiring.

---

### Task 1: Auth-scoped Home request gate

**Files:**
- Create: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/HomeSectionsRequestGate.kt`
- Create: `shared/src/commonTest/kotlin/org/siloserver/silo/repository/HomeSectionsRequestGateTest.kt`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/repository/SectionRepository.kt`
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/di/RepositoryModule.kt`

**Interfaces:**
- Consumes: `ApiResult<SectionsResponse>`, `AuthScopeSnapshot`, `TokenManager.snapshotCurrentScope()`.
- Produces: `HomeSectionsRequestGate.execute(HomeRequestScope?, HomeRequestPolicy, suspend () -> ApiResult<SectionsResponse>)` and `SectionRepository.getHomeSections(forceRefresh: Boolean = false)`.

- [ ] **Step 1: Write the failing request-gate tests**

Create `HomeSectionsRequestGateTest.kt` with real coroutines and a virtual clock:

```kotlin
package org.siloserver.silo.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionsResponse
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.seconds

class HomeSectionsRequestGateTest {
    private val scopeA = HomeRequestScope("server-a", "profile-a")
    private val scopeB = HomeRequestScope("server-a", "profile-b")

    private fun success(id: String): ApiResult<SectionsResponse> = ApiResult.Success(
        SectionsResponse(listOf(ResolvedSection(id = id, sectionType = id, title = id))),
    )

    private fun firstId(result: ApiResult<SectionsResponse>): String =
        assertIs<ApiResult.Success<SectionsResponse>>(result).data.sections.first().id

    @Test
    fun concurrentNormalRequestsShareOneFetch() = runTest {
        val release = CompletableDeferred<Unit>()
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        var calls = 0
        val first = async {
            gate.execute(scopeA, HomeRequestPolicy.NORMAL) {
                calls += 1
                release.await()
                success("shared")
            }
        }
        runCurrent()
        val second = async {
            gate.execute(scopeA, HomeRequestPolicy.NORMAL) {
                calls += 1
                success("duplicate")
            }
        }
        runCurrent()

        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals("shared", firstId(first.await()))
        assertEquals("shared", firstId(second.await()))
    }

    @Test
    fun forcedRequestJoinsMatchingInflightFetch() = runTest {
        val release = CompletableDeferred<Unit>()
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        var calls = 0
        val normal = async {
            gate.execute(scopeA, HomeRequestPolicy.NORMAL) {
                calls += 1
                release.await()
                success("shared")
            }
        }
        runCurrent()
        val forced = async {
            gate.execute(scopeA, HomeRequestPolicy.FORCE) {
                calls += 1
                success("forced")
            }
        }
        runCurrent()

        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals("shared", firstId(normal.await()))
        assertEquals("shared", firstId(forced.await()))
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelSharedWork() = runTest {
        val release = CompletableDeferred<Unit>()
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        var calls = 0
        val owner = async {
            gate.execute(scopeA, HomeRequestPolicy.NORMAL) {
                calls += 1
                release.await()
                success("shared")
            }
        }
        runCurrent()
        val waiter = async {
            gate.execute(scopeA, HomeRequestPolicy.NORMAL) { success("duplicate") }
        }
        runCurrent()

        waiter.cancelAndJoin()
        release.complete(Unit)
        assertEquals("shared", firstId(owner.await()))
        assertEquals("shared", firstId(gate.execute(scopeA, HomeRequestPolicy.NORMAL) { success("wrong") }))
        assertEquals(1, calls)
    }

    @Test
    fun successfulNormalResultIsReusedForExactlyTenSeconds() = runTest {
        val clock = TestTimeSource()
        val gate = HomeSectionsRequestGate(timeSource = clock, workerScope = backgroundScope)
        var calls = 0
        suspend fun fetch(): ApiResult<SectionsResponse> {
            calls += 1
            return success("call-$calls")
        }

        assertEquals("call-1", firstId(gate.execute(scopeA, HomeRequestPolicy.NORMAL, ::fetch)))
        clock += 9.seconds
        assertEquals("call-1", firstId(gate.execute(scopeA, HomeRequestPolicy.NORMAL, ::fetch)))
        clock += 1.seconds
        assertEquals("call-2", firstId(gate.execute(scopeA, HomeRequestPolicy.NORMAL, ::fetch)))
        assertEquals(2, calls)
    }

    @Test
    fun forceBypassesCompletedSuccessfulResult() = runTest {
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        var calls = 0
        suspend fun fetch(): ApiResult<SectionsResponse> {
            calls += 1
            return success("call-$calls")
        }

        assertEquals("call-1", firstId(gate.execute(scopeA, HomeRequestPolicy.NORMAL, ::fetch)))
        assertEquals("call-2", firstId(gate.execute(scopeA, HomeRequestPolicy.FORCE, ::fetch)))
        assertEquals(2, calls)
    }

    @Test
    fun errorsAndNetworkFailuresAreNeverCached() = runTest {
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        var calls = 0
        val serverError = gate.execute(scopeA, HomeRequestPolicy.NORMAL) {
            calls += 1
            ApiResult.Error(503, "unavailable", "Unavailable")
        }
        val networkError = gate.execute(scopeA, HomeRequestPolicy.NORMAL) {
            calls += 1
            ApiResult.NetworkError(IllegalStateException("offline"))
        }
        val recovered = gate.execute(scopeA, HomeRequestPolicy.NORMAL) {
            calls += 1
            success("recovered")
        }

        assertIs<ApiResult.Error>(serverError)
        assertIs<ApiResult.NetworkError>(networkError)
        assertEquals("recovered", firstId(recovered))
        assertEquals(3, calls)
    }

    @Test
    fun differentProfileScopesNeverShareResultsOrInflightWork() = runTest {
        val releaseA = CompletableDeferred<Unit>()
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        var calls = 0
        val requestA = async {
            gate.execute(scopeA, HomeRequestPolicy.NORMAL) {
                calls += 1
                releaseA.await()
                success("profile-a")
            }
        }
        runCurrent()
        val requestB = async {
            gate.execute(scopeB, HomeRequestPolicy.NORMAL) {
                calls += 1
                success("profile-b")
            }
        }
        runCurrent()

        assertEquals(2, calls)
        assertEquals("profile-b", firstId(requestB.await()))
        releaseA.complete(Unit)
        assertEquals("profile-a", firstId(requestA.await()))
        assertEquals("profile-b", firstId(gate.execute(scopeB, HomeRequestPolicy.NORMAL) { success("wrong") }))
    }

    @Test
    fun missingScopeAlwaysFetchesAndNeverReuses() = runTest {
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        var calls = 0
        suspend fun fetch(): ApiResult<SectionsResponse> {
            calls += 1
            return success("call-$calls")
        }

        assertEquals("call-1", firstId(gate.execute(null, HomeRequestPolicy.NORMAL, ::fetch)))
        assertEquals("call-2", firstId(gate.execute(null, HomeRequestPolicy.NORMAL, ::fetch)))
        assertEquals(2, calls)
    }
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./gradlew :shared:testDebugUnitTest --tests '*HomeSectionsRequestGateTest'
```

Expected: compilation fails because `HomeSectionsRequestGate`, `HomeRequestScope`, and `HomeRequestPolicy` do not exist.

- [ ] **Step 3: Implement the minimal request gate**

Create `HomeSectionsRequestGate.kt`:

```kotlin
package org.siloserver.silo.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.model.section.SectionsResponse
import org.siloserver.silo.network.ApiResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal data class HomeRequestScope(val serverId: String, val profileId: String)

internal enum class HomeRequestPolicy { NORMAL, FORCE }

internal class HomeSectionsRequestGate(
    private val freshnessWindow: Duration = 10.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class CachedResult(
        val scope: HomeRequestScope,
        val result: ApiResult.Success<SectionsResponse>,
        val completedAt: TimeMark,
    )

    private sealed interface Selection {
        data class Ready(val result: ApiResult<SectionsResponse>) : Selection
        data class Pending(val result: Deferred<ApiResult<SectionsResponse>>) : Selection
    }

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<HomeRequestScope, Deferred<ApiResult<SectionsResponse>>>()
    private val cached = mutableMapOf<HomeRequestScope, CachedResult>()

    suspend fun execute(
        scopeKey: HomeRequestScope?,
        policy: HomeRequestPolicy,
        fetch: suspend () -> ApiResult<SectionsResponse>,
    ): ApiResult<SectionsResponse> {
        if (scopeKey == null) return fetch()

        val selection = mutex.withLock {
            inFlight[scopeKey]?.let { return@withLock Selection.Pending(it) }

            cached[scopeKey]
                ?.takeIf { entry ->
                    policy == HomeRequestPolicy.NORMAL &&
                        !(entry.completedAt + freshnessWindow).hasPassedNow()
                }
                ?.let { return@withLock Selection.Ready(it.result) }

            lateinit var created: Deferred<ApiResult<SectionsResponse>>
            created = workerScope.async(start = CoroutineStart.LAZY) {
                try {
                    val result = fetch()
                    if (result is ApiResult.Success) {
                        mutex.withLock {
                            cached[scopeKey] = CachedResult(scopeKey, result, timeSource.markNow())
                        }
                    }
                    result
                } finally {
                    mutex.withLock {
                        if (inFlight[scopeKey] === created) inFlight.remove(scopeKey)
                    }
                }
            }
            inFlight[scopeKey] = created
            created.start()
            Selection.Pending(created)
        }

        return when (selection) {
            is Selection.Ready -> selection.result
            is Selection.Pending -> selection.result.await()
        }
    }
}
```

- [ ] **Step 4: Integrate the gate with `SectionRepository` and Koin**

Change `SectionRepository` so its public constructor accepts a scope provider while an internal primary constructor accepts the gate:

```kotlin
class SectionRepository internal constructor(
    private val sectionApi: SectionApi,
    private val catalogCache: CatalogCachePort,
    private val homeScopeProvider: suspend () -> AuthScopeSnapshot?,
    private val homeRequestGate: HomeSectionsRequestGate,
) {
    constructor(
        sectionApi: SectionApi,
        catalogCache: CatalogCachePort = NoOpCatalogCachePort,
        homeScopeProvider: suspend () -> AuthScopeSnapshot? = { null },
    ) : this(sectionApi, catalogCache, homeScopeProvider, HomeSectionsRequestGate())

    suspend fun getHomeSections(forceRefresh: Boolean = false): ApiResult<SectionsResponse> {
        val snapshot = homeScopeProvider()
        val scopeKey = snapshot?.profileId?.let { HomeRequestScope(snapshot.serverId, it) }
        val policy = if (forceRefresh) HomeRequestPolicy.FORCE else HomeRequestPolicy.NORMAL
        return homeRequestGate.execute(scopeKey, policy) { sectionApi.getHomeSections() }
    }
}
```

Add the `AuthScopeSnapshot` import in `SectionRepository.kt`. Preserve every other repository method unchanged.

Change the Koin binding in `RepositoryModule.kt` to:

```kotlin
single {
    val tokenManager = get<org.siloserver.silo.network.TokenManager>()
    SectionRepository(
        sectionApi = get(),
        catalogCache = getOrNull<org.siloserver.silo.repository.port.CatalogCachePort>()
            ?: org.siloserver.silo.repository.port.NoOpCatalogCachePort,
        homeScopeProvider = { tokenManager.snapshotCurrentScope() },
    )
}
```

- [ ] **Step 5: Run focused and repository tests and verify GREEN**

Run:

```bash
./gradlew :shared:testDebugUnitTest --tests '*HomeSectionsRequestGateTest' --tests '*SectionRepositoryCacheTest'
```

Expected: all selected tests pass with zero failures.

- [ ] **Step 6: Commit Task 1**

```bash
git add shared/src/commonMain/kotlin/org/siloserver/silo/repository/HomeSectionsRequestGate.kt \
  shared/src/commonMain/kotlin/org/siloserver/silo/repository/SectionRepository.kt \
  shared/src/commonMain/kotlin/org/siloserver/silo/di/RepositoryModule.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/repository/HomeSectionsRequestGateTest.kt
git commit -m "perf(android): coalesce home section requests"
```

---

### Task 2: Forced refresh and TV resume policy

**Files:**
- Modify: `shared/src/commonMain/kotlin/org/siloserver/silo/viewmodel/HomeViewModel.kt`
- Create: `shared/src/commonTest/kotlin/org/siloserver/silo/viewmodel/HomeViewModelRefreshPolicySourceTest.kt`
- Create: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/home/HomeResumeRefreshPolicy.kt`
- Create: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/home/HomeResumeRefreshPolicyTest.kt`
- Modify: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/home/TvHomeScreen.kt`
- Modify: `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/home/TvHomeScreenSourceTest.kt`

**Interfaces:**
- Consumes: `SectionRepository.getHomeSections(forceRefresh: Boolean)` from Task 1.
- Produces: `HomeViewModel.refreshAfterResume()` and `HomeResumeRefreshPolicy.shouldRefresh(eligible: Boolean)`.

- [ ] **Step 1: Write failing Home refresh-policy tests**

Create `HomeViewModelRefreshPolicySourceTest.kt`:

```kotlin
package org.siloserver.silo.viewmodel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class HomeViewModelRefreshPolicySourceTest {
    private val source = File(
        "src/commonMain/kotlin/org/siloserver/silo/viewmodel/HomeViewModel.kt",
    ).readText()

    @Test
    fun repositoryFetchReceivesTheRequestedRefreshPolicy() {
        assertTrue(source.contains("sectionRepository.getHomeSections(forceRefresh = forceRefresh)"))
    }

    @Test
    fun resumeAndUserRefreshForceWhileRealtimeAndInitialLoadStayNormal() {
        assertTrue(source.contains("fun refreshAfterResume()"))
        assertTrue(source.contains("quietRefresh(forceRefresh = true)"))
        assertTrue(source.contains("quietRefresh(forceRefresh = false)"))
        assertTrue(source.contains("fetchSections(forceRefresh = true)"))
        assertTrue(source.contains("fetchSections()"))
    }
}
```

Create `HomeResumeRefreshPolicyTest.kt`:

```kotlin
package org.siloserver.silo.tv.ui.screens.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeResumeRefreshPolicyTest {
    @Test
    fun firstResumeIsAlwaysConsumedWithoutRefreshing() {
        val policy = HomeResumeRefreshPolicy()

        assertFalse(policy.shouldRefresh(eligible = true))
    }

    @Test
    fun laterEligibleResumeRefreshes() {
        val policy = HomeResumeRefreshPolicy()
        policy.shouldRefresh(eligible = true)

        assertTrue(policy.shouldRefresh(eligible = true))
    }

    @Test
    fun laterSuppressedResumeDoesNotRefresh() {
        val policy = HomeResumeRefreshPolicy()
        policy.shouldRefresh(eligible = true)

        assertFalse(policy.shouldRefresh(eligible = false))
    }
}
```

Extend `TvHomeScreenSourceTest`:

```kotlin
@Test
fun homeSkipsInitialResumeAndForcesLaterEligibleRefreshes() {
    assertTrue(source.contains("resumeRefreshPolicy.shouldRefresh(shouldRefreshOnResume())"))
    assertTrue(source.contains("viewModel.refreshAfterResume()"))
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
./gradlew :shared:testDebugUnitTest --tests '*HomeViewModelRefreshPolicySourceTest' \
  :androidTvApp:testDebugUnitTest --tests '*HomeResumeRefreshPolicyTest' --tests '*TvHomeScreenSourceTest'
```

Expected: the shared source assertions fail and the TV test compilation fails because `HomeResumeRefreshPolicy` does not exist.

- [ ] **Step 3: Implement normal and forced Home ViewModel paths**

In `HomeViewModel.kt`:

```kotlin
private var quietRefreshInFlight = false

fun refreshFromRealtime() = quietRefresh(forceRefresh = false)

fun refreshAfterResume() = quietRefresh(forceRefresh = true)

private fun quietRefresh(forceRefresh: Boolean) {
    if (quietRefreshInFlight || _uiState.value.isRefreshing) return
    quietRefreshInFlight = true
    viewModelScope.launch {
        try {
            fetchSections(forceRefresh = forceRefresh)
        } finally {
            quietRefreshInFlight = false
        }
    }
}
```

Keep `loadSections()` calling `fetchSections()` with its default normal policy. Change explicit `refresh()` to:

```kotlin
fun refresh() {
    viewModelScope.launch {
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        fetchSections(forceRefresh = true)
        _uiState.update { it.copy(isRefreshing = false) }
    }
}
```

Make these two exact replacements in the existing fetch function, leaving its
three existing `when` branch bodies unchanged:

```kotlin
private suspend fun fetchSections() {
```

becomes:

```kotlin
private suspend fun fetchSections(forceRefresh: Boolean = false) {
```

and:

```kotlin
when (val result = sectionRepository.getHomeSections()) {
```

becomes:

```kotlin
when (val result = sectionRepository.getHomeSections(forceRefresh = forceRefresh)) {
```

- [ ] **Step 4: Implement the TV resume policy and wire the screen**

Create `HomeResumeRefreshPolicy.kt`:

```kotlin
package org.siloserver.silo.tv.ui.screens.home

internal class HomeResumeRefreshPolicy {
    private var hasResumed = false

    fun shouldRefresh(eligible: Boolean): Boolean {
        if (!hasResumed) {
            hasResumed = true
            return false
        }
        return eligible
    }
}
```

In `TvHomeScreen`, remember one policy beside the lifecycle owner:

```kotlin
val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
val resumeRefreshPolicy = remember { HomeResumeRefreshPolicy() }
```

Replace the `ON_RESUME` body with:

```kotlin
if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME &&
    resumeRefreshPolicy.shouldRefresh(shouldRefreshOnResume())
) {
    viewModel.refreshAfterResume()
}
```

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```bash
./gradlew :shared:testDebugUnitTest --tests '*HomeViewModelRefreshPolicySourceTest' \
  :androidTvApp:testDebugUnitTest --tests '*HomeResumeRefreshPolicyTest' --tests '*TvHomeScreenSourceTest'
```

Expected: all selected tests pass with zero failures.

- [ ] **Step 6: Commit Task 2**

```bash
git add shared/src/commonMain/kotlin/org/siloserver/silo/viewmodel/HomeViewModel.kt \
  shared/src/commonTest/kotlin/org/siloserver/silo/viewmodel/HomeViewModelRefreshPolicySourceTest.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/home/HomeResumeRefreshPolicy.kt \
  androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/home/TvHomeScreen.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/home/HomeResumeRefreshPolicyTest.kt \
  androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/ui/screens/home/TvHomeScreenSourceTest.kt
git commit -m "fix(tv): avoid duplicate initial home resume refresh"
```

---

### Task 3: Cross-client regression verification

**Files:**
- Verify only; no production file changes.

**Interfaces:**
- Consumes: Task 1 repository API and Task 2 ViewModel/lifecycle behavior.
- Produces: fresh test and assembly evidence for Android shared, mobile, and TV modules.

- [ ] **Step 1: Run all shared and Android-shared tests**

```bash
./gradlew :shared:testDebugUnitTest :android-shared:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 2: Run both application test suites**

```bash
./gradlew :androidApp:testDebugUnitTest :androidTvApp:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 3: Assemble both clients**

```bash
./gradlew :androidApp:assembleDebug :androidTvApp:assembleDebug
```

Expected: `BUILD SUCCESSFUL`; both debug APK families are produced.

- [ ] **Step 4: Verify repository state and commits**

```bash
git status --short
git log --oneline -4
```

Expected: no uncommitted files; the plan commit is followed by `perf(android): coalesce home section requests` and `fix(tv): avoid duplicate initial home resume refresh`.

- [ ] **Step 5: Report the result without installing or launching**

Report the exact tests and assemblies completed, note that no device was modified, and recommend a fresh diagnostics capture after installation as the runtime acceptance check.
