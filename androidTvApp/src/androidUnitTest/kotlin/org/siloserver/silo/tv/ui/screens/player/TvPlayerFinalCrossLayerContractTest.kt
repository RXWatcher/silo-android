package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.common.player.StagedVideoReplan
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.CommittedSubtitle
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.encodeSubtitleIdentityPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TvPlayerFinalCrossLayerContractTest {
    @Test
    fun `real TV port maps route generation from the manager handle rather than its input`() {
        val fields = StagedVideoReplan::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }
        assertTrue("outputRouteGeneration" in fields)

        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/" +
                "TvSubtitleTransactionAdapter.kt",
        ).readText()
        val stageBody = source
            .substringAfter("internal class PlaybackSessionManagerTvSubtitleStagedReplanPort(")
            .substringAfter("override suspend fun stage(")
            .substringBefore("override suspend fun commit(")

        assertTrue(stageBody.contains("outputRouteGeneration = handle.outputRouteGeneration"))
        assertTrue(!stageBody.contains("outputRouteGeneration = input.outputRouteGeneration"))
    }

    @Test
    fun `first audio snapshot maps stable catalog indexes 1 and 5 onto Media3 ordinals 0 and 1`() {
        val catalog = listOf(
            AudioTrack(index = 1, language = "eng", codec = "aac", title = "English"),
            AudioTrack(index = 5, language = "jpn", codec = "ac3", title = "Japanese"),
        )
        val mounted = listOf(
            PlayerTrackEntry(
                index = 0,
                label = "English",
                language = "eng",
                isSelected = true,
                codecOrMime = "aac",
            ),
            PlayerTrackEntry(
                index = 1,
                label = "Japanese",
                language = "jpn",
                isSelected = false,
                codecOrMime = "ac3",
            ),
        )
        val method = Class
            .forName("org.siloserver.silo.tv.ui.screens.player.TvPlayerSubtitlePolicyKt")
            .declaredMethods
            .singleOrNull { it.name == "resolveTvPersistedAudioPlayerOrdinal" }

        assertNotNull(
            method,
            "Production needs one typed catalog-to-Media3 audio restore boundary.",
        )
        method.isAccessible = true
        assertEquals(
            1,
            method.invoke(
                null,
                audioTrackFingerprint(catalog[1]),
                catalog,
                mounted,
            ),
        )
    }

    @Test
    fun `LocalMedia3 restore defers past unrelated Downloaded and Embedded rows`() {
        val identity = SubtitleIdentity.LocalMedia3(
            SubtitleMediaIdentity(
                trackId = "media3:english",
                label = "English",
                language = "en",
                codecFamily = "subrip",
                forced = false,
            ),
        )
        val resolution = resolveTvFreshSubtitlePreference(
            preference = encodeSubtitleIdentityPreference(identity),
            catalogTracks = emptyList(),
            hydratedRows = listOf(
                PlayerSubtitleInfo(
                    index = 7,
                    language = "nl",
                    codec = "vtt",
                    label = "Dutch downloaded",
                    source = "downloaded",
                    forced = false,
                    url = "/stream/session/subtitles/7.vtt",
                    downloadId = 44,
                    mediaTrackId = "silo-downloaded-subtitle:44",
                ),
                PlayerSubtitleInfo(
                    index = 8,
                    language = "fr",
                    codec = "ass",
                    label = "French embedded",
                    source = "embedded",
                    forced = false,
                    url = "",
                    mediaTrackId = "embedded:18",
                ),
            ),
        )

        assertEquals(identity, assertNotNull(resolution).identity)

        val reselection = SubtitleRemountReselection()
        reselection.arm(identity, generation = 11)
        assertNull(reselection.consume(emptyList(), snapshotKey = null, settled = false))
        val selected = reselection.consume(
            subtitleTracks = listOf(
                PlayerTrackEntry(
                    index = 2,
                    label = "English",
                    language = "en",
                    isSelected = false,
                    codecOrMime = "srt",
                    trackId = "media3:english",
                ),
            ),
            snapshotKey = "exact-local",
            settled = false,
        )

        assertEquals(2, assertIs<TvSubtitleRemountEvent.Select>(selected).trackIndex)
    }

    @Test
    fun `sidecar backend rejection replans adopts and remounts prior A before settling`() = runTest {
        verifyCompensatingRestore(
            scope = backgroundScope,
            replacement = sidecarB,
            replacementCandidate = sidecarCandidate("replacement-b", 4),
        )
    }

    @Test
    fun `Off backend rejection replans adopts and remounts prior A before settling`() = runTest {
        verifyCompensatingRestore(
            scope = backgroundScope,
            replacement = SubtitleIdentity.Off,
            replacementCandidate = offCandidate(),
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyCompensatingRestore(
        scope: CoroutineScope,
        replacement: SubtitleIdentity,
        replacementCandidate: TvStagedSubtitleCandidate,
    ) {
        val port = CrossLayerStagedPort()
        val persistence = CrossLayerPersistence()
        val adoptions = mutableListOf<String>()
        val adapter = TvSubtitleTransactionAdapter(
            scope = scope,
            stagedPort = port,
            persistencePort = persistence,
            durablePersistenceScope = scope,
            onCommittedPlayback = {
                adoptions += it.playback.sessionId
                TvSubtitleAdoptionResult.Adopted
            },
        )
        adapter.resetContent(context(), committedIdentity = sidecarA)

        adapter.select(replacement)
        runCurrent()
        port.completeStage(replacementCandidate)
        runCurrent()
        assertEquals(replacement, adapter.snapshot.localMountIdentity)
        assertEquals(listOf(replacementCandidate.sessionId), adoptions)

        adapter.reportMountedSelection(
            identity = replacement,
            selected = false,
            snapshotKey = "backend-rejected",
            settled = true,
        )
        runCurrent()

        assertEquals(
            listOf(replacement.serverIndex(), sidecarA.serverIndex()),
            port.requests.map { it.subtitleTrackIndex },
            "Backend rejection must start a compensating server transaction.",
        )
        assertEquals(sidecarA, adapter.snapshot.committedIdentity)
        assertTrue(persistence.persisted.isEmpty())

        port.completeStage(sidecarCandidate("restore-a", 3))
        runCurrent()

        assertEquals(
            listOf(replacementCandidate.sessionId, "session-restore-a"),
            adoptions,
        )
        assertEquals(sidecarA, adapter.snapshot.localMountIdentity)
        assertTrue(persistence.persisted.isEmpty())

        adapter.reportMountedSelection(
            identity = sidecarA,
            selected = true,
            snapshotKey = "prior-a-restored",
            settled = true,
        )
        runCurrent()

        assertEquals(sidecarA, adapter.snapshot.committedIdentity)
        assertNull(adapter.snapshot.pendingIdentity)
        assertTrue(persistence.persisted.isEmpty())
    }

    private fun context(): TvSubtitlePlaybackContext = TvSubtitlePlaybackContext(
        contentId = "movie",
        mediaFileId = 11,
        versionId = "version-11",
        sessionId = "session-a",
        positionSeconds = 42.0,
        audioTrackIndex = 1,
        qualityPreference = "auto",
        subtitleTracks = emptyList(),
    )

    private fun sidecarCandidate(id: String, index: Int): TvStagedSubtitleCandidate =
        TvStagedSubtitleCandidate(
            id = id,
            sessionId = "session-$id",
            selectedAudioIndex = 1,
            selectedSubtitleIndex = index,
            subtitleMode = PlaybackSubtitleModeV3.RENDER,
            hasSidecar = true,
            subtitleTracks = emptyList(),
            qualityPreference = "auto",
        )

    private fun offCandidate(): TvStagedSubtitleCandidate = TvStagedSubtitleCandidate(
        id = "replacement-off",
        sessionId = "session-replacement-off",
        selectedAudioIndex = 1,
        selectedSubtitleIndex = -1,
        subtitleMode = PlaybackSubtitleModeV3.OFF,
        hasSidecar = false,
        subtitleTracks = emptyList(),
        qualityPreference = "auto",
    )

    private fun SubtitleIdentity.serverIndex(): Int = when (this) {
        SubtitleIdentity.Off -> -1
        is SubtitleIdentity.ServerSidecar -> serverIndex
        else -> error("Unexpected test identity: $this")
    }

    private companion object {
        val sidecarA: SubtitleIdentity = SubtitleIdentity.ServerSidecar(3)
        val sidecarB: SubtitleIdentity = SubtitleIdentity.ServerSidecar(4)
    }
}

private class CrossLayerStagedPort : TvSubtitleStagedReplanPort {
    private val outcomes = Channel<TvStagedSubtitleCandidate>(Channel.UNLIMITED)
    val requests = mutableListOf<TvSubtitleStageRequest>()

    override suspend fun stage(
        request: TvSubtitleStageRequest,
    ): ApiResult<TvStagedSubtitleCandidate> {
        requests += request
        return ApiResult.Success(outcomes.receive())
    }

    override suspend fun commit(
        candidate: TvStagedSubtitleCandidate,
    ): ApiResult<TvSubtitleCommittedPlayback> = ApiResult.Success(
        TvSubtitleCommittedPlayback(
            sessionId = candidate.sessionId,
            subtitleTracks = candidate.subtitleTracks,
            outputRouteGeneration = candidate.outputRouteGeneration,
        ),
    )

    override suspend fun discard(candidate: TvStagedSubtitleCandidate) = Unit

    override suspend fun abandonCommitted(playback: TvSubtitleCommittedPlayback) = Unit

    suspend fun completeStage(candidate: TvStagedSubtitleCandidate) {
        outcomes.send(candidate)
    }
}

private class CrossLayerPersistence : TvSubtitlePersistencePort {
    val persisted = mutableListOf<CommittedSubtitle>()

    override suspend fun persist(
        committed: CommittedSubtitle,
        context: TvSubtitlePlaybackContext,
    ) {
        persisted += committed
    }
}
