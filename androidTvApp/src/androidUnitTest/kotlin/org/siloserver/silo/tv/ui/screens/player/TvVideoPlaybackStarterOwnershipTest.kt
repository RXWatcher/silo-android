package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class TvVideoPlaybackStarterOwnershipTest {
    private val starterSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvVideoPlaybackStarter.kt",
    ).readText()

    @Test
    fun `T62 lifecycle adoption cancellation stops unpublished Ready and propagates`() =
        runTest {
            val adoptionEntered = CompletableDeferred<Unit>()
            val stopped = mutableListOf<String>()
            val stopContextsActive = mutableListOf<Boolean>()
            var returnedNormally = false
            var propagated = false
            val result = async {
                try {
                    publishTvAllocatedSession(
                        sessionId = "allocated-session",
                        stopUnpublishedSession = { sessionId ->
                            stopped += sessionId
                            stopContextsActive += currentCoroutineContext().isActive
                        },
                        publish = {
                            adoptionEntered.complete(Unit)
                            awaitCancellation()
                        },
                    ).also { returnedNormally = true }
                } catch (error: CancellationException) {
                    propagated = true
                    throw error
                }
            }
            adoptionEntered.await()

            result.cancel()
            assertFailsWith<CancellationException> { result.await() }

            assertTrue(propagated)
            assertFalse(returnedNormally)
            assertEquals(listOf("allocated-session"), stopped)
            assertEquals(listOf(true), stopContextsActive)
        }

    @Test
    fun `adoption exception stops unpublished Ready before the starter maps failure`() =
        runTest {
            val stopped = mutableListOf<String>()

            val failure = assertFailsWith<IllegalStateException> {
                publishTvAllocatedSession(
                    sessionId = "exception-session",
                    stopUnpublishedSession = { stopped += it },
                    publish = { throw IllegalStateException("adoption failed") },
                )
            }

            assertEquals("adoption failed", failure.message)
            assertEquals(listOf("exception-session"), stopped)
        }

    @Test
    fun `successful lifecycle publication transfers ownership without cleanup`() =
        runTest {
            val stopped = mutableListOf<String>()

            val result = publishTvAllocatedSession(
                sessionId = "active-session",
                stopUnpublishedSession = { stopped += it },
                publish = { "published" },
            )

            assertEquals("published", result)
            assertEquals(emptyList(), stopped)
        }

    @Test
    fun `cleanup exception never replaces adoption cancellation`() = runTest {
        val adoptionEntered = CompletableDeferred<Unit>()
        val result = async {
            publishTvAllocatedSession(
                sessionId = "allocated-session",
                stopUnpublishedSession = {
                    throw IllegalStateException("cleanup failed")
                },
                publish = {
                    adoptionEntered.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        adoptionEntered.await()

        result.cancel(CancellationException("owner cancelled"))
        val failure = assertFailsWith<CancellationException> { result.await() }

        assertEquals("owner cancelled", failure.message)
    }

    @Test
    fun `cleanup executes exactly once for a failed publication`() = runTest {
        var cleanupCalls = 0

        assertFailsWith<IllegalArgumentException> {
            publishTvAllocatedSession(
                sessionId = "allocated-session",
                stopUnpublishedSession = { cleanupCalls += 1 },
                publish = { throw IllegalArgumentException("bad Ready") },
            )
        }
        runCurrent()

        assertEquals(1, cleanupCalls)
    }

    @Test
    fun `stale owner between allocation and lifecycle adoption stops without publication`() =
        runTest {
            val registry = TvPlayerLoadOwnerRegistry()
            val owner = registry.begin("movie", 11, "720p")
            val ownership = registry.withOwner(owner) {
                requireNotNull(currentTvPlayerLoadOwnership())
            }
            val stopped = mutableListOf<String>()
            var published = false
            registry.begin("movie", 22, "4k")

            assertFailsWith<TvPlayerLoadSupersededException> {
                publishTvAllocatedSessionIfOwned(
                    sessionId = "stale-session",
                    ownership = ownership,
                    stopUnpublishedSession = { stopped += it },
                    adoptIfCurrent = { isCurrent -> isCurrent() },
                    publish = {
                        published = true
                        "Ready"
                    },
                )
            }

            assertFalse(published)
            assertEquals(listOf("stale-session"), stopped)
        }

    @Test
    fun `exit invalidation after allocation stops without lifecycle publication`() = runTest {
        val registry = TvPlayerLoadOwnerRegistry()
        val owner = registry.begin("movie", 11, "720p")
        val ownership = registry.withOwner(owner) {
            requireNotNull(currentTvPlayerLoadOwnership())
        }
        val stopped = mutableListOf<String>()
        var published = false
        registry.invalidate()

        assertFailsWith<TvPlayerLoadSupersededException> {
            publishTvAllocatedSessionIfOwned(
                sessionId = "exit-session",
                ownership = ownership,
                stopUnpublishedSession = { stopped += it },
                adoptIfCurrent = { isCurrent -> isCurrent() },
                publish = {
                    published = true
                    "Ready"
                },
            )
        }

        assertFalse(published)
        assertEquals(listOf("exit-session"), stopped)
    }

    @Test
    fun `owner lost while lifecycle mutex is queued cannot publish`() = runTest {
        val registry = TvPlayerLoadOwnerRegistry()
        val owner = registry.begin("movie", 11, "720p")
        val ownership = registry.withOwner(owner) {
            requireNotNull(currentTvPlayerLoadOwnership())
        }
        val adoptionEntered = CompletableDeferred<Unit>()
        val releaseAdoption = CompletableDeferred<Unit>()
        val stopped = mutableListOf<String>()
        var published = false
        val result = async {
            publishTvAllocatedSessionIfOwned(
                sessionId = "queued-session",
                ownership = ownership,
                stopUnpublishedSession = { stopped += it },
                adoptIfCurrent = { isCurrent ->
                    adoptionEntered.complete(Unit)
                    releaseAdoption.await()
                    isCurrent()
                },
                publish = {
                    published = true
                    "Ready"
                },
            )
        }
        adoptionEntered.await()

        registry.begin("movie", 22, "4k")
        releaseAdoption.complete(Unit)

        assertFailsWith<TvPlayerLoadSupersededException> { result.await() }
        assertFalse(published)
        assertEquals(listOf("queued-session"), stopped)
    }

    @Test
    fun `owned lifecycle adoption cancellation cleans exactly once and propagates`() =
        runTest {
            val registry = TvPlayerLoadOwnerRegistry()
            val owner = registry.begin("movie", 11, "720p")
            val ownership = registry.withOwner(owner) {
                requireNotNull(currentTvPlayerLoadOwnership())
            }
            val adoptionEntered = CompletableDeferred<Unit>()
            val stopped = mutableListOf<String>()
            val stopContextsActive = mutableListOf<Boolean>()
            var published = false
            val result = async {
                publishTvAllocatedSessionIfOwned(
                    sessionId = "cancelled-session",
                    ownership = ownership,
                    stopUnpublishedSession = {
                        stopped += it
                        stopContextsActive += currentCoroutineContext().isActive
                    },
                    adoptIfCurrent = {
                        adoptionEntered.complete(Unit)
                        awaitCancellation()
                    },
                    publish = {
                        published = true
                        "Ready"
                    },
                )
            }
            adoptionEntered.await()

            result.cancel(CancellationException("cancel adoption"))
            val failure = assertFailsWith<CancellationException> { result.await() }

            assertEquals("cancel adoption", failure.message)
            assertFalse(published)
            assertEquals(listOf("cancelled-session"), stopped)
            assertEquals(listOf(true), stopContextsActive)
        }

    @Test
    fun `owned lifecycle adoption exception cleans exactly once and propagates`() = runTest {
        val registry = TvPlayerLoadOwnerRegistry()
        val owner = registry.begin("movie", 11, "720p")
        val ownership = registry.withOwner(owner) {
            requireNotNull(currentTvPlayerLoadOwnership())
        }
        val stopped = mutableListOf<String>()
        var published = false

        val failure = assertFailsWith<IllegalStateException> {
            publishTvAllocatedSessionIfOwned(
                sessionId = "failed-session",
                ownership = ownership,
                stopUnpublishedSession = { stopped += it },
                adoptIfCurrent = { throw IllegalStateException("adoption failed") },
                publish = {
                    published = true
                    "Ready"
                },
            )
        }

        assertEquals("adoption failed", failure.message)
        assertFalse(published)
        assertEquals(listOf("failed-session"), stopped)
    }

    @Test
    fun `successful owned lifecycle adoption transfers without cleanup`() = runTest {
        val registry = TvPlayerLoadOwnerRegistry()
        val owner = registry.begin("movie", 11, "720p")
        val ownership = registry.withOwner(owner) {
            requireNotNull(currentTvPlayerLoadOwnership())
        }
        val stopped = mutableListOf<String>()
        var adoptionChecks = 0

        val result = publishTvAllocatedSessionIfOwned(
            sessionId = "active-session",
            ownership = ownership,
            stopUnpublishedSession = { stopped += it },
            adoptIfCurrent = { isCurrent ->
                adoptionChecks += 1
                isCurrent()
            },
            publish = { "Ready" },
        )

        assertEquals("Ready", result)
        assertEquals(1, adoptionChecks)
        assertTrue(stopped.isEmpty())
    }

    @Test
    fun `starter delegates allocated Ready to the owner checked lifecycle helper`() {
        val startBody = starterSource.substringAfter("override suspend fun start(")
        val managerStart = startBody.indexOf("playbackSessionManager.startVideoSessionV3(")
        val pendingRollback = startBody.indexOf(
            "sessionLifecycle.rollbackCurrentPendingPublication",
        )
        assertTrue(pendingRollback >= 0)
        assertTrue(pendingRollback < managerStart)
        assertTrue(startBody.contains("rollbackUnpublishedVideoSession(sessionId)"))
        assertTrue(startBody.contains("publishTvAllocatedSessionIfOwned("))
        assertTrue(startBody.contains("sessionLifecycle.adoptActiveSessionIfCurrent("))
        assertTrue(startBody.contains("currentTvPlayerLoadOwnership()"))
        assertFalse(startBody.contains("sessionLifecycle.adoptActiveSession("))
        assertTrue(startBody.contains("catch (e: CancellationException)"))
        val cancellationCatch = startBody
            .substringAfter("catch (e: CancellationException)")
            .substringBefore("catch (e: Exception)")
        assertTrue(cancellationCatch.contains("throw e"))
        assertFalse(cancellationCatch.contains("failure("))
    }
}
