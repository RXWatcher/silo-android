package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.encodeSubtitleIdentityPreference
import org.siloserver.silo.network.ApiResult

class TvPlayerFinalOwnershipAcceptanceTest {
    private val viewModelSource = source("TvPlayerViewModel.kt")
    private val adapterSource = source("TvSubtitleTransactionAdapter.kt")

    @Test
    fun `reverse quality mutation invalidates a replacement load before either can split publication`() {
        val registry = TvPlayerLoadOwnerRegistry()
        val fence = TvPlayerMutationFence(registry) {}
        val versionB = fence.beginLoad("movie", preferredFileId = 22, preferredQuality = "720p")

        // Keep this behavioral while freezing the new production API without
        // making the whole RED suite fail at Kotlin compilation.
        fence.javaClass.getDeclaredMethod("beginReplan").invoke(fence)

        var lifecycleSession = "session-a"
        var uiSession = "session-a"
        var publications = 0
        if (fence.owns(versionB)) {
            lifecycleSession = "session-b"
            uiSession = "session-b"
            publications += 1
        }
        // The newer A-route mutation is now the only owner allowed to adopt.
        lifecycleSession = "session-a-quality"
        uiSession = "session-a-quality"
        publications += 1

        assertFalse(fence.owns(versionB))
        assertEquals(1, publications)
        assertEquals(uiSession, lifecycleSession)

        val qualityBody = viewModelSource
            .substringAfter("fun switchQuality(wireValue: String)")
            .substringBefore("private fun transcodeQualityLadder(")
        assertBefore(
            qualityBody,
            "playbackMutationFence.beginReplan()",
            "subtitleTransactions.selectQuality(wireValue)",
        )
    }

    @Test
    fun `reverse output route mutation shares the replacement load fence`() {
        val routeCollector = viewModelSource
            .substringAfter("capabilityDetector.outputRouteGeneration.drop(1).collect")
            .substringBefore("// When the sleep timer fires")

        assertBefore(
            routeCollector,
            "playbackMutationFence.beginReplan()",
            "subtitleTransactions.updateOutputRouteGeneration(",
        )
    }

    @Test
    fun `real staged output route request carries the new capabilities context and generation`() {
        val requestDeclaration = adapterSource
            .substringAfter("internal data class TvSubtitleStageRequest(")
            .substringBefore("internal data class TvStagedSubtitleCandidate(")
        val realPortStage = adapterSource
            .substringAfter("internal class PlaybackSessionManagerTvSubtitleStagedReplanPort(")
            .substringAfter("override suspend fun stage(")
            .substringBefore("override suspend fun commit(")

        assertTrue(requestDeclaration.contains("val capabilities: ClientCodecCapabilities"))
        assertTrue(requestDeclaration.contains("val clientPlaybackContext: ClientPlaybackContext"))
        assertTrue(requestDeclaration.contains("val outputRouteGeneration: Long"))
        assertTrue(realPortStage.contains("capabilities = input.capabilities"))
        assertTrue(realPortStage.contains("clientPlaybackContext = input.clientPlaybackContext"))
        assertTrue(realPortStage.contains("outputRouteGeneration = handle.outputRouteGeneration"))
        assertFalse(realPortStage.contains("outputRouteGeneration = input.outputRouteGeneration"))

        val capabilities = ClientCodecCapabilities(codecsVideo = listOf("hevc"))
        val context = ClientPlaybackContext(
            formFactor = "tv",
            appVersion = "test",
            output = PlaybackOutputContext(outputRouteGeneration = 9),
        )
        val input = assertIs<ApiResult.Success<TvSubtitleManagerStageInput>>(
            stageInput(
                capabilities = capabilities,
                context = context,
                generation = 9,
            ).toManagerStageInput(),
        ).data
        assertEquals(capabilities, input.capabilities)
        assertEquals(context, input.clientPlaybackContext)
        assertEquals(9, input.outputRouteGeneration)

        assertIs<ApiResult.Error>(
            stageInput(
                capabilities = capabilities,
                context = context,
                generation = 8,
            ).toManagerStageInput(),
        )
    }

    private fun stageInput(
        capabilities: ClientCodecCapabilities,
        context: ClientPlaybackContext,
        generation: Long,
    ) = TvSubtitleStageRequest(
        generation = 1,
        contentId = "movie",
        mediaFileId = 1,
        versionId = "v1",
        sessionId = "session-a",
        positionSeconds = 12.0,
        audioTrackIndex = 0,
        qualityPreference = "auto",
        subtitleTrackIndex = -1,
        outputRouteGeneration = generation,
        capabilities = capabilities,
        clientPlaybackContext = context,
    )

    @Test
    fun `successful version replacement confirms B only after publication while failure retains A`() {
        val loadBody = viewModelSource
            .substringAfter("private fun loadContent(")
            .substringBefore("private fun startIntroAutoSkipObserver(")
        val readyBody = loadBody
            .substringAfter("is VideoPlayerUiState.Ready ->")
            .substringBefore("is VideoPlayerUiState.Error ->")
        val failureBody = loadBody
            .substringAfter("is VideoPlayerUiState.Error ->")
            .substringBefore("is VideoPlayerUiState.Loading ->")
        val publicationIndex = readyBody.indexOf("if (!published) return@launch")
        val managerConfirmation = readyBody.indexOf(
            ".confirmVideoSessionPublication(publishedSessionId)",
        )
        val jointConfirmation = readyBody.indexOf(
            "sessionLifecycle.settlePendingPublicationIfCurrent(",
        )

        assertTrue(publicationIndex >= 0)
        assertTrue(managerConfirmation > publicationIndex)
        assertTrue(jointConfirmation > publicationIndex)
        assertTrue(managerConfirmation > jointConfirmation)
        assertFalse(readyBody.contains("stopReplacedTvSessionAfterPublication("))
        assertFalse(failureBody.contains("settlePendingPublicationIfCurrent("))

        val versionSwitchBody = viewModelSource
            .substringAfter("fun onSelectFileVersion(fileId: Int)")
            .substringBefore("/** Reload the current content")
        assertFalse(versionSwitchBody.contains("playbackSessionManager.stopSession("))
    }

    @Test
    fun `fresh Ready remains rollback owned through every post start await and confirmation`() {
        val loadBody = viewModelSource
            .substringAfter("private fun loadContent(")
            .substringBefore("private fun startIntroAutoSkipObserver(")
        val readyBody = loadBody
            .substringAfter("is VideoPlayerUiState.Ready ->")
            .substringBefore("is VideoPlayerUiState.Error ->")
        val acquire = readyBody.indexOf("unpublishedReadySession.acquire(")
        val localSelection = readyBody.indexOf("userItemStatePort.localTrackSelection(")
        val hydration = readyBody.indexOf("resolveOwnedTvFreshSubtitleRestore(")
        val publication = readyBody.lastIndexOf("loadOwners.publishReadyIfOwned(")
        val confirmation = readyBody.indexOf(
            "sessionLifecycle.settlePendingPublicationIfCurrent(",
        )
        val transfer = readyBody.indexOf(".transferConfirmed(publishedSessionId)")

        assertTrue(acquire >= 0)
        assertTrue(localSelection > acquire)
        assertTrue(hydration > localSelection)
        assertTrue(publication > hydration)
        assertTrue(confirmation > publication)
        assertTrue(transfer > confirmation)
        assertTrue(readyBody.contains("unpublishedReadySession::rollbackIfOwned"))

        val cancellationCatch = loadBody
            .substringAfter("catch (cancellation: CancellationException)")
            .substringBefore("catch (e: Exception)")
        val exceptionCatch = loadBody
            .substringAfter("catch (e: Exception)")
            .substringBefore("private fun startIntroAutoSkipObserver(")
        assertTrue(cancellationCatch.contains("unpublishedReadySession.rollbackIfOwned("))
        assertTrue(exceptionCatch.contains("unpublishedReadySession.rollbackIfOwned("))
        assertTrue(readyBody.indexOf("withContext(NonCancellable)") < confirmation)
    }

    @Test
    fun `persisted audio resolves and applies during the first and only tracks snapshot`() {
        val catalog = listOf(
            AudioTrack(index = 1, language = "en", codec = "aac", title = "English"),
            AudioTrack(index = 5, language = "ja", codec = "ac3", title = "Japanese"),
        )
        val persisted = audioTrackFingerprint(catalog[1])
        val mounted = listOf(
            PlayerTrackEntry(
                index = 0,
                label = "English",
                language = "en",
                isSelected = true,
                codecOrMime = "aac",
            ),
            PlayerTrackEntry(
                index = 1,
                label = "Japanese",
                language = "ja",
                isSelected = false,
                codecOrMime = "ac3",
            ),
        )

        assertEquals(
            1,
            resolveTvPersistedAudioPlayerOrdinal(
                fingerprint = persisted,
                catalogAudioTracks = catalog,
                mountedAudioTracks = mounted,
            ),
        )
        assertEquals(5, resolveTvRemoteAudioIntent(1, catalog))

        val tracksBody = viewModelSource
            .substringAfter("fun onTracksChanged(\n        audio: List<PlayerTrackEntry>")
            .substringBefore("private fun resolvePendingPersistedTrackSelection(")
        assertBefore(
            tracksBody,
            "resolvePendingPersistedTrackSelection(audio, subtitle)",
            "retryPendingRemoteTrackIntents()",
        )
    }

    @Test
    fun `player only LocalMedia3 preference defers before mount then selects the exact later track`() {
        val identity = SubtitleIdentity.LocalMedia3(
            SubtitleMediaIdentity(
                trackId = "media3:english",
                label = "English",
                language = "en",
                codecFamily = "subrip",
                forced = false,
            ),
        )
        val deferred = resolveTvFreshSubtitlePreference(
            preference = encodeSubtitleIdentityPreference(identity),
            catalogTracks = emptyList(),
            hydratedRows = listOf(
                PlayerSubtitleInfo(
                    index = 8,
                    codec = "webvtt",
                    source = "downloaded",
                    url = "https://silo.test/subtitles/8.vtt",
                    downloadId = 44,
                    mediaTrackId = "silo-downloaded-subtitle:44",
                ),
                PlayerSubtitleInfo(
                    index = 9,
                    codec = "pgs",
                    source = "embedded",
                    url = "",
                    mediaTrackId = "embedded:9",
                ),
            ),
        )

        assertEquals(identity, assertNotNull(deferred).identity)

        val reselection = SubtitleRemountReselection()
        reselection.arm(identity, generation = 7)
        assertNull(reselection.consume(emptyList(), snapshotKey = null, settled = false))
        val event = reselection.consume(
            subtitleTracks = listOf(
                playerTrack(
                    index = 4,
                    trackId = "media3:english",
                    label = "English",
                ),
            ),
            snapshotKey = "mounted-english",
            settled = false,
        )

        assertEquals(4, assertIs<TvSubtitleRemountEvent.Select>(event).trackIndex)
    }

    @Test
    fun `deferred LocalMedia3 ambiguity waits for settlement then fails without fallback`() {
        val identity = SubtitleIdentity.LocalMedia3(
            SubtitleMediaIdentity(
                label = "English",
                language = "en",
                codecFamily = "subrip",
                forced = false,
            ),
        )
        val deferred = resolveTvFreshSubtitlePreference(
            preference = encodeSubtitleIdentityPreference(identity),
            catalogTracks = emptyList(),
            hydratedRows = emptyList(),
        )
        assertEquals(identity, assertNotNull(deferred).identity)

        val tracks = listOf(
            playerTrack(index = 2, trackId = "media3:full", label = "English"),
            playerTrack(index = 3, trackId = "media3:forced", label = "English"),
        )
        val reselection = SubtitleRemountReselection()
        reselection.arm(identity, generation = 8)

        assertNull(
            reselection.consume(
                subtitleTracks = tracks,
                snapshotKey = "ambiguous",
                settled = false,
            ),
        )
        val failed = reselection.consume(
            subtitleTracks = tracks,
            snapshotKey = "ambiguous",
            settled = true,
        )

        assertEquals(
            TvSubtitleRemountFailure.Ambiguous,
            assertIs<TvSubtitleRemountEvent.Failed>(failed).reason,
        )
    }

    private fun playerTrack(
        index: Int,
        trackId: String,
        label: String,
    ) = PlayerTrackEntry(
        index = index,
        label = label,
        language = "en",
        isSelected = false,
        displayLabel = label,
        codecOrMime = "srt",
        trackId = trackId,
        isForced = false,
    )

    private fun source(fileName: String): String = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/$fileName",
    ).readText()

    private fun assertBefore(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing `$first`")
        assertTrue(secondIndex >= 0, "Missing `$second`")
        assertTrue(firstIndex < secondIndex, "`$first` must occur before `$second`")
    }

    private fun String.indicesOf(needle: String): List<Int> = buildList {
        var offset = 0
        while (true) {
            val index = indexOf(needle, startIndex = offset)
            if (index < 0) return@buildList
            add(index)
            offset = index + needle.length
        }
    }
}
