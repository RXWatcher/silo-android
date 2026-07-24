package org.siloserver.silo.tv.ui.screens.detail

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.decodeSubtitleIdentityPreference
import org.siloserver.silo.playback.encodeCatalogSubtitlePreference
import org.siloserver.silo.playback.subtitleTrackFingerprint
import org.siloserver.silo.repository.port.LocalTrackSelection
import org.siloserver.silo.repository.port.OutboxHandle
import org.siloserver.silo.repository.port.TrackSelectionFingerprintUpdate
import org.siloserver.silo.repository.port.UserItemStatePort
import org.siloserver.silo.repository.port.WriteOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvTrackSelectionPersistenceTest {
    @Test
    fun subtitleSelectionWritesTypedCombinedCatalogIdentityAtomically() = runTest {
        val version = version()
        val payload = buildTrackSelectionPersistence(
            targetContentId = "episode-42",
            version = version,
            changedDimension = TvTrackSelectionDimension.Subtitle,
            selectedAudioIndex = null,
            selectedSubtitleIndex = 0,
        )
        val port = RecordingTrackSelectionPort()

        recordTrackSelection(port, payload)

        val write = assertIs<RecordingTrackSelectionPort.AtomicWrite>(port.writes.single())
        assertEquals("episode-42", write.contentId)
        assertEquals(22, write.fileId)
        assertEquals(TrackSelectionFingerprintUpdate.Preserve, write.audioUpdate)
        val encoded = assertIs<TrackSelectionFingerprintUpdate.Set>(write.subtitleUpdate).fingerprint
        val identity = assertIs<SubtitleIdentity.ServerSidecar>(
            decodeSubtitleIdentityPreference(encoded),
        )
        // Combined index 0 is the external catalog track at ordinal 1.
        assertEquals(0, identity.serverIndex)
        assertEquals("French full", identity.media?.label)
        assertEquals("fr", identity.media?.language)
        assertEquals("subrip", identity.media?.codecFamily)
        assertEquals(false, identity.media?.forced)
    }

    @Test
    fun typedOffPreferenceRestoresInTvDetail() {
        val version = version()
        val restored = restoreTrackSelection(
            version,
            LocalTrackSelection(
                audioFingerprint = null,
                subtitleFingerprint = encodeCatalogSubtitlePreference(
                    version.subtitleTracks.orEmpty(),
                    selectedOrdinal = -1,
                ),
            ),
        )

        assertEquals(-1, restored.subtitleIndex)
        assertNull(restored.subtitlePreferenceMigration)
    }

    @Test
    fun typedServerSidecarPreferenceFollowsMetadataAfterCatalogReorder() {
        val original = listOf(
            external("English full", "eng", "srt", forced = false),
            external("French full", "fra", "srt", forced = false),
            embedded("English embedded", "eng", "ass", index = 18),
        )
        val saved = encodeCatalogSubtitlePreference(original, selectedOrdinal = 0)
        val reordered = listOf(original[1], original[2], original[0])

        val restored = restoreTrackSelection(
            FileVersion(fileId = 22, subtitleTracks = reordered),
            LocalTrackSelection(audioFingerprint = null, subtitleFingerprint = saved),
        )

        // The English external row moved to catalog ordinal 2 and combined index 1.
        assertEquals(1, restored.subtitleIndex)
        assertNull(restored.subtitlePreferenceMigration)
    }

    @Test
    fun typedServerBurnInPreferenceFollowsMetadataAfterCatalogReorder() {
        val original = listOf(
            external("English full", "eng", "srt", forced = false),
            external("English PGS", "eng", "hdmv_pgs_subtitle", forced = false),
            embedded("English embedded", "eng", "ass", index = 18),
        )
        val saved = encodeCatalogSubtitlePreference(original, selectedOrdinal = 1)
        val reordered = listOf(original[1], original[2], original[0])

        val restored = restoreTrackSelection(
            FileVersion(fileId = 22, subtitleTracks = reordered),
            LocalTrackSelection(audioFingerprint = null, subtitleFingerprint = saved),
        )

        assertEquals(0, restored.subtitleIndex)
        assertNull(restored.subtitlePreferenceMigration)
    }

    @Test
    fun typedEmbeddedPreferenceFollowsMetadataAfterCatalogReorder() {
        val original = listOf(
            external("French full", "fra", "srt", forced = false),
            embedded("English embedded", "eng", "ass", index = 18),
            embedded("Japanese embedded", "jpn", "ass", index = 24),
        )
        val saved = encodeCatalogSubtitlePreference(original, selectedOrdinal = 1)
        val reordered = listOf(original[0], original[2], original[1])

        val restored = restoreTrackSelection(
            FileVersion(fileId = 22, subtitleTracks = reordered),
            LocalTrackSelection(audioFingerprint = null, subtitleFingerprint = saved),
        )

        // One external precedes the reordered embedded rows; English is the
        // second embedded row and therefore has combined index 2.
        assertEquals(2, restored.subtitleIndex)
        assertNull(restored.subtitlePreferenceMigration)
    }

    @Test
    fun legacyFingerprintStillRestoresAndRequestsTypedMigration() {
        val version = version()
        val legacy = subtitleTrackFingerprint(version.subtitleTracks!![1])
        val restored = restoreTrackSelection(
            version,
            LocalTrackSelection(
                audioFingerprint = audioTrackFingerprint(version.audioTracks!![1]),
                subtitleFingerprint = legacy,
            ),
        )

        assertEquals(1, restored.audioIndex)
        assertEquals(0, restored.subtitleIndex)
        val migrated = assertIs<SubtitleIdentity.ServerSidecar>(
            decodeSubtitleIdentityPreference(restored.subtitlePreferenceMigration),
        )
        assertEquals(0, migrated.serverIndex)
        assertEquals("French full", migrated.media?.label)
    }

    @Test
    fun legacyOffStillRestoresAndRequestsTypedOffMigration() {
        val restored = restoreTrackSelection(
            version(),
            LocalTrackSelection(
                audioFingerprint = null,
                subtitleFingerprint = org.siloserver.silo.playback.SUBTITLE_OFF_FINGERPRINT,
            ),
        )

        assertEquals(-1, restored.subtitleIndex)
        assertEquals(
            SubtitleIdentity.Off,
            decodeSubtitleIdentityPreference(restored.subtitlePreferenceMigration),
        )
    }

    @Test
    fun typedMetadataMismatchAndAmbiguitySafelyMiss() {
        val original = listOf(
            external("English full", "eng", "srt", forced = false),
        )
        val saved = encodeCatalogSubtitlePreference(original, selectedOrdinal = 0)
        val mismatch = listOf(
            external("French full", "fra", "srt", forced = false),
        )
        val ambiguous = listOf(original[0], original[0])

        assertNull(
            restoreTrackSelection(
                FileVersion(fileId = 22, subtitleTracks = mismatch),
                LocalTrackSelection(null, saved),
            ).subtitleIndex,
        )
        assertNull(
            restoreTrackSelection(
                FileVersion(fileId = 22, subtitleTracks = ambiguous),
                LocalTrackSelection(null, saved),
            ).subtitleIndex,
        )
    }

    @Test
    fun audioOnlyChangePreservesTypedSubtitlePreference() = runTest {
        val payload = buildTrackSelectionPersistence(
            targetContentId = "movie-1",
            version = version(),
            changedDimension = TvTrackSelectionDimension.Audio,
            selectedAudioIndex = 1,
            selectedSubtitleIndex = null,
        )
        val port = RecordingTrackSelectionPort()

        recordTrackSelection(port, payload)

        val write = assertIs<RecordingTrackSelectionPort.AtomicWrite>(port.writes.single())
        assertEquals(
            TrackSelectionFingerprintUpdate.Set(
                audioTrackFingerprint(version().audioTracks!![1]),
            ),
            write.audioUpdate,
        )
        assertEquals(TrackSelectionFingerprintUpdate.Preserve, write.subtitleUpdate)
    }

    @Test
    fun subtitleOnlyChangePreservesAudioPreference() = runTest {
        val payload = buildTrackSelectionPersistence(
            targetContentId = "movie-1",
            version = version(),
            changedDimension = TvTrackSelectionDimension.Subtitle,
            selectedAudioIndex = null,
            selectedSubtitleIndex = -1,
        )
        val port = RecordingTrackSelectionPort()

        recordTrackSelection(port, payload)

        val write = assertIs<RecordingTrackSelectionPort.AtomicWrite>(port.writes.single())
        assertEquals(TrackSelectionFingerprintUpdate.Preserve, write.audioUpdate)
        val subtitle = assertIs<TrackSelectionFingerprintUpdate.Set>(write.subtitleUpdate)
        assertEquals(SubtitleIdentity.Off, decodeSubtitleIdentityPreference(subtitle.fingerprint))
    }

    @Test
    fun missingPositiveCombinedSubtitleMappingSafelyPreservesInsteadOfEncodingOff() = runTest {
        val payload = buildTrackSelectionPersistence(
            targetContentId = "movie-1",
            version = version(),
            changedDimension = TvTrackSelectionDimension.Subtitle,
            selectedAudioIndex = null,
            selectedSubtitleIndex = 99,
        )
        val port = RecordingTrackSelectionPort(
            initialAudio = "old-audio",
            initialSubtitle = "old-subtitle",
        )

        recordTrackSelection(port, payload)

        val write = assertIs<RecordingTrackSelectionPort.AtomicWrite>(port.writes.single())
        assertEquals(TrackSelectionFingerprintUpdate.Preserve, write.audioUpdate)
        assertEquals(TrackSelectionFingerprintUpdate.Preserve, write.subtitleUpdate)
        assertEquals(
            listOf<Pair<String?, String?>>("old-audio" to "old-subtitle"),
            port.atomicSnapshots,
        )
        assertTrue(port.legacySnapshots.isEmpty())
    }

    @Test
    fun autoClearsOnlyTheChangedDimension() = runTest {
        val audioPort = RecordingTrackSelectionPort()
        recordTrackSelection(
            audioPort,
            buildTrackSelectionPersistence(
                targetContentId = "movie-1",
                version = version(),
                changedDimension = TvTrackSelectionDimension.Audio,
                selectedAudioIndex = null,
                selectedSubtitleIndex = 0,
            ),
        )
        val subtitlePort = RecordingTrackSelectionPort()
        recordTrackSelection(
            subtitlePort,
            buildTrackSelectionPersistence(
                targetContentId = "movie-1",
                version = version(),
                changedDimension = TvTrackSelectionDimension.Subtitle,
                selectedAudioIndex = 1,
                selectedSubtitleIndex = null,
            ),
        )

        val audioWrite = assertIs<RecordingTrackSelectionPort.AtomicWrite>(audioPort.writes.single())
        assertEquals(TrackSelectionFingerprintUpdate.Clear, audioWrite.audioUpdate)
        assertEquals(TrackSelectionFingerprintUpdate.Preserve, audioWrite.subtitleUpdate)
        val subtitleWrite = assertIs<RecordingTrackSelectionPort.AtomicWrite>(subtitlePort.writes.single())
        assertEquals(TrackSelectionFingerprintUpdate.Preserve, subtitleWrite.audioUpdate)
        assertEquals(TrackSelectionFingerprintUpdate.Clear, subtitleWrite.subtitleUpdate)
    }

    @Test
    fun logicalTrackWriteNeverExposesATornPair() = runTest {
        val port = RecordingTrackSelectionPort(
            initialAudio = "old-audio",
            initialSubtitle = "old-subtitle",
        )
        val payload = buildTrackSelectionPersistence(
            targetContentId = "movie-1",
            version = version(),
            changedDimension = TvTrackSelectionDimension.Subtitle,
            selectedAudioIndex = null,
            selectedSubtitleIndex = 0,
        )

        recordTrackSelection(port, payload)

        assertEquals(1, port.writes.size)
        assertTrue(port.legacySnapshots.isEmpty())
        assertEquals(
            listOf<Pair<String?, String?>>(
                "old-audio" to assertIs<TrackSelectionFingerprintUpdate.Set>(
                    assertIs<RecordingTrackSelectionPort.AtomicWrite>(port.writes.single())
                        .subtitleUpdate,
                ).fingerprint,
            ),
            port.atomicSnapshots,
        )
    }

    @Test
    fun nextUpSessionIsKeyedToEpisodeIdentity() {
        TvDetailTrackSelectionSession.remember("episode-session-a", 22, 1, 0)
        TvDetailTrackSelectionSession.remember("episode-session-b", 23, null, -1)

        assertEquals(TvDetailTrackSelectionSession.Saved(22, 1, 0), TvDetailTrackSelectionSession.recall("episode-session-a"))
        assertEquals(TvDetailTrackSelectionSession.Saved(23, null, -1), TvDetailTrackSelectionSession.recall("episode-session-b"))
    }

    @Test
    fun lateRestoreCannotApplyAfterEpisodeOrVersionChanges() {
        assertTrue(shouldApplyNextUpTrackRestore("episode-42", "episode-42", 22, 22))
        assertFalse(shouldApplyNextUpTrackRestore("episode-43", "episode-42", 22, 22))
        assertFalse(shouldApplyNextUpTrackRestore("episode-42", "episode-42", 23, 22))
    }

    @Test
    fun sessionSelectedFileMergesItsDurableTracksOnReentry() {
        val version = version()
        val session = TvDetailTrackSelectionSession.Saved(fileId = 22, audio = null, subtitle = null)
        val durable = restoreTrackSelection(
            version,
            LocalTrackSelection(
                audioFingerprint = audioTrackFingerprint(version.audioTracks!![1]),
                subtitleFingerprint = subtitleTrackFingerprint(version.subtitleTracks!![1]),
            ),
        )

        val merged = mergeTrackSelection(session.audio, session.subtitle, durable)

        assertEquals(1, merged.audioIndex)
        assertEquals(0, merged.subtitleIndex)
    }

    private fun version() = FileVersion(
        fileId = 22,
        audioTracks = listOf(
            AudioTrack(index = 4, codec = "aac", language = "eng"),
            AudioTrack(index = 9, codec = "ac3", language = "jpn", title = "Commentary"),
        ),
        subtitleTracks = listOf(
            embedded("English embedded", "eng", "ass", index = 10),
            external("French full", "fre", "srt", forced = false),
            embedded("Japanese embedded", "jpn", "ass", index = 11),
        ),
    )

    private fun external(
        title: String,
        language: String,
        codec: String,
        forced: Boolean,
    ) = SubtitleTrack(
        index = 0,
        codec = codec,
        language = language,
        title = title,
        forced = forced,
        external = true,
    )

    private fun embedded(
        title: String,
        language: String,
        codec: String,
        index: Int,
    ) = SubtitleTrack(
        index = index,
        codec = codec,
        language = language,
        title = title,
        external = false,
    )
}

private class RecordingTrackSelectionPort(
    initialAudio: String? = null,
    initialSubtitle: String? = null,
) : UserItemStatePort {
    data class AtomicWrite(
        val contentId: String,
        val fileId: Int,
        val audioUpdate: TrackSelectionFingerprintUpdate,
        val subtitleUpdate: TrackSelectionFingerprintUpdate,
    )

    val writes = mutableListOf<AtomicWrite>()
    val legacySnapshots = mutableListOf<Pair<String?, String?>>()
    val atomicSnapshots = mutableListOf<Pair<String?, String?>>()
    private var audioFingerprint: String? = initialAudio
    private var subtitleFingerprint: String? = initialSubtitle

    override suspend fun recordWatched(contentId: String, watched: Boolean) = OutboxHandle.NONE
    override suspend fun recordFavorite(contentId: String, favorite: Boolean) = OutboxHandle.NONE
    override suspend fun recordRating(contentId: String, rating: Int?) = OutboxHandle.NONE
    override suspend fun resolve(handle: OutboxHandle, outcome: WriteOutcome) = Unit

    override suspend fun recordAudioTrackSelection(
        contentId: String,
        fileId: Int,
        audioFingerprint: String?,
    ) {
        this.audioFingerprint = audioFingerprint
        legacySnapshots += this.audioFingerprint to this.subtitleFingerprint
    }

    override suspend fun recordSubtitleTrackSelection(
        contentId: String,
        fileId: Int,
        subtitleFingerprint: String?,
    ) {
        this.subtitleFingerprint = subtitleFingerprint
        legacySnapshots += this.audioFingerprint to this.subtitleFingerprint
    }

    override suspend fun recordTrackSelection(
        contentId: String,
        fileId: Int,
        audioUpdate: TrackSelectionFingerprintUpdate,
        subtitleUpdate: TrackSelectionFingerprintUpdate,
    ) {
        writes += AtomicWrite(contentId, fileId, audioUpdate, subtitleUpdate)
        audioFingerprint = audioUpdate.applyTo(audioFingerprint)
        subtitleFingerprint = subtitleUpdate.applyTo(subtitleFingerprint)
        atomicSnapshots += audioFingerprint to subtitleFingerprint
    }
}

private fun TrackSelectionFingerprintUpdate.applyTo(current: String?): String? = when (this) {
    TrackSelectionFingerprintUpdate.Preserve -> current
    TrackSelectionFingerprintUpdate.Clear -> null
    is TrackSelectionFingerprintUpdate.Set -> fingerprint
}
