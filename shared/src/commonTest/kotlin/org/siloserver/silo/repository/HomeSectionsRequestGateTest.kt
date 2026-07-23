package org.siloserver.silo.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionsResponse
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
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
        assertEquals(
            "shared",
            firstId(gate.execute(scopeA, HomeRequestPolicy.NORMAL) { success("wrong") }),
        )
        assertEquals(1, calls)
    }

    @Test
    fun staleWaiterRejectsSharedResultWithoutCancellingOwner() = runTest {
        val release = CompletableDeferred<Unit>()
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        val owner = async {
            gate.execute(
                scopeKey = scopeA,
                policy = HomeRequestPolicy.NORMAL,
                isScopeCurrent = { true },
            ) {
                release.await()
                success("shared")
            }
        }
        runCurrent()
        val staleWaiter = async {
            gate.execute(
                scopeKey = scopeA,
                policy = HomeRequestPolicy.NORMAL,
                isScopeCurrent = { false },
            ) {
                success("must-not-run")
            }
        }
        runCurrent()

        release.complete(Unit)

        assertEquals("shared", firstId(owner.await()))
        assertIs<ApiResult.NetworkError>(staleWaiter.await())
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
    fun expiredCompletedEntriesArePhysicallyPruned() = runTest {
        val clock = TestTimeSource()
        val gate = HomeSectionsRequestGate(timeSource = clock, workerScope = backgroundScope)

        gate.execute(scopeA, HomeRequestPolicy.NORMAL) { success("a") }
        assertEquals(HomeGateEntryCounts(inFlight = 0, cached = 1), gate.entryCountsForTest())

        clock += 11.seconds
        gate.execute(scopeB, HomeRequestPolicy.NORMAL) { success("b") }

        assertEquals(HomeGateEntryCounts(inFlight = 0, cached = 1), gate.entryCountsForTest())
    }

    @Test
    fun supersededIdentityEntriesArePhysicallyPruned() = runTest {
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        val priorIdentity = HomeRequestScope(
            serverId = "server-a",
            profileId = "profile-a",
            credentialGenerationId = "credential-generation-a",
            identityGeneration = 4L,
        )
        val currentIdentity = priorIdentity.copy(
            credentialGenerationId = "credential-generation-b",
            identityGeneration = 6L,
        )

        gate.execute(priorIdentity, HomeRequestPolicy.NORMAL) { success("prior") }
        gate.execute(currentIdentity, HomeRequestPolicy.NORMAL) { success("current") }

        assertEquals(HomeGateEntryCounts(inFlight = 0, cached = 1), gate.entryCountsForTest())
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
        assertEquals(
            "profile-b",
            firstId(gate.execute(scopeB, HomeRequestPolicy.NORMAL) { success("wrong") }),
        )
    }

    @Test
    fun reauthenticatedScopeNeverReusesPriorIdentityResult() = runTest {
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        val priorIdentity = HomeRequestScope(
            serverId = "server-a",
            profileId = "profile-a",
            credentialGenerationId = "credential-generation-a",
            identityGeneration = 4L,
        )
        val reauthenticatedIdentity = priorIdentity.copy(identityGeneration = 6L)
        var calls = 0

        val prior = gate.execute(priorIdentity, HomeRequestPolicy.NORMAL) {
            calls += 1
            success("prior")
        }
        val current = gate.execute(reauthenticatedIdentity, HomeRequestPolicy.NORMAL) {
            calls += 1
            success("current")
        }

        assertEquals("prior", firstId(prior))
        assertEquals("current", firstId(current))
        assertEquals(2, calls)
    }

    @Test
    fun cachedResultIsRejectedAfterScopeBecomesStale() = runTest {
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        var current = true
        assertEquals(
            "cached",
            firstId(
                gate.execute(scopeA, HomeRequestPolicy.NORMAL, { current }) {
                    success("cached")
                },
            ),
        )

        current = false
        assertIs<ApiResult.NetworkError>(
            gate.execute(scopeA, HomeRequestPolicy.NORMAL, { current }) {
                success("must-not-run")
            },
        )
    }

    @Test
    fun fetchedScopeValidationCancellationPropagates() = runTest {
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)

        assertFailsWith<CancellationException> {
            gate.execute(
                scopeKey = scopeA,
                policy = HomeRequestPolicy.NORMAL,
                isScopeCurrent = { throw CancellationException("cancelled") },
            ) {
                success("fetched")
            }
        }
    }

    @Test
    fun cachedScopeValidationCancellationPropagates() = runTest {
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        gate.execute(scopeA, HomeRequestPolicy.NORMAL) { success("cached") }

        assertFailsWith<CancellationException> {
            gate.execute(
                scopeKey = scopeA,
                policy = HomeRequestPolicy.NORMAL,
                isScopeCurrent = { throw CancellationException("cancelled") },
            ) {
                success("must-not-run")
            }
        }
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

    @Test
    fun identityChangeWhileFetchIsDelayedRejectsAndDoesNotCacheResult() = runTest {
        val release = CompletableDeferred<Unit>()
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        var currentScope = scopeA
        var calls = 0
        val delayed = async {
            gate.execute(
                scopeKey = scopeA,
                policy = HomeRequestPolicy.NORMAL,
                isScopeCurrent = { currentScope == scopeA },
            ) {
                calls += 1
                release.await()
                success("stale")
            }
        }
        runCurrent()

        currentScope = scopeB
        release.complete(Unit)
        assertIs<ApiResult.NetworkError>(delayed.await())

        currentScope = scopeA
        assertEquals(
            "fresh",
            firstId(
                gate.execute(
                    scopeKey = scopeA,
                    policy = HomeRequestPolicy.NORMAL,
                    isScopeCurrent = { true },
                ) {
                    calls += 1
                    success("fresh")
                },
            ),
        )
        assertEquals(2, calls)
    }

    @Test
    fun missingScopeFetchIsRejectedWhenIdentityChanges() = runTest {
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)
        var current = true

        val result = gate.execute(
            scopeKey = null,
            policy = HomeRequestPolicy.NORMAL,
            isScopeCurrent = { current },
        ) {
            current = false
            success("stale")
        }

        assertIs<ApiResult.NetworkError>(result)
    }

    @Test
    fun missingScopeValidationCancellationPropagates() = runTest {
        val gate = HomeSectionsRequestGate(workerScope = backgroundScope)

        assertFailsWith<CancellationException> {
            gate.execute(
                scopeKey = null,
                policy = HomeRequestPolicy.NORMAL,
                isScopeCurrent = { throw CancellationException("cancelled") },
            ) {
                success("fetched")
            }
        }
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
