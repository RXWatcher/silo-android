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

        return when (selection) {
            is Selection.Ready -> selection.result
            is Selection.Pending -> selection.result.await()
        }
    }
}
