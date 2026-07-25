package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.repository.port.TrackSelectionFingerprintUpdate

class TvPlayerSubtitleIntegrationPolicyTest {
    @Test
    fun `unresolved audio during subtitle persistence preserves the existing preference`() {
        val update = tvAudioTrackPersistenceUpdate(
            committedAudioTrackIndex = 7,
            audioTracks = listOf(AudioTrack(index = 2, language = "en", codec = "aac")),
        )

        assertEquals(TrackSelectionFingerprintUpdate.Preserve, update)
    }

    @Test
    fun `resolved audio during subtitle persistence writes the exact fingerprint`() {
        val selected = AudioTrack(index = 7, language = "ja", codec = "ac3")

        val update = tvAudioTrackPersistenceUpdate(
            committedAudioTrackIndex = 7,
            audioTracks = listOf(selected),
        )

        assertEquals(
            TrackSelectionFingerprintUpdate.Set(audioTrackFingerprint(selected)),
            update,
        )
    }

    @Test
    fun `missing audio intent during subtitle persistence preserves rather than clears`() {
        assertEquals(
            TrackSelectionFingerprintUpdate.Preserve,
            tvAudioTrackPersistenceUpdate(
                committedAudioTrackIndex = null,
                audioTracks = emptyList(),
            ),
        )
    }

    @Test
    fun `authoritative empty adapter snapshot clears stale downloaded UI rows`() {
        val stale = listOf(downloadedRow(index = 4, downloadId = 91))

        assertEquals(
            emptyList(),
            authoritativeTvSubtitleRows(snapshotRows = emptyList(), previousRows = stale),
        )
    }

    @Test
    fun `download auto selection uses the same canonical identity as the HUD row`() {
        val row = downloadedRow(index = 4, downloadId = 91)

        assertEquals(
            tvSubtitleIdentity(row),
            tvDownloadedRefreshIdentity(row),
        )
    }

    @Test
    fun `download auto selection with missing domain id safely returns null`() {
        val legacy = downloadedRow(index = 4, downloadId = null)

        assertEquals(null, tvDownloadedRefreshIdentity(legacy))
    }

    @Test
    fun `T91 remote subtitle intent resolves a typed identity instead of a backend ordinal`() {
        val row = PlayerSubtitleInfo(
            index = 8,
            language = "en",
            codec = "srt",
            label = "English",
            source = "server_artifact",
            forced = false,
            url = "/subtitles/8.srt",
        )
        val mounted = PlayerTrackEntry(
            index = 2,
            label = "English",
            language = "en",
            isSelected = false,
            displayLabel = "English",
            codecOrMime = "srt",
            trackId = "silo-subtitle:8",
        )

        val identity = resolveTvRemoteSubtitleIntent(
            playerOrdinal = 2,
            subtitleTracks = listOf(mounted),
            subtitleRows = listOf(row),
        )

        assertEquals(tvSubtitleIdentity(row), identity)
    }

    @Test
    fun `T91 remote Off intent is typed and does not need mounted tracks`() {
        assertEquals(
            SubtitleIdentity.Off,
            resolveTvRemoteSubtitleIntent(
                playerOrdinal = -1,
                subtitleTracks = emptyList(),
                subtitleRows = emptyList(),
            ),
        )
    }

    @Test
    fun `T92 remote audio intent resolves the stable server index for the adapter`() {
        val identity = resolveTvRemoteAudioIntent(
            playerOrdinal = 1,
            audioTracks = listOf(
                AudioTrack(index = 3, language = "en", codec = "aac"),
                AudioTrack(index = 9, language = "ja", codec = "ac3"),
            ),
        )

        assertEquals(9, identity)
    }

    @Test
    fun `invalid pre-mount remote intents remain unresolved rather than disabling subtitles`() {
        assertEquals(
            null,
            resolveTvRemoteSubtitleIntent(
                playerOrdinal = 4,
                subtitleTracks = emptyList(),
                subtitleRows = emptyList(),
            ),
        )
        assertEquals(
            null,
            resolveTvRemoteAudioIntent(
                playerOrdinal = 4,
                audioTracks = emptyList(),
            ),
        )
    }

    // Embedded PGS is client-mounted: the server raw-serves it as a `.sup`
    // sidecar, so it materialises like extracted text rather than demanding a
    // burn-in.
    @Test
    fun `embedded PGS stays a client-mounted identity`() {
        val identity = tvSubtitleIdentity(embeddedRow(index = 8, codec = "hdmv_pgs_subtitle"))

        assertIs<SubtitleIdentity.Embedded>(identity)
        assertEquals(8, identity.serverIndex)
    }

    // VobSub and DVB have no sidecar route, so the server always burns them in.
    // Demanding a sidecar for those made a pick silently revert to Off.
    @Test
    fun `embedded bitmap without a sidecar route is a burn-in identity`() {
        for (codec in listOf("dvd_subtitle", "dvb_subtitle", "vobsub")) {
            val identity = tvSubtitleIdentity(embeddedRow(index = 5, codec = codec))

            assertIs<SubtitleIdentity.ServerBurnIn>(identity)
            assertEquals(5, identity.serverIndex, codec)
        }
    }

    @Test
    fun `embedded text subtitle stays an embedded identity`() {
        val identity = tvSubtitleIdentity(embeddedRow(index = 3, codec = "subrip"))

        assertIs<SubtitleIdentity.Embedded>(identity)
        assertEquals(3, identity.serverIndex)
    }

    private fun embeddedRow(
        index: Int,
        codec: String,
    ) = PlayerSubtitleInfo(
        index = index,
        language = "en",
        codec = codec,
        label = "English (SDH)",
        source = "embedded",
        catalogSource = "embedded",
        forced = false,
        url = "",
    )

    private fun downloadedRow(
        index: Int,
        downloadId: Int?,
    ) = PlayerSubtitleInfo(
        index = index,
        language = "en",
        codec = "vtt",
        label = "English",
        source = "downloaded",
        forced = false,
        url = "/subtitles/${downloadId ?: "legacy"}.vtt",
        downloadId = downloadId,
        mediaTrackId = downloadId?.let { "silo-downloaded-subtitle:$it" },
    )
}
