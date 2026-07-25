package org.siloserver.silo.common.player

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PLAYBACK_PLAN_V3_FEATURE
import org.siloserver.silo.model.playback.PlaybackDecisionOutcome
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackEffectiveRecipeV3
import org.siloserver.silo.model.playback.PlaybackEngineKind
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackStreamProtocol
import org.siloserver.silo.model.playback.PlaybackStreamV3
import org.siloserver.silo.model.playback.PlaybackSubtitleArtifactV3
import org.siloserver.silo.model.playback.PlaybackSubtitleDecisionV3
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.PlaybackTerminalV3
import org.siloserver.silo.model.playback.PlaybackTrackIdentityV3
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.model.playback.SubtitleFidelityPreference
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.PlaybackApi
import org.siloserver.silo.repository.PlaybackRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaybackSessionManagerStagedReplanTest {
    @Test
    fun `deferred confirmation registers predecessor orphan before releasing reset waiter`() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackSessionManager.kt",
        ).readText()
        val confirmation = source
            .substringAfter("suspend fun confirmVideoSessionPublication(")
            .substringBefore("suspend fun rollbackUnpublishedVideoSession(")

        val orphanRegistration = confirmation.indexOf("orphanedSessionIds +=")
        val waiterRelease = confirmation.indexOf("pending.settled.complete(Unit)")
        val registeredCleanup = confirmation.indexOf(
            "scheduleRegisteredCommittedSessionCleanup(",
        )

        assertTrue(orphanRegistration >= 0)
        assertTrue(orphanRegistration < waiterRelease)
        assertTrue(registeredCleanup > waiterRelease)
    }

    @Test
    fun `deferred confirm cleanup concurrent with orphan drain loses no ledger entry`() =
        runTest {
            val firstCleanupEntered = CompletableDeferred<Unit>()
            val releaseFirstCleanup = CompletableDeferred<Unit>()
            val oldAttempts = AtomicInteger()
            val harness = Harness(
                replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
                stopBehavior = { sessionId ->
                    if (sessionId == "s1" && oldAttempts.incrementAndGet() == 1) {
                        firstCleanupEntered.complete(Unit)
                        releaseFirstCleanup.await()
                    }
                },
            )
            harness.start()
            val staged = harness.stageSidecar()
            harness.manager.commitStagedVideoReplan(
                staged = staged,
                deferPublication = true,
            )

            assertTrue(harness.manager.confirmVideoSessionPublication("s2"))
            firstCleanupEntered.await()
            assertEquals(setOf("s1"), harness.manager.orphanedSessionIdsForTest())

            // stopSession drains the same orphan ledger while confirmation's
            // asynchronous cleanup still owns its first network attempt.
            harness.manager.stopSession("s2")
            releaseFirstCleanup.complete(Unit)
            withTimeout(5_000) {
                while (harness.manager.orphanedSessionIdsForTest().isNotEmpty()) {
                    yield()
                }
            }

            assertTrue(oldAttempts.get() >= 2)
            assertEquals(emptySet(), harness.manager.orphanedSessionIdsForTest())
            assertTrue("s1" in harness.stoppedSessions)
            assertTrue("s2" in harness.stoppedSessions)
        }

    @Test
    fun `staging replacement does not swap attempt or stop old session`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start()

        val staged = harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        val candidate = assertIs<ApiResult.Success<StagedVideoReplan>>(staged).data
        assertEquals("s2", candidate.candidateSessionId)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun `a third pick after a rollback and a commit still addresses the server's plan`() = runTest {
        // The harness previously returned a CONSTANT planId per replan, which is
        // why an incomplete cursor fix looked covered: a committed replan copies
        // the attempt from the PRE-request snapshot, so it silently reinstated
        // the plan the rollback had retired. That only shows up on the pick
        // AFTER a commit — the second pick worked, the third 409'd.
        val harness = Harness(
            replanResponse = { index, _ ->
                response(sidecarPlan(sessionId = "s1", planId = "plan-server-v${index + 1}"))
            },
        )
        harness.start()

        // The harness's plans always select subtitle 4; this test is about the
        // plan cursor, not track selection, so every pick uses that index.
        suspend fun pick() = assertIs<ApiResult.Success<StagedVideoReplan>>(
            harness.manager.stageActiveVideoSessionReplan(
                classification = "subtitle_track_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            ),
        ).data

        // Pick 1: commit, then roll back. The server keeps plan-server-v1.
        val first = pick()
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(staged = first, deferPublication = true),
        )
        assertTrue(harness.manager.rollbackUnpublishedVideoSession("s1"))

        // Pick 2: commit and keep it. The server moves to plan-server-v2.
        val second = pick()
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(staged = second),
        )

        // Pick 3 must address plan-server-v2 — what the server actually holds.
        pick()

        assertEquals(
            "plan-server-v2",
            harness.replanBodies.last()["failed_plan_id"]?.toString()?.trim('"'),
            "a committed replan reinstated a plan the server had already retired",
        )
    }

    @Test
    fun `rolling back an in-place replan keeps addressing the server's current plan`() = runTest {
        // POST /replan is a commit. For an in-place replan the server keeps the
        // session id but moves to a new plan, and nothing can un-commit it.
        // Reverting the client's plan identity on rollback retires a plan the
        // server has already superseded, after which every later replan is
        // rejected 409 "The failed plan is no longer current" for the rest of
        // the session — which is exactly what stopped a second subtitle pick
        // from ever working.
        val harness = Harness(
            replanResponse = { _, _ ->
                response(sidecarPlan(sessionId = "s1", planId = "plan-server-v2"))
            },
        )
        harness.start()

        val staged = assertIs<ApiResult.Success<StagedVideoReplan>>(
            harness.manager.stageActiveVideoSessionReplan(
                classification = "subtitle_track_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            ),
        ).data
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(staged = staged, deferPublication = true),
        )
        assertTrue(harness.manager.rollbackUnpublishedVideoSession("s1"))

        // A further replan must address plan-server-v2, not the retired plan.
        harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 5,
        )
        val lastFailedPlanId = harness.replanBodies.last()["failed_plan_id"]?.toString()?.trim('"')
        assertEquals(
            "plan-server-v2",
            lastFailedPlanId,
            "rollback retired the plan the server actually holds",
        )
    }

    @Test
    fun `rolling back an in-place replan never stops the session still playing`() = runTest {
        // Production servers replan IN PLACE, returning the same session id they
        // were given. Rolling back such a publication reverts ownership to that
        // very session, so stopping the "replacement" deletes the session the
        // user is watching — after which every later replan 404s.
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s1")) },
        )
        harness.start()

        val staged = assertIs<ApiResult.Success<StagedVideoReplan>>(
            harness.manager.stageActiveVideoSessionReplan(
                classification = "subtitle_track_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            ),
        ).data
        assertEquals(staged.baseSessionId, staged.candidateSessionId)

        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(staged = staged, deferPublication = true),
        )

        assertTrue(harness.manager.rollbackUnpublishedVideoSession("s1"))

        assertEquals(emptyList(), harness.stoppedSessions)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
    }

    @Test
    fun `staged replacement exposes manager derived output route generation`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start()

        val staged = assertIs<ApiResult.Success<StagedVideoReplan>>(
            harness.manager.stageActiveVideoSessionReplan(
                classification = "output_route_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
                clientPlaybackContext = ClientPlaybackContext(
                    formFactor = "tv",
                    appVersion = "test",
                    output = PlaybackOutputContext(outputRouteGeneration = 11),
                ),
            ),
        ).data

        assertEquals(11, staged.outputRouteGeneration)
    }

    @Test
    fun `commit swaps once then stops old session`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start()
        val staged = assertIs<ApiResult.Success<StagedVideoReplan>>(
            harness.manager.stageActiveVideoSessionReplan(
                classification = "subtitle_track_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            ),
        ).data

        val committed = harness.manager.commitStagedVideoReplan(staged)

        assertEquals(
            "s2",
            assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(committed).data.session.sessionId,
        )
        assertEquals("s2", harness.manager.activeSessionIdForTest())
        harness.awaitStopped("s1")
        assertEquals(listOf("s1"), harness.stoppedSessions)

        val consumed = harness.manager.commitStagedVideoReplan(staged)
        assertEquals(409, assertIs<ApiResult.Error>(consumed).code)
        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(listOf("s1"), harness.stoppedSessions)
    }

    @Test
    fun `deferred staged commit rollback restores base and unblocks replan from base`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                response(sidecarPlan(sessionId = if (index == 0) "s2" else "s3"))
            },
        )
        harness.start()
        val replacement = harness.stageSidecar()

        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(
                staged = replacement,
                deferPublication = true,
            ),
        )
        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)

        val reverseMutation = async {
            harness.manager.stageActiveVideoSessionReplan(
                classification = "output_route_changed",
                positionSeconds = 43.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            )
        }
        yield()
        assertFalse(reverseMutation.isCompleted)
        assertEquals(listOf("s1"), harness.replanBaseSessions)

        harness.manager.rollbackUnpublishedVideoSession("s2")
        val stagedFromBase =
            assertIs<ApiResult.Success<StagedVideoReplan>>(reverseMutation.await()).data

        assertEquals("s3", stagedFromBase.candidateSessionId)
        assertEquals(listOf("s1", "s1"), harness.replanBaseSessions)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s2" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())
    }

    @Test
    fun `a start still completes when a deferred publication is never settled`() = runTest {
        // The manager's pending publication is created before the caller installs
        // the lifecycle-side counterpart, so a cancellation between the two leaves
        // this one with no owner. beginContentReset used to await it forever,
        // wedging every future start behind a spinner that nothing could clear.
        val harness = Harness(
            startResponses = listOf(response(basePlan()), response(basePlan(sessionId = "s9"))),
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start()
        val replacement = harness.stageSidecar()
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(
                staged = replacement,
                deferPublication = true,
            ),
        )
        // Deliberately no confirm and no rollback: the owner is gone.

        harness.start(fileId = 43)

        assertEquals("s9", harness.manager.activeSessionIdForTest())
        assertTrue(
            "s2" in harness.stoppedSessions,
            "the abandoned publication should be rolled back, not left running",
        )
    }

    @Test
    fun `rollbackCurrentPendingVideoPublication clears a publication the caller cannot name`() = runTest {
        val harness = Harness(replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) })
        harness.start()
        val replacement = harness.stageSidecar()
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(
                staged = replacement,
                deferPublication = true,
            ),
        )

        assertTrue(harness.manager.rollbackCurrentPendingVideoPublication())
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        // Idempotent: nothing pending is still success.
        assertTrue(harness.manager.rollbackCurrentPendingVideoPublication())
    }

    @Test
    fun `deferred staged commit confirmation retains replacement and stops base once`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start()
        val replacement = harness.stageSidecar()

        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(
                staged = replacement,
                deferPublication = true,
            ),
        )
        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)

        harness.manager.confirmVideoSessionPublication("s2")
        harness.manager.confirmVideoSessionPublication("s2")

        harness.awaitStopped("s1")
        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s1" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())
    }

    @Test
    fun `commit returns after active swap without waiting for cancellable old session cleanup`() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
            stopBehavior = {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
            },
        )
        harness.start()
        val staged = harness.stageSidecar()

        val commit = async { harness.manager.commitStagedVideoReplan(staged) }
        cleanupEntered.await()

        assertTrue(
            commit.isCompleted,
            "Once the active attempt swaps, old-session cleanup must not keep commit cancellable.",
        )
        assertEquals(
            "s2",
            assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(commit.await())
                .data.session.sessionId,
        )
        assertEquals("s2", harness.manager.activeSessionIdForTest())

        releaseCleanup.cancel(CancellationException("cleanup cancelled"))
    }

    @Test
    fun `throwing old session cleanup cannot escape after active swap`() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
            stopBehavior = {
                cleanupEntered.complete(Unit)
                throw AssertionError("old-session cleanup exploded")
            },
        )
        harness.start()
        val staged = harness.stageSidecar()

        val committed = harness.manager.commitStagedVideoReplan(staged)

        assertEquals(
            "s2",
            assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(committed)
                .data.session.sessionId,
        )
        assertEquals("s2", harness.manager.activeSessionIdForTest())
        cleanupEntered.await()
    }

    @Test
    fun `failed bounded cleanup remains orphaned until later stop drains it`() = runTest {
        val oldAttempts = AtomicInteger()
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
            stopBehavior = { sessionId ->
                if (sessionId == "s1" && oldAttempts.incrementAndGet() <= 2) {
                    throw IllegalStateException("transient delete failure")
                }
            },
        )
        harness.start()
        val staged = harness.stageSidecar()

        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(staged),
        )
        harness.awaitStopAttempts("s1", count = 2)

        harness.manager.stopSession("s2")

        assertEquals(3, oldAttempts.get())
        assertTrue("s1" in harness.stoppedSessions)
        assertTrue("s2" in harness.stoppedSessions)
    }

    @Test
    fun `cancelled cleanup is eventually drained by content reset`() = runTest {
        val oldAttempts = AtomicInteger()
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
            stopBehavior = { sessionId ->
                if (sessionId == "s1" && oldAttempts.incrementAndGet() == 1) {
                    throw CancellationException("cleanup cancelled")
                }
            },
        )
        harness.start(fileId = 42)
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(harness.stageSidecar()),
        )
        harness.awaitStopAttempts("s1", count = 1)

        harness.start(fileId = 84)

        assertTrue(oldAttempts.get() >= 2)
        assertTrue("s1" in harness.stoppedSessions)
        assertEquals("s3", harness.manager.activeSessionIdForTest())
    }

    @Test
    fun `candidate stop exception cannot skip requested stop and is retained for drain`() = runTest {
        val candidateAttempts = AtomicInteger()
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
            stopBehavior = { sessionId ->
                if (sessionId == "s2" && candidateAttempts.incrementAndGet() == 1) {
                    throw IllegalStateException("candidate stop failed")
                }
            },
        )
        harness.start()
        harness.stageSidecar()

        assertIs<ApiResult.Success<Unit>>(harness.manager.stopSession("s1"))

        assertTrue("s1" in harness.stoppedSessions)
        assertTrue("s2" in harness.stoppedSessions)
        assertEquals(2, candidateAttempts.get())
    }

    @Test
    fun `requested stop failure still drains prior orphan`() = runTest {
        val oldAttempts = AtomicInteger()
        val requestedAttempts = AtomicInteger()
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
            stopBehavior = { sessionId ->
                when {
                    sessionId == "s1" && oldAttempts.incrementAndGet() <= 2 ->
                        throw IllegalStateException("old cleanup failed")
                    sessionId == "s2" && requestedAttempts.incrementAndGet() == 1 ->
                        throw CancellationException("requested stop cancelled locally")
                }
            },
        )
        harness.start()
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(harness.stageSidecar()),
        )
        harness.awaitStopAttempts("s1", count = 2)

        assertIs<ApiResult.NetworkError>(harness.manager.stopSession("s2"))

        assertEquals(3, oldAttempts.get())
        assertTrue("s1" in harness.stoppedSessions)
        assertTrue("s2" in harness.stoppedSessions)
    }

    @Test
    fun `caller cancellation waits for contained cleanup then rethrows cancellation`() = runTest {
        val candidateEntered = CompletableDeferred<Unit>()
        val releaseCandidate = CompletableDeferred<Unit>()
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
            stopBehavior = { sessionId ->
                if (sessionId == "s2") {
                    candidateEntered.complete(Unit)
                    releaseCandidate.await()
                }
            },
        )
        harness.start()
        harness.stageSidecar()

        val stopJob = launch {
            harness.manager.stopSession("s1")
        }
        candidateEntered.await()
        stopJob.cancel(CancellationException("caller stopped"))
        releaseCandidate.complete(Unit)
        stopJob.join()

        assertTrue(stopJob.isCancelled)
        assertTrue("s1" in harness.stoppedSessions)
        assertTrue("s2" in harness.stoppedSessions)
    }

    @Test
    fun `discard stops only candidate and consumes handle`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start()
        val staged = assertIs<ApiResult.Success<StagedVideoReplan>>(
            harness.manager.stageActiveVideoSessionReplan(
                classification = "subtitle_track_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            ),
        ).data

        harness.manager.discardStagedVideoReplan(staged)
        harness.manager.discardStagedVideoReplan(staged)

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(listOf("s2"), harness.stoppedSessions)
        assertEquals(
            409,
            assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(staged)).code,
        )
        assertEquals(listOf("s2"), harness.stoppedSessions)
    }

    @Test
    fun `suspended discard cleanup does not hold staged ownership mutex`() = runTest {
        val firstStopStarted = CompletableDeferred<Unit>()
        val secondStopStarted = CompletableDeferred<Unit>()
        val releaseFirstStop = CompletableDeferred<Unit>()
        val harness = Harness(
            replanResponse = { index, _ ->
                response(sidecarPlan(sessionId = if (index == 0) "s2" else "s3"))
            },
            stopBehavior = { sessionId ->
                when (sessionId) {
                    "s2" -> {
                        firstStopStarted.complete(Unit)
                        releaseFirstStop.await()
                    }
                    "s3" -> secondStopStarted.complete(Unit)
                }
            },
        )
        harness.start()
        val first = harness.stageSidecar()
        val second = harness.stageSidecar()

        val firstDiscard = launch { harness.manager.discardStagedVideoReplan(first) }
        firstStopStarted.await()
        val secondDiscard = launch { harness.manager.discardStagedVideoReplan(second) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { secondStopStarted.await() }
            }
            assertFalse(firstDiscard.isCompleted)
            assertTrue("s3" in harness.stopAttempts)
        } finally {
            releaseFirstStop.complete(Unit)
        }
        firstDiscard.join()
        secondDiscard.join()

        assertEquals(setOf("s2", "s3"), harness.stoppedSessions.toSet())
    }

    @Test
    fun `stale handle cannot replace newer committed candidate`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                response(sidecarPlan(sessionId = if (index == 0) "s2" else "s3"))
            },
        )
        harness.start()
        val first = harness.stageSidecar()
        val second = harness.stageSidecar()

        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(second),
        )
        val stale = harness.manager.commitStagedVideoReplan(first)

        harness.awaitStopped("s1")
        harness.awaitStopped("s2")
        assertEquals(409, assertIs<ApiResult.Error>(stale).code)
        assertEquals("s3", harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1, "s2" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `content reset invalidates staged handle`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start(fileId = 42)
        val staged = harness.stageSidecar()

        harness.start(fileId = 84)
        val stale = harness.manager.commitStagedVideoReplan(staged)

        assertEquals(409, assertIs<ApiResult.Error>(stale).code)
        assertEquals("s3", harness.manager.activeSessionIdForTest())
        assertEquals(listOf("s2"), harness.stoppedSessions)
    }

    @Test
    fun `burn in candidate commits without sidecar artifact`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ ->
                response(
                    sidecarPlan(sessionId = "s2").copy(
                        subtitle = PlaybackSubtitleDecisionV3(
                            mode = PlaybackSubtitleModeV3.BURN_IN,
                            trackId = subtitleTrackId(fileId = 42, index = 4),
                        ),
                    ),
                )
            },
        )
        harness.start()

        val staged = harness.stageSidecar()
        val committed = harness.manager.commitStagedVideoReplan(staged)

        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(committed)
        assertEquals("s2", harness.manager.activeSessionIdForTest())
        harness.awaitStopped("s1")
        assertEquals(listOf("s1"), harness.stoppedSessions)
    }

    @Test
    fun `sidecar candidate without artifact is rejected`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ ->
                response(
                    sidecarPlan(sessionId = "s2").copy(
                        subtitle = PlaybackSubtitleDecisionV3(
                            mode = PlaybackSubtitleModeV3.CONVERT,
                            trackId = subtitleTrackId(fileId = 42, index = 4),
                            artifact = null,
                        ),
                    ),
                )
            },
        )
        harness.start()

        val staged = harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        assertIs<ApiResult.Error>(staged)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(listOf("s2"), harness.stoppedSessions)
    }

    @Test
    fun `sidecar candidate for another server index is rejected`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ ->
                response(
                    sidecarPlan(sessionId = "s2").copy(
                        selectedTracks = SelectedPlaybackTracksV3(
                            audio = audioTrack(fileId = 42),
                            subtitle = PlaybackTrackIdentityV3(
                                id = subtitleTrackId(fileId = 42, index = 5),
                                index = 5,
                            ),
                        ),
                        subtitle = PlaybackSubtitleDecisionV3(
                            mode = PlaybackSubtitleModeV3.RENDER,
                            trackId = subtitleTrackId(fileId = 42, index = 5),
                            artifact = sidecarArtifact(sessionId = "s2", index = 5),
                        ),
                    ),
                )
            },
        )
        harness.start()

        val staged = harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        assertIs<ApiResult.Error>(staged)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(listOf("s2"), harness.stoppedSessions)
    }

    @Test
    fun `immediate replan wrapper stages and commits replacement`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start()

        val replanned = harness.manager.replanActiveVideoSession(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        assertEquals(
            "s2",
            assertIs<VideoSessionStartV3.Ready>(
                assertIs<ApiResult.Success<VideoSessionStartV3>>(replanned).data,
            ).session.sessionId,
        )
        assertEquals("s2", harness.manager.activeSessionIdForTest())
        harness.awaitStopped("s1")
        assertEquals(listOf("s1"), harness.stoppedSessions)
    }

    @Test
    fun `content start rejects stage registration until replacement is installed`() = runTest {
        val replacementEntered = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        val starts = listOf(
            response(basePlan(sessionId = "s1", fileId = 42)),
            response(basePlan(sessionId = "s3", fileId = 84)),
        )
        val harness = Harness(
            startResponses = starts,
            startResponseOverride = { index ->
                if (index == 1) {
                    replacementEntered.complete(Unit)
                    releaseReplacement.await()
                }
                starts[index]
            },
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start(fileId = 42)

        val replacement = async { harness.start(fileId = 84) }
        replacementEntered.await()
        val duringReset = harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )
        releaseReplacement.complete(Unit)
        replacement.await()

        assertEquals("content_reset_in_progress", assertIs<ApiResult.Error>(duringReset).error)
        assertEquals(emptyList(), harness.replanBodies)
        assertEquals("s3", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun `unpublished replacement rollback restores predecessor and stops replacement once`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 84, deferPublication = true)

        harness.manager.rollbackUnpublishedVideoSession("s3")
        harness.manager.rollbackUnpublishedVideoSession("s3")

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        harness.awaitStopped("s3")
        assertEquals(mapOf("s3" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())
    }

    @Test
    fun `default terminal fresh start clears prior active attempt`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                terminalResponse(
                    sessionId = "s3",
                    reason = "adaptation_unavailable",
                    message = "No compatible route.",
                ),
            ),
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start(fileId = 42)

        harness.start(fileId = 84)

        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s3" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())
    }

    @Test
    fun `deferred terminal fresh start preserves prior active attempt`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                terminalResponse(
                    sessionId = "s3",
                    reason = "adaptation_unavailable",
                    message = "No compatible route.",
                ),
            ),
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start(fileId = 42)

        harness.start(fileId = 84, deferPublication = true)

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s3" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())
        assertFalse(harness.manager.rollbackUnpublishedVideoSession("s3"))
    }

    @Test
    fun `deferred legacy fresh start terminal replan restores prior active attempt`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(
                    basePlan(sessionId = "s3", fileId = 84).copy(
                        engine = PlaybackEngineKind.MPV_DIRECT,
                    ),
                ),
            ),
            replanResponse = { _, _ ->
                terminalResponse(
                    sessionId = "s4",
                    reason = "adaptation_unavailable",
                    message = "No compatible route.",
                )
            },
        )
        harness.start(fileId = 42)

        harness.start(fileId = 84, deferPublication = true)

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s3" to 1, "s4" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `confirming replacement retains it and stops predecessor once`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 84, deferPublication = true)

        harness.manager.confirmVideoSessionPublication("s3")
        harness.manager.confirmVideoSessionPublication("s3")

        assertEquals("s3", harness.manager.activeSessionIdForTest())
        harness.awaitStopped("s1")
        assertEquals(mapOf("s1" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())
    }

    @Test
    fun `reverse mutation waits for unpublished replacement rollback then stages from predecessor`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 84, deferPublication = true)

        val reverseMutation = async {
            harness.manager.stageActiveVideoSessionReplan(
                classification = "output_route_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            )
        }
        yield()

        assertFalse(reverseMutation.isCompleted)
        assertEquals(emptyList(), harness.replanBaseSessions)

        harness.manager.rollbackUnpublishedVideoSession("s3")
        val staged = assertIs<ApiResult.Success<StagedVideoReplan>>(reverseMutation.await()).data

        assertEquals("s2", staged.candidateSessionId)
        assertEquals(listOf("s1"), harness.replanBaseSessions)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
    }

    @Test
    fun `new content start waits for unresolved replacement settlement and preserves predecessor`() =
        runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
                response(basePlan(sessionId = "s4", fileId = 126)),
            ),
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 84, deferPublication = true)
        val nextStart = async {
            harness.start(fileId = 126, deferPublication = true)
        }
        yield()

        assertFalse(nextStart.isCompleted)
        assertEquals("s3", harness.manager.activeSessionIdForTest())

        assertTrue(harness.manager.rollbackUnpublishedVideoSession("s3"))
        nextStart.await()

        harness.awaitStopped("s3")
        assertEquals("s4", harness.manager.activeSessionIdForTest())

        harness.manager.rollbackUnpublishedVideoSession("s4")
        harness.awaitStopped("s4")

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s3" to 1, "s4" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `stopping unpublished replacement is an idempotent rollback to predecessor`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 84, deferPublication = true)

        harness.manager.stopSession("s3")
        harness.manager.rollbackUnpublishedVideoSession("s3")

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s3" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())
    }

    @Test
    fun `stopping predecessor clears unresolved replacement and stops both once`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 84, deferPublication = true)

        harness.manager.stopSession("s1")

        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
        assertFalse(harness.manager.confirmVideoSessionPublication("s3"))
        assertFalse(harness.manager.rollbackUnpublishedVideoSession("s3"))
        assertEquals(
            mapOf("s1" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `stop session invalidates and stops every distinct staged candidate`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                response(sidecarPlan(sessionId = if (index == 0) "s2" else "s3"))
            },
        )
        harness.start()
        val first = harness.stageSidecar()
        val second = harness.stageSidecar()

        harness.manager.stopSession("s1")

        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1, "s2" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
        assertEquals(409, assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(first)).code)
        assertEquals(409, assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(second)).code)
        assertEquals(
            mapOf("s1" to 1, "s2" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `stopping active replacement drains stale older base handle`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                response(sidecarPlan(sessionId = if (index == 0) "s2" else "s3"))
            },
        )
        harness.start()
        val stale = harness.stageSidecar()
        val replacement = harness.stageSidecar()
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(replacement),
        )
        harness.awaitStopped("s1")

        harness.manager.stopSession("s3")

        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1, "s2" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
        assertEquals(
            409,
            assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(stale)).code,
        )
        assertEquals(
            mapOf("s1" to 1, "s2" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `delayed stop for stale session leaves active staged transaction untouched`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                response(sidecarPlan(sessionId = if (index == 0) "s2" else "s3"))
            },
        )
        harness.start()
        val replacement = harness.stageSidecar()
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(replacement),
        )
        harness.awaitStopped("s1")
        val stagedFromS2 = harness.stageSidecar()

        harness.manager.stopSession("s1")

        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s1" to 2), harness.stoppedSessions.groupingBy { it }.eachCount())

        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(stagedFromS2),
        )
        harness.awaitStopped("s2")
        assertEquals("s3", harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 2, "s2" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `stale stop drains only matching base owner of shared candidate`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                response(
                    sidecarPlan(
                        sessionId = when (index) {
                            1 -> "s2"
                            else -> "s3"
                        },
                    ),
                )
            },
        )
        harness.start()
        harness.stageSidecar()
        val replacement = harness.stageSidecar()
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(replacement),
        )
        harness.awaitStopped("s1")
        val stagedFromS2 = harness.stageSidecar()

        harness.manager.stopSession("s1")

        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s1" to 2), harness.stoppedSessions.groupingBy { it }.eachCount())

        harness.manager.discardStagedVideoReplan(stagedFromS2)

        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 2, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `concurrent immediate replans serialize through both stage and commit`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val harness = Harness(
            replanResponse = { index, _ ->
                if (index == 0) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
                response(sidecarPlan(sessionId = if (index == 0) "s2" else "s3"))
            },
        )
        harness.start()

        val first = async {
            harness.manager.replanActiveVideoSession(
                classification = "subtitle_track_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            )
        }
        firstEntered.await()
        val second = async {
            harness.manager.replanActiveVideoSession(
                classification = "subtitle_track_changed",
                positionSeconds = 43.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            )
        }
        repeat(3) { yield() }
        releaseFirst.complete(Unit)

        assertIs<ApiResult.Success<VideoSessionStartV3>>(first.await())
        assertIs<ApiResult.Success<VideoSessionStartV3>>(second.await())
        harness.awaitStopped("s1")
        harness.awaitStopped("s2")
        assertEquals("s3", harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1, "s2" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `immediate terminal response preserves typed outcome and teardown`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                if (index == 0) {
                    response(sidecarPlan(sessionId = "s2"))
                } else {
                    terminalResponse(
                        sessionId = "s3",
                        reason = "adaptation_unavailable",
                        message = "No compatible route.",
                    )
                }
            },
        )
        harness.start()
        val staged = harness.stageSidecar()

        val result = harness.manager.replanActiveVideoSession(
            classification = "player_failure",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = null,
        )

        val terminal = assertIs<VideoSessionStartV3.Terminal>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(result).data,
        )
        assertEquals("adaptation_unavailable", terminal.reason)
        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )

        harness.manager.stopSession("s1")

        assertEquals(
            mapOf("s1" to 2, "s2" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
        assertEquals(
            409,
            assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(staged)).code,
        )
        assertEquals(
            mapOf("s1" to 2, "s2" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `staged terminal response rejects candidate but keeps active attempt`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ ->
                terminalResponse(
                    sessionId = "s2",
                    reason = "adaptation_unavailable",
                    message = "No compatible route.",
                )
            },
        )
        harness.start()

        val result = harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        assertIs<ApiResult.Error>(result)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(listOf("s2"), harness.stoppedSessions)
    }

    @Test
    fun `immediate incompatible response preserves server upgrade outcome`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                val response = response(sidecarPlan(sessionId = if (index == 0) "s2" else "s3"))
                if (index == 0) response else response.copy(protocolVersion = 2)
            },
        )
        harness.start()
        val staged = harness.stageSidecar()

        val result = harness.manager.replanActiveVideoSession(
            classification = "player_failure",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = null,
        )

        assertIs<VideoSessionStartV3.ServerUpgradeRequired>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(result).data,
        )
        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(listOf("s3"), harness.stoppedSessions)

        harness.manager.stopSession("s1")

        assertEquals(
            mapOf("s1" to 1, "s2" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
        assertEquals(
            409,
            assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(staged)).code,
        )
        assertEquals(
            mapOf("s1" to 1, "s2" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `immediate legacy engine response preserves terminal outcome and cleanup`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                if (index == 0) {
                    response(sidecarPlan(sessionId = "s2"))
                } else {
                    response(
                        sidecarPlan(sessionId = "s3").copy(
                            engine = PlaybackEngineKind.MPV_DIRECT,
                        ),
                    )
                }
            },
        )
        harness.start()
        val staged = harness.stageSidecar()

        val result = harness.manager.replanActiveVideoSession(
            classification = "player_failure",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        val terminal = assertIs<VideoSessionStartV3.Terminal>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(result).data,
        )
        assertEquals("unsupported_legacy_engine", terminal.reason)
        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )

        harness.manager.stopSession("s1")

        assertEquals(
            mapOf("s1" to 2, "s2" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
        assertEquals(
            409,
            assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(staged)).code,
        )
        assertEquals(
            mapOf("s1" to 2, "s2" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `shared candidate session remains alive until last staged owner discards`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(sidecarPlan(sessionId = "s2")) },
        )
        harness.start()
        val first = harness.stageSidecar()
        val second = harness.stageSidecar()

        harness.manager.discardStagedVideoReplan(first)
        assertEquals(emptyList(), harness.stoppedSessions)

        harness.manager.discardStagedVideoReplan(second)
        assertEquals(listOf("s2"), harness.stoppedSessions)
    }

    private class Harness(
        startResponses: List<PlaybackDecisionResponseV3> = listOf(response(basePlan())),
        private val startResponseOverride: (suspend (Int) -> PlaybackDecisionResponseV3)? = null,
        private val replanResponse: suspend (Int, JsonObject) -> PlaybackDecisionResponseV3,
        private val stopBehavior: suspend (String) -> Unit = {},
    ) {
        val stoppedSessions: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val stopAttempts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val replanBodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        val replanBaseSessions: MutableList<String> =
            Collections.synchronizedList(mutableListOf())
        private val stoppedEvents = Channel<String>(Channel.UNLIMITED)
        private val stopAttemptEvents = Channel<String>(Channel.UNLIMITED)
        private val startIndex = AtomicInteger()
        private val replanIndex = AtomicInteger()
        private val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val response = when {
                    path == "/api/v1/playback/start" -> {
                        val index = startIndex.getAndIncrement()
                        startResponseOverride?.invoke(index) ?: startResponses[index]
                    }
                    path.endsWith("/replan") -> {
                        replanBaseSessions += path
                            .substringBeforeLast("/replan")
                            .substringAfterLast('/')
                        val body = SiloJson.parseToJsonElement(
                            request.body.toByteArray().decodeToString(),
                        ).jsonObject
                        replanBodies += body
                        replanResponse(replanIndex.getAndIncrement(), body)
                    }
                    request.method == HttpMethod.Delete && path.startsWith("/api/v1/playback/") -> {
                        val sessionId = path.substringAfterLast('/')
                        stopAttempts += sessionId
                        stopAttemptEvents.send(sessionId)
                        stopBehavior(sessionId)
                        stoppedSessions += sessionId
                        stoppedEvents.send(sessionId)
                        null
                    }
                    else -> null
                }
                respond(
                    content = response?.let(SiloJson::encodeToString) ?: "{}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val manager = PlaybackSessionManager(
            playbackRepository = PlaybackRepository(PlaybackApi(client)),
            tokenManager = StagedReplanNoOpTokenManager,
        )

        suspend fun start(
            fileId: Int = 42,
            deferPublication: Boolean = false,
        ) {
            assertIs<ApiResult.Success<VideoSessionStartV3>>(
                manager.startVideoSessionV3(
                    fileId = fileId,
                    profileId = "profile-1",
                    capabilities = ClientCodecCapabilities(
                        codecsVideo = listOf("hevc"),
                        codecsAudio = listOf("eac3"),
                        containers = listOf("mkv"),
                    ),
                    clientPlaybackContext = ClientPlaybackContext(
                        formFactor = "tv",
                        appVersion = "test",
                        output = PlaybackOutputContext(outputRouteGeneration = 7),
                    ),
                    audioTrackIndex = 0,
                    subtitleTrackIndex = null,
                    qualityPreference = "original",
                    startPosition = 0.0,
                    subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                    deferPublication = deferPublication,
                ),
            )
        }

        suspend fun stageSidecar(): StagedVideoReplan = assertIs<ApiResult.Success<StagedVideoReplan>>(
            manager.stageActiveVideoSessionReplan(
                classification = "subtitle_track_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            ),
        ).data

        suspend fun awaitStopped(sessionId: String) {
            if (sessionId in stoppedSessions) return
            while (stoppedEvents.receive() != sessionId) {
                // Drain unrelated cleanup completions until this owner stops.
            }
        }

        suspend fun awaitStopAttempts(sessionId: String, count: Int) {
            while (stopAttempts.count { it == sessionId } < count) {
                stopAttemptEvents.receive()
            }
        }
    }

    private companion object {
        fun basePlan(
            sessionId: String = "s1",
            fileId: Int = 42,
            // An in-place replan returns the SAME session id with a NEW plan id.
            // Deriving planId from sessionId alone made such a test pass
            // vacuously: the "stale" plan id it asserted against was identical
            // to the current one.
            planId: String = "plan-$sessionId",
        ): PlaybackPlanV3 = PlaybackPlanV3(
            planId = planId,
            sessionId = sessionId,
            delivery = PlaybackDelivery.SERVER_REMUX_HLS,
            engine = PlaybackEngineKind.MEDIA3_HLS,
            stream = PlaybackStreamV3(
                url = "/stream/$sessionId/master.m3u8",
                protocol = PlaybackStreamProtocol.HLS,
                container = "mpegts",
                mimeType = "application/x-mpegURL",
            ),
            selectedTracks = SelectedPlaybackTracksV3(audio = audioTrack(fileId)),
            effectiveRecipe = PlaybackEffectiveRecipeV3(
                videoCodec = "hevc",
                audioCodec = "eac3",
            ),
            decisionReason = "test",
            requestedMediaFileId = fileId,
            effectiveMediaFileId = fileId,
        )

        fun sidecarPlan(
            sessionId: String,
            planId: String = "plan-$sessionId",
        ): PlaybackPlanV3 = basePlan(sessionId, planId = planId).copy(
            selectedTracks = SelectedPlaybackTracksV3(
                audio = audioTrack(fileId = 42),
                subtitle = PlaybackTrackIdentityV3(
                    id = subtitleTrackId(fileId = 42, index = 4),
                    index = 4,
                ),
            ),
            subtitle = PlaybackSubtitleDecisionV3(
                mode = PlaybackSubtitleModeV3.CONVERT,
                trackId = subtitleTrackId(fileId = 42, index = 4),
                artifact = sidecarArtifact(sessionId = sessionId, index = 4),
            ),
        )

        fun sidecarArtifact(sessionId: String, index: Int): PlaybackSubtitleArtifactV3 =
            PlaybackSubtitleArtifactV3(
                url = "/stream/$sessionId/subtitles/$index.vtt",
                mimeType = "text/vtt",
                format = "webvtt",
            )

        fun audioTrack(fileId: Int): PlaybackTrackIdentityV3 =
            PlaybackTrackIdentityV3("file:$fileId:audio:0", 0)

        fun subtitleTrackId(fileId: Int, index: Int): String =
            "file:$fileId:subtitle:$index"

        fun response(plan: PlaybackPlanV3): PlaybackDecisionResponseV3 =
            PlaybackDecisionResponseV3(
                protocolVersion = 3,
                serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
                outcome = PlaybackDecisionOutcome.PLAYABLE,
                sessionId = plan.sessionId,
                playbackPlan = plan,
            )

        fun terminalResponse(
            sessionId: String,
            reason: String,
            message: String,
        ): PlaybackDecisionResponseV3 = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.ADAPTATION_UNAVAILABLE,
            sessionId = sessionId,
            terminal = PlaybackTerminalV3(
                reason = reason,
                message = message,
                retryable = false,
            ),
        )
    }
}

private object StagedReplanNoOpTokenManager : TokenManager {
    override val sessionExpired: SharedFlow<Unit> = MutableSharedFlow()
    override suspend fun getAccessToken(): String? = null
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {}
    override suspend fun clearTokens() {}
    override suspend fun invalidateSession() {}
    override suspend fun getProfileId(): String? = null
    override suspend fun setProfileId(profileId: String?) {}
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) {}
    override suspend fun getServerUrl(): String = ""
    override suspend fun setServerUrl(url: String) {}
    override suspend fun getCurrentServerId(): String? = null
    override suspend fun switchActiveServer(serverId: String?) {}
    override suspend fun signOutCurrentServer() {}
    override suspend fun snapshotCurrentScope(): AuthScopeSnapshot? = null
}
