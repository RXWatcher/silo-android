package org.siloserver.silo.util

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

suspend fun <T, R> Iterable<T>.mapConcurrentBounded(
    maxConcurrency: Int,
    transform: suspend (T) -> R,
): List<R> {
    require(maxConcurrency > 0) { "maxConcurrency must be greater than zero" }
    val permits = Semaphore(maxConcurrency)
    return coroutineScope {
        map { value ->
            async {
                permits.withPermit { transform(value) }
            }
        }.awaitAll()
    }
}
