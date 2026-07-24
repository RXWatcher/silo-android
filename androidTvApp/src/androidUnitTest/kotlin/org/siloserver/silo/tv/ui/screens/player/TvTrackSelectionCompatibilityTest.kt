package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.playback.decodeSubtitleIdentityPreference
import org.siloserver.silo.playback.encodeCatalogSubtitlePreference
import org.siloserver.silo.playback.encodeSubtitleIdentityPreference
import org.siloserver.silo.playback.SUBTITLE_OFF_FINGERPRINT
import org.siloserver.silo.playback.subtitleTrackFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TvTrackSelectionCompatibilityTest {
    @Test
    fun `legacy Off fresh restore migrates to typed Off`() {
        val resolution = resolveTvFreshSubtitlePreference(
            preference = SUBTITLE_OFF_FINGERPRINT,
            catalogTracks = emptyList(),
            hydratedRows = emptyList(),
        )

        assertEquals(SubtitleIdentity.Off, resolution?.identity)
        assertEquals(
            SubtitleIdentity.Off,
            decodeSubtitleIdentityPreference(resolution?.migratedPreference),
        )
    }

    @Test
    fun `T81 legacy persisted fresh restore migrates to typed identity`() {
        val catalog = listOf(external("English full", "eng", "srt"))
        val resolution = resolveTvFreshSubtitlePreference(
            preference = subtitleTrackFingerprint(catalog.single()),
            catalogTracks = catalog,
            hydratedRows = listOf(
                row(
                    index = 0,
                    label = "English full",
                    language = "en",
                    codec = "subrip",
                    source = "external",
                ),
            ),
        )

        val identity = assertIs<SubtitleIdentity.ServerSidecar>(resolution?.identity)
        assertEquals(0, identity.serverIndex)
        assertEquals("English full", identity.media?.label)
        assertEquals(identity, decodeSubtitleIdentityPreference(resolution?.migratedPreference))
    }

    @Test
    fun `T82 typed sidecar fresh restore resolves exact metadata`() {
        val english = external("English full", "eng", "srt")
        val french = external("French full", "fra", "srt")
        val saved = requireNotNull(
            encodeCatalogSubtitlePreference(listOf(english, french), selectedOrdinal = 0),
        )

        val resolution = resolveTvFreshSubtitlePreference(
            preference = saved,
            catalogTracks = listOf(french, english),
            hydratedRows = listOf(
                row(0, "French full", "fr", "srt", source = "external"),
                row(1, "English full", "en", "srt", source = "external"),
            ),
        )

        val identity = assertIs<SubtitleIdentity.ServerSidecar>(resolution?.identity)
        assertEquals(1, identity.serverIndex)
        assertEquals("English full", identity.media?.label)
        assertNull(resolution?.migratedPreference)
    }

    @Test
    fun `T83 typed burn-in fresh restore does not wait for a mounted row`() {
        val englishText = external("English text", "eng", "srt")
        val englishPgs = external("English PGS", "eng", "hdmv_pgs_subtitle")
        val saved = requireNotNull(
            encodeCatalogSubtitlePreference(
                listOf(englishText, englishPgs),
                selectedOrdinal = 1,
            ),
        )

        val resolution = resolveTvFreshSubtitlePreference(
            preference = saved,
            catalogTracks = listOf(englishPgs, englishText),
            hydratedRows = emptyList(),
        )

        val identity = assertIs<SubtitleIdentity.ServerBurnIn>(resolution?.identity)
        assertEquals(0, identity.serverIndex)
        assertEquals("English PGS", identity.media?.label)
        assertNull(resolution?.migratedPreference)
    }

    @Test
    fun `T84 typed embedded fresh restore resolves exact metadata`() {
        val external = external("French full", "fra", "srt")
        val english = embedded("English embedded", "eng", "ass", index = 18)
        val japanese = embedded("Japanese embedded", "jpn", "ass", index = 24)
        val saved = requireNotNull(
            encodeCatalogSubtitlePreference(
                listOf(external, english, japanese),
                selectedOrdinal = 1,
            ),
        )

        val resolution = resolveTvFreshSubtitlePreference(
            preference = saved,
            catalogTracks = listOf(external, japanese, english),
            hydratedRows = listOf(
                row(
                    index = 2,
                    label = "English embedded",
                    language = "en",
                    codec = "ass",
                    source = "embedded",
                    mediaTrackId = "embedded:18",
                ),
            ),
        )

        val identity = assertIs<SubtitleIdentity.Embedded>(resolution?.identity)
        assertEquals(2, identity.serverIndex)
        assertEquals("English embedded", identity.media.label)
        assertNull(resolution?.migratedPreference)
    }

    @Test
    fun `T85 typed downloaded fresh restore resolves unique downloadId`() {
        val savedIdentity = SubtitleIdentity.Downloaded(
            downloadId = 91,
            media = media(
                trackId = "silo-downloaded-subtitle:91",
                label = "English",
                language = "en",
                codec = "webvtt",
            ),
        )

        val resolution = resolveTvFreshSubtitlePreference(
            preference = encodeSubtitleIdentityPreference(savedIdentity),
            catalogTracks = emptyList(),
            hydratedRows = listOf(
                downloadedRow(index = 4, downloadId = 90),
                downloadedRow(index = 5, downloadId = 91),
            ),
        )

        val identity = assertIs<SubtitleIdentity.Downloaded>(resolution?.identity)
        assertEquals(91, identity.downloadId)
        assertEquals("silo-downloaded-subtitle:91", identity.media.trackId)
        assertNull(resolution?.migratedPreference)
    }

    @Test
    fun `T86 typed LocalMedia3 fresh restore resolves exact metadata`() {
        val savedIdentity = SubtitleIdentity.LocalMedia3(
            media(
                trackId = "media3:english",
                label = "English",
                language = "en",
                codec = "subrip",
                forced = false,
            ),
        )

        val resolution = resolveTvFreshSubtitlePreference(
            preference = encodeSubtitleIdentityPreference(savedIdentity),
            catalogTracks = emptyList(),
            hydratedRows = listOf(
                row(
                    index = 3,
                    label = "English",
                    language = "en",
                    codec = "srt",
                    mediaTrackId = "media3:commentary",
                ),
                row(
                    index = 4,
                    label = "English",
                    language = "en",
                    codec = "srt",
                    mediaTrackId = "media3:english",
                ),
            ),
        )

        assertEquals(savedIdentity, resolution?.identity)
        assertNull(resolution?.migratedPreference)
    }

    @Test
    fun `T87 typed Off fresh restore disables subtitles exactly once`() {
        val resolution = resolveTvFreshSubtitlePreference(
            preference = encodeSubtitleIdentityPreference(SubtitleIdentity.Off),
            catalogTracks = emptyList(),
            hydratedRows = emptyList(),
        )

        assertEquals(SubtitleIdentity.Off, resolution?.identity)
        assertNull(resolution?.migratedPreference)
    }

    @Test
    fun `T88 fresh restore metadata mismatch misses while player-only local intent defers`() {
        val typedSidecar = encodeSubtitleIdentityPreference(
            SubtitleIdentity.ServerSidecar(
                serverIndex = 0,
                media = media(
                    label = "English full",
                    language = "en",
                    codec = "subrip",
                    forced = false,
                ),
            ),
        )
        assertNull(
            resolveTvFreshSubtitlePreference(
                preference = typedSidecar,
                catalogTracks = listOf(external("French full", "fra", "srt")),
                hydratedRows = emptyList(),
            ),
        )

        val ambiguousLocal = encodeSubtitleIdentityPreference(
            SubtitleIdentity.LocalMedia3(
                media(
                    label = "English",
                    language = "en",
                    codec = "subrip",
                    forced = false,
                ),
            ),
        )
        assertEquals(
            decodeSubtitleIdentityPreference(ambiguousLocal),
            resolveTvFreshSubtitlePreference(
                preference = ambiguousLocal,
                catalogTracks = emptyList(),
                hydratedRows = listOf(
                    row(3, "English", "en", "srt", mediaTrackId = "media3:a"),
                    row(4, "English", "en", "srt", mediaTrackId = "media3:b"),
                ),
            )?.identity,
            "Server rows carrying media IDs are not proof that the player-only track catalog is mounted.",
        )
    }

    private fun external(
        title: String,
        language: String,
        codec: String,
    ) = SubtitleTrack(
        codec = codec,
        language = language,
        title = title,
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

    private fun row(
        index: Int,
        label: String,
        language: String,
        codec: String,
        source: String? = null,
        downloadId: Int? = null,
        mediaTrackId: String? = null,
    ) = PlayerSubtitleInfo(
        index = index,
        language = language,
        codec = codec,
        label = label,
        source = source,
        forced = false,
        url = "",
        downloadId = downloadId,
        mediaTrackId = mediaTrackId,
    )

    private fun downloadedRow(index: Int, downloadId: Int): PlayerSubtitleInfo =
        row(
            index = index,
            label = "English",
            language = "en",
            codec = "vtt",
            source = "downloaded",
            downloadId = downloadId,
            mediaTrackId = "silo-downloaded-subtitle:$downloadId",
        )

    private fun media(
        trackId: String? = null,
        label: String? = null,
        language: String? = null,
        codec: String? = null,
        forced: Boolean? = null,
    ) = SubtitleMediaIdentity(
        trackId = trackId,
        label = label,
        language = language,
        codecFamily = codec,
        forced = forced,
    )
}
