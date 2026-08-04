package org.siloserver.silo.audiobook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Offline there is no server detail to say whether a downloaded file is a whole
 * book or one part of one, and that decides whether its clock is the book's.
 * The layout is captured at download time so this question has an answer.
 */
class OfflineWholeBookModeTest {

    private fun mode(
        partCount: Int? = 3,
        partIndex: Int? = 1,
        offset: Double? = 100.0,
        duration: Double? = 200.0,
        total: Double? = 450.0,
        fileId: Int? = 11,
    ) = offlineWholeBookMode(partCount, partIndex, offset, duration, total, fileId)

    @Test
    fun aDownloadWithNoRecordedLayoutStaysAmbiguous() {
        // Downloads made before the layout was recorded. Guessing either way
        // writes something a later session acts on.
        assertEquals(
            WholeBookProgressMode.UnknownOfflineLayout(11),
            mode(partCount = null),
        )
    }

    @Test
    fun aSinglePartBookNeedsNoConversion() {
        // One part means file time already is book time — the case that would
        // otherwise lose progress if ambiguity were treated as unmappable.
        assertEquals(WholeBookProgressMode.AlreadyGlobal, mode(partCount = 1))
    }

    @Test
    fun aKnownPartOfAMultiPartBookConvertsWithoutServerDetail() {
        val result = mode()

        assertIs<WholeBookProgressMode.OfflinePart>(result)
        // 40s into this part is 140s into the book.
        assertEquals(140.0, result.timeline.globalTimeFor(40.0, result.track))
        // And the book's total, not the part's, is what progress is measured
        // against.
        assertEquals(450.0, result.timeline.totalSeconds)
    }

    @Test
    fun theBuiltTimelineDescribesOnlyThePartOnDisk() {
        val result = mode()
        assertIs<WholeBookProgressMode.OfflinePart>(result)

        // The regression this pins. A one-track timeline cannot be asked
        // "which track plays here" — trackIndexAt clamps anything it does not
        // recognise onto the last track, so it would claim the whole book.
        assertEquals(result.track.index, result.timeline.trackIndexAt(20.0))
        // partContains is the question that survives a partial timeline.
        assertTrue(result.timeline.partContains(result.track, 140.0))
        assertTrue(!result.timeline.partContains(result.track, 20.0))
        assertTrue(!result.timeline.partContains(result.track, 400.0))
    }

    @Test
    fun theFinalPartKeepsEverythingPastTheEnd() {
        val result = mode(partIndex = 2, offset = 300.0, duration = 150.0)
        assertIs<WholeBookProgressMode.OfflinePart>(result)

        assertTrue(result.timeline.partContains(result.track, 450.0))
        assertTrue(result.timeline.partContains(result.track, 999.0))
    }

    @Test
    fun theFinalPartKeepsTheTailWhenTheServerTotalExceedsItsParts() {
        // buildAudiobookTimeline takes total = max(server total, stitched
        // parts), so the last part's end can fall short of the book total.
        // Deciding finality by comparing the two would refuse everything in
        // that gap — which trackIndexAt hands to the last part.
        val result = mode(partIndex = 2, offset = 300.0, duration = 150.0, total = 500.0)
        assertIs<WholeBookProgressMode.OfflinePart>(result)

        assertTrue(result.timeline.partContains(result.track, 470.0))
    }

    @Test
    fun aMiddlePartDoesNotKeepTheTail() {
        // The mirror of the above: one track is present either way, so
        // finality has to come from the recorded part index, not from what the
        // timeline happens to contain.
        val result = mode()
        assertIs<WholeBookProgressMode.OfflinePart>(result)

        assertTrue(!result.timeline.partContains(result.track, 470.0))
    }

    @Test
    fun thePartsOwnLengthIsUsedNotTheFilesFallbackDuration() {
        // The sidecar's durationSeconds falls back to the WHOLE BOOK's total
        // when a file has no probed duration. Using that as the part's span
        // would stretch this part across every part after it.
        val result = mode(duration = 200.0)
        assertIs<WholeBookProgressMode.OfflinePart>(result)

        assertTrue(result.timeline.partContains(result.track, 299.0))
        assertTrue(!result.timeline.partContains(result.track, 301.0))
    }

    @Test
    fun aPartCountWithoutTheNumbersToConvertByStaysAmbiguous() {
        // Half a layout is not a layout: without the offset there is nothing to
        // convert through, and asserting otherwise would corrupt the position.
        assertEquals(WholeBookProgressMode.UnknownOfflineLayout(11), mode(offset = null))
        assertEquals(WholeBookProgressMode.UnknownOfflineLayout(11), mode(total = null))
        assertEquals(WholeBookProgressMode.UnknownOfflineLayout(11), mode(duration = null))
        assertEquals(WholeBookProgressMode.UnknownOfflineLayout(11), mode(duration = 0.0))
        assertEquals(WholeBookProgressMode.UnknownOfflineLayout(11), mode(partIndex = null))
        assertEquals(WholeBookProgressMode.UnknownOfflineLayout(null), mode(fileId = null))
    }

    // ── Parity with a full, server-built timeline ────────────────────────

    @Test
    fun partContainsMatchesTrackIndexAtOnAServerBuiltTimeline() {
        // partContains replaced trackIndexAt for the ownership question so it
        // would survive a one-track timeline. It must not have changed the
        // answer for a complete one — including the tail a server total leaves
        // beyond the stitched parts, which trackIndexAt hands to the last part.
        val a = AudioPlaybackTrack(index = 0, fileId = 1, durationSeconds = 100.0, startOffsetSeconds = 0.0)
        val b = AudioPlaybackTrack(index = 1, fileId = 2, durationSeconds = 200.0, startOffsetSeconds = 100.0)
        val c = AudioPlaybackTrack(index = 2, fileId = 3, durationSeconds = 150.0, startOffsetSeconds = 300.0)
        val full = AudiobookTimeline(
            tracks = listOf(a, b, c),
            chapters = emptyList(),
            // Deliberately beyond the stitched 450 the parts add up to.
            totalSeconds = 500.0,
        )

        val probes = listOf(0.0, 50.0, 99.9, 100.0, 250.0, 299.9, 300.0, 449.9, 450.0, 480.0, 999.0)
        for (position in probes) {
            val owner = full.trackIndexAt(position)
            for (track in full.tracks) {
                assertEquals(
                    track.index == owner,
                    full.partContains(track, position),
                    "position=$position track=${track.index}",
                )
            }
        }
    }
}
