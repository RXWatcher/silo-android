package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleMountResolverTest {

    @Test
    fun serverSidecarResolvesExactStableIdAcrossSameLabelTracks() {
        val tracks = listOf(
            track(index = 3, trackId = "silo-subtitle:3", label = "Server subtitle"),
            track(index = 4, trackId = "silo-subtitle:4", label = "Server subtitle"),
        )

        val match = resolveMountedSubtitle(SubtitleIdentity.ServerSidecar(4), tracks)

        assertEquals(4, match?.track?.index)
        assertEquals("silo-subtitle:4", match?.track?.trackId)
    }

    @Test
    fun exactLocalIdHasGlobalPriorityOverEarlierMetadataMatch() {
        val identity = SubtitleIdentity.LocalMedia3(
            media(
                trackId = "decoder-text-9",
                label = "English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val tracks = listOf(
            track(
                index = 0,
                trackId = "decoder-text-8",
                label = "English",
                language = "en",
                codec = "text/vtt",
                forced = false,
                hearingImpaired = false,
            ),
            track(
                index = 1,
                trackId = "decoder-text-9",
                label = "Different runtime label",
                language = "fr",
                codec = "application/x-subrip",
                forced = true,
                hearingImpaired = true,
            ),
        )

        assertEquals(1, resolveMountedSubtitle(identity, tracks)?.track?.index)
    }

    @Test
    fun localIdChangeFallsBackToCompleteTypedMetadata() {
        val identity = SubtitleIdentity.LocalMedia3(
            media(
                trackId = "old-decoder-id",
                label = "English SDH",
                language = "en",
                codecFamily = "subrip",
                forced = false,
                hearingImpaired = true,
            ),
        )
        val tracks = listOf(
            track(
                index = 2,
                trackId = "new-decoder-id",
                label = "English SDH",
                language = "EN",
                codec = "application/x-subrip",
                forced = false,
                hearingImpaired = true,
            ),
        )

        assertEquals(2, resolveMountedSubtitle(identity, tracks)?.track?.index)
    }

    @Test
    fun embeddedPgsFallbackSeparatesForcedAndFullDuplicates() {
        val full = track(
            index = 5,
            trackId = null,
            label = "English",
            language = "eng",
            codec = "application/pgs",
            forced = false,
            hearingImpaired = false,
        )
        val forced = full.copy(index = 6, forced = true)
        val identity = SubtitleIdentity.Embedded(
            serverIndex = 7,
            media = media(
                label = "English",
                language = "en",
                codecFamily = "pgs",
                forced = true,
                hearingImpaired = false,
            ),
        )

        assertEquals(6, resolveMountedSubtitle(identity, listOf(full, forced))?.track?.index)
    }

    @Test
    fun metadataFallbackSeparatesHearingImpairedDuplicate() {
        val identity = SubtitleIdentity.LocalMedia3(
            media(
                label = "English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = true,
            ),
        )
        val tracks = listOf(
            track(1, null, "English", "en", "text/vtt", false, false),
            track(2, null, "English", "en", "text/vtt", false, true),
        )

        assertEquals(2, resolveMountedSubtitle(identity, tracks)?.track?.index)
    }

    @Test
    fun idLessLegacyTextResolvesByTypedMetadata() {
        val identity = SubtitleIdentity.LocalMedia3(
            media(
                language = "fr",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val tracks = listOf(
            track(0, null, "Français", "fr", "text/vtt", false, false),
        )

        assertEquals(0, resolveMountedSubtitle(identity, tracks)?.track?.index)
    }

    @Test
    fun embeddedBitmapAcceptsOrdinaryDecoderTrackId() {
        val identity = SubtitleIdentity.Embedded(
            serverIndex = 4,
            media = media(
                trackId = "7",
                language = "en",
                codecFamily = "pgs",
                forced = true,
            ),
        )
        val tracks = listOf(
            track(0, "8", "English", "en", "application/pgs", true, false),
            track(1, "7", "English", "en", "application/pgs", false, false),
        )

        assertEquals(1, resolveMountedSubtitle(identity, tracks)?.track?.index)
    }

    @Test
    fun missingLocalIdCannotFallBackToServerSidecarWithSameMetadata() {
        val identity = SubtitleIdentity.LocalMedia3(
            media(
                trackId = "decoder-text-2",
                label = "English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = false,
            ),
        )
        val tracks = listOf(
            track(
                index = 0,
                trackId = "silo-subtitle:4",
                label = "English",
                language = "en",
                codec = "text/vtt",
                forced = false,
                hearingImpaired = false,
            ),
        )

        assertNull(resolveMountedSubtitle(identity, tracks))
    }

    @Test
    fun displayLabelAloneNeverEstablishesIdentity() {
        val identity = SubtitleIdentity.LocalMedia3(media(label = "English"))

        assertNull(
            resolveMountedSubtitle(
                identity,
                listOf(track(index = 0, trackId = null, label = "English")),
            ),
        )
    }

    @Test
    fun offAndBurnInHaveNoMountedTrack() {
        val tracks = listOf(track(index = 0, trackId = "silo-subtitle:3"))

        assertNull(resolveMountedSubtitle(SubtitleIdentity.Off, tracks))
        assertNull(resolveMountedSubtitle(SubtitleIdentity.ServerBurnIn(3), tracks))
    }

    @Test
    fun stableArtifactIdUsesCombinedServerIndex() {
        assertEquals("silo-subtitle:0", subtitleArtifactTrackId(0))
        assertEquals("silo-subtitle:42", subtitleArtifactTrackId(42))
    }

    private fun track(
        index: Int,
        trackId: String?,
        label: String? = null,
        language: String? = null,
        codec: String? = null,
        forced: Boolean? = null,
        hearingImpaired: Boolean? = null,
    ): MountedSubtitleTrack = MountedSubtitleTrack(
        index = index,
        trackId = trackId,
        label = label,
        language = language,
        codec = codec,
        forced = forced,
        hearingImpaired = hearingImpaired,
    )

    private fun media(
        trackId: String? = null,
        label: String? = null,
        language: String? = null,
        codecFamily: String? = null,
        forced: Boolean? = null,
        hearingImpaired: Boolean? = null,
    ): SubtitleMediaIdentity = SubtitleMediaIdentity(
        trackId = trackId,
        label = label,
        language = language,
        codecFamily = codecFamily,
        forced = forced,
        hearingImpaired = hearingImpaired,
    )
}
