package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackSelectionFingerprintTest {

    @Test
    fun downloadedSubtitleIdentityRoundTripsThroughVersionedPreference() {
        val identity = SubtitleIdentity.Downloaded(
            downloadId = 312,
            media = SubtitleMediaIdentity(
                trackId = "silo-downloaded-subtitle:312",
                label = "English | SDH",
                language = "eng",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = true,
            ),
        )

        val encoded = encodeSubtitleIdentityPreference(identity)

        assertEquals(identity, decodeSubtitleIdentityPreference(encoded))
    }

    @Test
    fun localMedia3SubtitleIdentityRoundTripsExactTrackMetadata() {
        val identity = SubtitleIdentity.LocalMedia3(
            SubtitleMediaIdentity(
                trackId = "decoder:text:9",
                label = "Commentary",
                language = "fr-CA",
                codecFamily = "vtt",
                forced = null,
                hearingImpaired = false,
            ),
        )

        val encoded = encodeSubtitleIdentityPreference(identity)

        assertEquals(identity, decodeSubtitleIdentityPreference(encoded))
        assertNull(decodeSubtitleIdentityPreference(subtitleTrackFingerprint(
            PlayerSubtitleInfo(index = 1, label = "Legacy", url = "/1.vtt"),
        )))
        assertNull(decodeSubtitleIdentityPreference("silo-subtitle-v2:{broken"))
    }

    @Test
    fun resolvesAudioFingerprintBackToTrackOrdinal() {
        val tracks = listOf(
            AudioTrack(index = 0, codec = "aac", language = "eng", title = "Stereo"),
            AudioTrack(index = 1, codec = "eac3", language = "eng", title = "5.1"),
        )
        val saved = audioTrackFingerprint(tracks[1])

        assertEquals(1, resolveAudioTrackOrdinal(tracks, saved))
    }

    @Test
    fun resolvesSubtitleOffAndTrackFingerprints() {
        val tracks = listOf(
            SubtitleTrack(index = 0, codec = "srt", language = "eng", title = "English"),
            SubtitleTrack(index = 3, codec = "ass", language = "eng", title = "Signs", forced = true),
        )

        assertEquals(-1, resolveSubtitleTrackOrdinal(tracks, SUBTITLE_OFF_FINGERPRINT))
        assertEquals(1, resolveSubtitleTrackOrdinal(tracks, subtitleTrackFingerprint(tracks[1])))
    }

    @Test
    fun playerSubtitleInfoUsesSameSubtitleFingerprintShape() {
        val catalog = SubtitleTrack(index = 2, codec = "srt", language = "eng", title = "English CC", forced = true)
        val mounted = PlayerSubtitleInfo(
            index = 2,
            codec = "srt",
            language = "eng",
            label = "English CC",
            forced = true,
            url = "/stream/subtitles/2.vtt",
        )

        assertEquals(subtitleTrackFingerprint(catalog), subtitleTrackFingerprint(mounted))
    }

    @Test
    fun resolvesMountedSubtitleFingerprintBackToMountedOrdinal() {
        // The mobile player records selections against the mounted list, so
        // they must round-trip against the SAME list even when the mounted
        // ordinals differ from the catalog demux indices.
        val mounted = listOf(
            PlayerSubtitleInfo(index = 0, codec = "srt", language = "eng", label = "English", url = "/s/0.vtt"),
            PlayerSubtitleInfo(index = 1, codec = "ass", language = "eng", label = "Signs", forced = true, url = "/s/1.vtt"),
        )

        assertEquals(-1, resolveMountedSubtitleOrdinal(mounted, SUBTITLE_OFF_FINGERPRINT))
        assertEquals(1, resolveMountedSubtitleOrdinal(mounted, subtitleTrackFingerprint(mounted[1])))
        assertNull(resolveMountedSubtitleOrdinal(mounted, null))
    }

    @Test
    fun blankOrUnknownFingerprintDoesNotResolve() {
        val tracks = listOf(AudioTrack(index = 0, codec = "aac", language = "eng", title = "Stereo"))

        assertNull(resolveAudioTrackOrdinal(tracks, null))
        assertNull(resolveAudioTrackOrdinal(tracks, ""))
        assertNull(resolveAudioTrackOrdinal(tracks, "missing"))
    }
}
