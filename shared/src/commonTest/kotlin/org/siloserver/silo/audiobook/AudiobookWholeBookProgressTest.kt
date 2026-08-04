package org.siloserver.silo.audiobook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AudiobookWholeBookProgressTest {

    // Three parts: 100s, 200s, 150s → offsets 0, 100, 300; whole book 450.
    private val first = AudioPlaybackTrack(index = 0, fileId = 10, durationSeconds = 100.0, startOffsetSeconds = 0.0)
    private val second = AudioPlaybackTrack(index = 1, fileId = 11, durationSeconds = 200.0, startOffsetSeconds = 100.0)
    private val third = AudioPlaybackTrack(index = 2, fileId = 12, durationSeconds = 150.0, startOffsetSeconds = 300.0)

    private val timeline = AudiobookTimeline(
        tracks = listOf(first, second, third),
        chapters = emptyList(),
        totalSeconds = 450.0,
    )

    private fun offline(track: AudioPlaybackTrack) = WholeBookProgressMode.OfflinePart(timeline, track)

    @Test
    fun onlinePlaybackPassesGlobalPositionAndDurationThrough() {
        val progress = wholeBookProgress(WholeBookProgressMode.AlreadyGlobal, 250.0, 450.0)

        assertNotNull(progress)
        assertEquals(250.0, progress.positionSeconds)
        assertEquals(450.0, progress.totalSeconds)
    }

    @Test
    fun offlinePartLocalPositionBecomesGlobalAgainstTheWholeBookTotal() {
        // 40s into the second part is 140s into the book, not 40s.
        val progress = wholeBookProgress(offline(second), 40.0, 200.0)

        assertNotNull(progress)
        assertEquals(140.0, progress.positionSeconds)
        // The part's own 200s duration must never be recorded as the book total.
        assertEquals(450.0, progress.totalSeconds)
    }

    @Test
    fun aPositionPastThePartClampsToThePartEndAndNeverOvershootsIntoTheNext() {
        // The second part ends at 300s; a local overshoot must not land in the third.
        val progress = wholeBookProgress(offline(second), 999.0, 200.0)

        assertNotNull(progress)
        assertEquals(300.0, progress.positionSeconds)
    }

    @Test
    fun aNegativePartLocalPositionClampsToThePartStart() {
        val progress = wholeBookProgress(offline(third), -25.0, 150.0)

        assertNotNull(progress)
        assertEquals(300.0, progress.positionSeconds)
    }

    @Test
    fun theStartOfALaterPartIsAWritablePositionBecauseItIsGloballyPositive() {
        val progress = wholeBookProgress(offline(second), 0.0, 200.0)

        assertNotNull(progress)
        assertEquals(100.0, progress.positionSeconds)
    }

    @Test
    fun theVeryStartOfTheBookIsNotWritten() {
        // Global zero has nothing to resume to, and writing it would clobber a
        // real position recorded elsewhere.
        assertNull(wholeBookProgress(offline(first), 0.0, 100.0))
        assertNull(wholeBookProgress(WholeBookProgressMode.AlreadyGlobal, 0.0, 450.0))
        assertNull(wholeBookProgress(WholeBookProgressMode.AlreadyGlobal, -5.0, 450.0))
    }

    @Test
    fun playbackWithNoTimelineIsNeverWrittenAgainstTheBook() {
        assertNull(wholeBookProgress(WholeBookProgressMode.Unmappable, 40.0, 200.0))
    }

    @Test
    fun aSinglePartBookConvertsToItself() {
        val single = AudioPlaybackTrack(index = 0, fileId = 10, durationSeconds = 120.0, startOffsetSeconds = 0.0)
        val singleTimeline = AudiobookTimeline(listOf(single), emptyList(), 120.0)

        val progress = wholeBookProgress(WholeBookProgressMode.OfflinePart(singleTimeline, single), 30.0, 120.0)

        assertNotNull(progress)
        assertEquals(30.0, progress.positionSeconds)
        assertEquals(120.0, progress.totalSeconds)
    }

    @Test
    fun eachModeIsEvaluatedIndependentlySoAStaleMappingCannotSurviveAModeChange() {
        val mapped = wholeBookProgress(offline(third), 10.0, 150.0)
        assertNotNull(mapped)
        assertEquals(310.0, mapped.positionSeconds)

        // The same position under an unmappable mode must not reuse the mapping.
        assertNull(wholeBookProgress(WholeBookProgressMode.Unmappable, 10.0, 150.0))
    }
}
