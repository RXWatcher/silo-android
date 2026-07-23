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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertIs

class PlaybackSessionManagerStagedReplanTest {
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
        assertEquals(listOf("s1"), harness.stoppedSessions)

        val consumed = harness.manager.commitStagedVideoReplan(staged)
        assertEquals(409, assertIs<ApiResult.Error>(consumed).code)
        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(listOf("s1"), harness.stoppedSessions)
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

        assertEquals(409, assertIs<ApiResult.Error>(stale).code)
        assertEquals("s3", harness.manager.activeSessionIdForTest())
        assertEquals(listOf("s1", "s2"), harness.stoppedSessions)
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
        val stagedFromS2 = harness.stageSidecar()

        harness.manager.stopSession("s1")

        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s1" to 2), harness.stoppedSessions.groupingBy { it }.eachCount())

        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(stagedFromS2),
        )
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
    ) {
        val stoppedSessions: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val replanBodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
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
                        val body = SiloJson.parseToJsonElement(
                            request.body.toByteArray().decodeToString(),
                        ).jsonObject
                        replanBodies += body
                        replanResponse(replanIndex.getAndIncrement(), body)
                    }
                    request.method == HttpMethod.Delete && path.startsWith("/api/v1/playback/") -> {
                        stoppedSessions += path.substringAfterLast('/')
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

        suspend fun start(fileId: Int = 42) {
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
    }

    private companion object {
        fun basePlan(
            sessionId: String = "s1",
            fileId: Int = 42,
        ): PlaybackPlanV3 = PlaybackPlanV3(
            planId = "plan-$sessionId",
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

        fun sidecarPlan(sessionId: String): PlaybackPlanV3 = basePlan(sessionId).copy(
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
