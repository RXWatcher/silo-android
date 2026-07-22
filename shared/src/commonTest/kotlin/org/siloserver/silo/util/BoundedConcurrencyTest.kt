package org.siloserver.silo.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedConcurrencyTest {
    @Test
    fun `retains input order while limiting work in flight`() = runTest {
        val release = Channel<Unit>(Channel.UNLIMITED)
        val lock = Mutex()
        var inFlight = 0
        var maximumInFlight = 0
        var result: List<Int>? = null

        val job = launch {
            result = (1..8).mapConcurrentBounded(maxConcurrency = 3) { value ->
                lock.withLock {
                    inFlight += 1
                    maximumInFlight = maxOf(maximumInFlight, inFlight)
                }
                release.receive()
                lock.withLock { inFlight -= 1 }
                value * 10
            }
        }

        runCurrent()
        assertEquals(3, maximumInFlight)
        repeat(8) {
            release.send(Unit)
            runCurrent()
        }
        job.join()

        assertEquals(listOf(10, 20, 30, 40, 50, 60, 70, 80), result)
        assertEquals(3, maximumInFlight)
    }

    @Test
    fun `rejects non-positive concurrency limits`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            listOf(1).mapConcurrentBounded(maxConcurrency = 0) { it }
        }
    }
}
