package org.siloserver.silo.common.settings

import android.util.Log
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SettingsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ServerSettingsFlusher {
    fun enqueue(profileId: String, key: String, value: String)
    fun enqueueDelete(profileId: String, key: String)

    /**
     * Cancel any in-flight debounce, drain every pending op, and suspend
     * until each one has been ack'd (or errored). Mirrors the iOS
     * `PlayerSettings.flushPendingDeviceSettings()` semantics —
     * re-entrant calls coalesce, and the second caller waits for the
     * first to finish.
     *
     * Returning does *not* guarantee the server took every op: transport
     * failures stay queued and are retried in the background (bounded).
     * See [DefaultServerSettingsFlusher.pendingOpCount].
     */
    suspend fun flushNow()
}

private sealed class PendingOp {
    data class Set(val value: String) : PendingOp()
    object Delete : PendingOp()
}

class DefaultServerSettingsFlusher(
    private val settingsApi: SettingsApi,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 750,
    private val retryBaseDelayMs: Long = 2_000,
    private val retryMaxDelayMs: Long = 60_000,
    private val maxRetryRounds: Int = 5,
) : ServerSettingsFlusher {

    private val lock = Any()
    private val pending = mutableMapOf<Pair<String, String>, PendingOp>()
    private var flushJob: Job? = null
    private var retryJob: Job? = null
    private val flushMutex = Mutex()

    /** Ops written locally that the server has not accepted yet. 0 = in sync. */
    val pendingOpCount: Int
        get() = synchronized(lock) { pending.size }

    override fun enqueue(profileId: String, key: String, value: String) {
        scheduleDebounced(profileId, key, PendingOp.Set(value))
    }

    override fun enqueueDelete(profileId: String, key: String) {
        scheduleDebounced(profileId, key, PendingOp.Delete)
    }

    private fun scheduleDebounced(profileId: String, key: String, op: PendingOp) {
        synchronized(lock) {
            pending[profileId to key] = op
            flushJob?.cancel()
            flushJob = scope.launch {
                delay(debounceMs)
                drainAndFlush()
            }
        }
    }

    override suspend fun flushNow() {
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = null
        }
        drainAndFlush()
    }

    /**
     * Sends every queued op, dropping each one only once the server has
     * accepted it (or permanently rejected it). Whatever is left over stays
     * queued and is handed to [scheduleRetry], so a failed write is never
     * silently lost — and so callers of [flushNow] are not held for the whole
     * backoff sequence.
     */
    private suspend fun drainAndFlush() {
        flushMutex.withLock {
            while (true) {
                val snapshot: Map<Pair<String, String>, PendingOp> = synchronized(lock) {
                    if (pending.isEmpty()) return@withLock
                    pending.toMap()
                }
                var failed = false
                snapshot.forEach { (composite, op) ->
                    val (profileId, key) = composite
                    if (flushOne(profileId, key, op)) {
                        synchronized(lock) {
                            // Drop only what we actually sent: an enqueue that
                            // landed while the request was in flight must survive.
                            if (pending[composite] == op) pending.remove(composite)
                        }
                    } else {
                        failed = true
                    }
                }
                // Loop again to pick up anything enqueued mid-flight; on failure
                // stop here so the bounded retry job owns the backoff instead.
                if (failed) return@withLock
            }
        }
        scheduleRetry()
    }

    /**
     * Retries whatever [drainAndFlush] could not deliver, with exponential
     * backoff and a hard round cap so a down server is never hammered. Once
     * the cap is hit the ops stay queued but sit idle until the next enqueue
     * or [flushNow] gives them a fresh attempt.
     */
    private fun scheduleRetry() {
        synchronized(lock) {
            if (pending.isEmpty() || retryJob?.isActive == true) return
            retryJob = scope.launch {
                var delayMs = retryBaseDelayMs
                repeat(maxRetryRounds) {
                    delay(delayMs)
                    drainAndFlush()
                    if (synchronized(lock) { pending.isEmpty() }) return@launch
                    delayMs = (delayMs * 2).coerceAtMost(retryMaxDelayMs)
                }
                Log.e(
                    TAG,
                    "gave up after $maxRetryRounds retries; $pendingOpCount op(s) still queued",
                )
            }
        }
    }

    /** True when the op is done with — ack'd, or rejected in a way retrying cannot fix. */
    private suspend fun flushOne(profileId: String, key: String, op: PendingOp): Boolean {
        return try {
            val result = when (op) {
                is PendingOp.Set ->
                    settingsApi.setDeviceSetting(key, op.value, profileId = profileId)
                is PendingOp.Delete ->
                    settingsApi.deleteDeviceSetting(key)
            }
            when (result) {
                is ApiResult.Success -> true
                is ApiResult.Error -> {
                    val retryable = isRetryable(result.code)
                    Log.w(
                        TAG,
                        "$op profile=$profileId key=$key code=${result.code} " +
                            "retryable=$retryable: ${result.message}",
                    )
                    !retryable
                }
                is ApiResult.NetworkError -> {
                    Log.w(
                        TAG,
                        "$op profile=$profileId key=$key network error: ${result.exception}",
                    )
                    false
                }
            }
        } catch (c: CancellationException) {
            // Caller-side cancellation: leave the op queued so the next drain resends it.
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "$op profile=$profileId key=$key threw: $t")
            false
        }
    }

    /** 4xx other than auth/timeout/rate-limit will fail identically forever. */
    private fun isRetryable(code: Int): Boolean =
        code !in 400..499 || code == 401 || code == 408 || code == 429

    private companion object {
        const val TAG = "ServerSettingsFlusher"
    }
}
