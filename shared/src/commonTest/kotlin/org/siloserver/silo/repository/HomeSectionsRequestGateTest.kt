package org.siloserver.silo.repository

import kotlinx.coroutines.CompletableDeferred
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
        assertEquals(
            "profile-b",
            firstId(gate.execute(scopeB, HomeRequestPolicy.NORMAL) { success("wrong") }),
        )
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
