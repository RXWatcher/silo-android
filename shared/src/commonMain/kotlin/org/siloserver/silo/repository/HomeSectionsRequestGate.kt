package org.siloserver.silo.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
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

internal data class HomeRequestScope(
    val serverId: String,
    val profileId: String,
    val credentialGenerationId: String? = null,
    val identityGeneration: Long = 0L,
)

internal enum class HomeRequestPolicy { NORMAL, FORCE }

private class HomeRequestScopeChangedException :
    IllegalStateException("Home request identity changed before completion")

internal data class HomeGateEntryCounts(
    val inFlight: Int,
    val cached: Int,
)

internal class HomeSectionsRequestGate(
    private val freshnessWindow: Duration = 10.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class CachedResult(
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

    internal suspend fun entryCountsForTest(): HomeGateEntryCounts = mutex.withLock {
        HomeGateEntryCounts(inFlight = inFlight.size, cached = cached.size)
    }

    private suspend fun scopeIsCurrent(isScopeCurrent: suspend () -> Boolean): Boolean =
        try {
            isScopeCurrent()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }

    suspend fun execute(
        scopeKey: HomeRequestScope?,
        policy: HomeRequestPolicy,
        fetch: suspend () -> ApiResult<SectionsResponse>,
    ): ApiResult<SectionsResponse> = execute(
        scopeKey = scopeKey,
        policy = policy,
        isScopeCurrent = { true },
        fetch = fetch,
    )

    suspend fun execute(
        scopeKey: HomeRequestScope?,
        policy: HomeRequestPolicy,
        isScopeCurrent: suspend () -> Boolean,
        fetch: suspend () -> ApiResult<SectionsResponse>,
    ): ApiResult<SectionsResponse> {
        if (scopeKey == null) {
            val result = fetch()
            return if (scopeIsCurrent(isScopeCurrent)) {
                result
            } else {
                ApiResult.NetworkError(HomeRequestScopeChangedException())
            }
        }

        val selection = mutex.withLock {
            cached.entries.removeAll { (_, entry) ->
                (entry.completedAt + freshnessWindow).hasPassedNow()
            }
            cached.keys.removeAll { key ->
                key.serverId == scopeKey.serverId &&
                    key.profileId == scopeKey.profileId &&
                    key.identityGeneration != scopeKey.identityGeneration
            }

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
                    if (!scopeIsCurrent(isScopeCurrent)) {
                        return@async ApiResult.NetworkError(HomeRequestScopeChangedException())
                    }
                    if (result is ApiResult.Success) {
                        mutex.withLock {
                            cached[scopeKey] = CachedResult(result, timeSource.markNow())
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

        val result = when (selection) {
            is Selection.Ready -> selection.result
            is Selection.Pending -> selection.result.await()
        }
        return if (scopeIsCurrent(isScopeCurrent)) {
            result
        } else {
            ApiResult.NetworkError(HomeRequestScopeChangedException())
        }
    }
}
