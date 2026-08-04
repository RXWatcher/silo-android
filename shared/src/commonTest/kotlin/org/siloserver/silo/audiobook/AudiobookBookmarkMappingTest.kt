package org.siloserver.silo.audiobook

import org.siloserver.silo.model.audiobook.BookmarkPositionSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudiobookBookmarkMappingTest {

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

    // ── Writing ──────────────────────────────────────────────────────────

    @Test
    fun onlineBookmarkStoresTheGlobalPositionUnchanged() {
        val stored = bookmarkPositionFor(WholeBookProgressMode.AlreadyGlobal, 250.0)
        assertEquals(250.0, stored?.seconds)
        assertEquals(BookmarkPositionSpace.WholeBook, stored?.space)
    }

    @Test
    fun offlineBookmarkStoresWholeBookTimeNotPartLocalTime() {
        // The regression this pins: 40s into part two is 140s into the book.
        // Storing 40.0 made the bookmark point at part one when reopened online.
        val stored = bookmarkPositionFor(offline(second), 40.0)
        assertEquals(140.0, stored?.seconds)
        assertEquals(BookmarkPositionSpace.WholeBook, stored?.space)
        // The originating part is recorded so the shared boundary between two
        // parts is never ambiguous on the way back.
        assertEquals(second.fileId, stored?.sourceFileId)
    }

    @Test
    fun offlineBookmarkInTheThirdPartClearsBothPrecedingParts() {
        assertEquals(310.0, bookmarkPositionFor(offline(third), 10.0)?.seconds)
    }

    @Test
    fun theStartOfTheBookIsAStorableBookmark() {
        // Unlike a resume position, where zero means "nothing to resume", zero
        // is a place a listener can deliberately mark.
        assertEquals(0.0, bookmarkPositionFor(WholeBookProgressMode.AlreadyGlobal, 0.0)?.seconds)
        assertEquals(100.0, bookmarkPositionFor(offline(second), 0.0)?.seconds)
    }

    @Test
    fun anUnmappableBookRefusesToStoreABookmark() {
        assertNull(bookmarkPositionFor(WholeBookProgressMode.Unmappable, 40.0))
    }

    @Test
    fun onlineMultiPartPlaybackRecordsWhichPartTheBookmarkCameFrom() {
        // The position is already whole-book, but the part still matters: an
        // offline session reading it back can only reach one part, and a
        // boundary position alone cannot say which side it came from.
        val stored = bookmarkPositionFor(
            mode = WholeBookProgressMode.AlreadyGlobal,
            positionSeconds = 300.0,
            activeFileId = second.fileId,
        )
        assertEquals(300.0, stored?.seconds)
        assertEquals(second.fileId, stored?.sourceFileId)
        // And that makes it reachable from part two, which ownership alone
        // would have refused.
        assertEquals(
            BookmarkSeekTarget.Seek(200.0),
            seek(offline(second), 300.0, sourceFileId = stored?.sourceFileId),
        )
    }

    @Test
    fun singleFilePlaybackRecordsNoPart() {
        assertNull(
            bookmarkPositionFor(WholeBookProgressMode.AlreadyGlobal, 250.0)?.sourceFileId,
        )
    }

    @Test
    fun anUnknownOfflineLayoutRecordsWhatItHasWithoutClaimingItIsWholeBook() {
        // The regression this pins. Playback here may be part-local or
        // whole-book and nothing offline says which, so stamping WholeBook
        // would let a LATER session — one that does have the timeline —
        // convert or reject a number that was never global. The old code
        // stored the same raw seconds, so pass-through preserves it exactly.
        val stored = bookmarkPositionFor(WholeBookProgressMode.UnknownOfflineLayout(null), 40.0)
        assertEquals(40.0, stored?.seconds)
        assertEquals(BookmarkPositionSpace.AsRecorded, stored?.space)
        assertNull(stored?.sourceFileId)
    }

    @Test
    fun aWholeBookBookmarkCannotBePlacedAgainstAnUnknownLayout() {
        assertEquals(
            BookmarkSeekTarget.OutOfReach,
            seek(WholeBookProgressMode.UnknownOfflineLayout(second.fileId), 140.0),
        )
    }

    @Test
    fun anUnknownOfflineLayoutStillRecordsWhichFileItPlayed() {
        val stored = bookmarkPositionFor(
            mode = WholeBookProgressMode.UnknownOfflineLayout(second.fileId),
            positionSeconds = 40.0,
        )
        assertEquals(BookmarkPositionSpace.AsRecorded, stored?.space)
        assertEquals(second.fileId, stored?.sourceFileId)
    }

    @Test
    fun anAsRecordedBookmarkIsRefusedWhenAnotherFileIsPlaying() {
        // Unconverted seconds only mean anything against the file that produced
        // them. Passing them through regardless would seek raw part-two time
        // into part three — the wrong-content failure the whole boundary rule
        // exists to avoid.
        assertEquals(
            BookmarkSeekTarget.OutOfReach,
            seek(offline(third), 40.0, BookmarkPositionSpace.AsRecorded, sourceFileId = second.fileId),
        )
        assertEquals(
            BookmarkSeekTarget.Seek(40.0),
            seek(offline(second), 40.0, BookmarkPositionSpace.AsRecorded, sourceFileId = second.fileId),
        )
    }

    @Test
    fun anAsRecordedBookmarkIsRefusedWhenAnotherFilePlaysUnderAnUnknownLayout() {
        // The gap this pins: an unknown layout knows which file it is playing
        // even though it cannot place it in the book. Without that, an
        // unconverted position from one download passed straight through into
        // a different one.
        assertEquals(
            BookmarkSeekTarget.OutOfReach,
            seek(
                WholeBookProgressMode.UnknownOfflineLayout(third.fileId),
                40.0,
                BookmarkPositionSpace.AsRecorded,
                sourceFileId = second.fileId,
            ),
        )
        assertEquals(
            BookmarkSeekTarget.Seek(40.0),
            seek(
                WholeBookProgressMode.UnknownOfflineLayout(second.fileId),
                40.0,
                BookmarkPositionSpace.AsRecorded,
                sourceFileId = second.fileId,
            ),
        )
    }

    @Test
    fun anAsRecordedBookmarkWithNoRecordedFileStillPassesThrough() {
        // Bookmarks written before the origin was recorded have nothing to
        // check against, and pass-through is what they were written for.
        assertEquals(
            BookmarkSeekTarget.Seek(40.0),
            seek(offline(third), 40.0, BookmarkPositionSpace.AsRecorded),
        )
    }

    @Test
    fun anAsRecordedBookmarkSurvivesAnUnknownLayoutUnchanged() {
        assertEquals(
            BookmarkSeekTarget.Seek(40.0),
            seek(WholeBookProgressMode.UnknownOfflineLayout(second.fileId), 40.0, BookmarkPositionSpace.AsRecorded),
        )
    }

    // ── Reading ──────────────────────────────────────────────────────────

    private fun seek(
        mode: WholeBookProgressMode,
        seconds: Double,
        space: BookmarkPositionSpace = BookmarkPositionSpace.WholeBook,
        sourceFileId: Int? = null,
    ) = bookmarkSeekTarget(mode, space, seconds, sourceFileId)

    @Test
    fun onlineSeeksInWholeBookTime() {
        assertEquals(
            BookmarkSeekTarget.Seek(250.0),
            seek(WholeBookProgressMode.AlreadyGlobal, 250.0),
        )
    }

    @Test
    fun offlineSeeksPartLocallyWhenTheBookmarkIsInThePartOnDisk() {
        // 140s whole-book is 40s into the part being played offline.
        assertEquals(BookmarkSeekTarget.Seek(40.0), seek(offline(second), 140.0))
    }

    @Test
    fun offlineRefusesABookmarkBelongingToAnotherPart() {
        // Seeking anyway clamps into the downloaded part and lands at an
        // arbitrary point in the wrong chapter.
        assertEquals(BookmarkSeekTarget.OutOfReach, seek(offline(second), 20.0))
        assertEquals(BookmarkSeekTarget.OutOfReach, seek(offline(second), 350.0))
    }

    @Test
    fun theSharedPartBoundaryIsResolvedByTheOriginatingPart() {
        // 300.0 is simultaneously part two's last instant and part three's
        // first, and position alone cannot tell them apart. The recorded part
        // does: only the bookmark that came from part two is reachable while
        // part two is the file on disk.
        assertEquals(
            BookmarkSeekTarget.Seek(200.0),
            seek(offline(second), 300.0, sourceFileId = second.fileId),
        )
        assertEquals(
            BookmarkSeekTarget.OutOfReach,
            seek(offline(second), 300.0, sourceFileId = third.fileId),
        )
    }

    @Test
    fun withNoRecordedPartTheBoundaryFallsToOwnership() {
        // Refusing a reachable bookmark merely annoys; accepting an
        // unreachable one seeks to the wrong content. So the ambiguous
        // instant goes to the part that owns it.
        assertEquals(BookmarkSeekTarget.OutOfReach, seek(offline(second), 300.0))
        assertEquals(BookmarkSeekTarget.Seek(0.0), seek(offline(second), 100.0))
    }

    @Test
    fun aBookmarkAtThePartEndRoundTripsFromThatPart() {
        val stored = bookmarkPositionFor(offline(second), 200.0)
        assertEquals(300.0, stored?.seconds)
        assertEquals(
            BookmarkSeekTarget.Seek(200.0),
            seek(offline(second), stored!!.seconds, sourceFileId = stored.sourceFileId),
        )
    }

    @Test
    fun anUnmappableBookCannotReachAnyBookmark() {
        assertEquals(
            BookmarkSeekTarget.OutOfReach,
            seek(WholeBookProgressMode.Unmappable, 140.0),
        )
    }

    @Test
    fun writingThenReadingInTheSameOfflinePartRoundTrips() {
        val stored = bookmarkPositionFor(offline(third), 75.0)
        assertEquals(375.0, stored?.seconds)
        assertEquals(
            BookmarkSeekTarget.Seek(75.0),
            seek(offline(third), stored!!.seconds, sourceFileId = stored.sourceFileId),
        )
    }

    // ── Legacy bookmarks ─────────────────────────────────────────────────

    @Test
    fun asRecordedBookmarksArePassedThroughOfflineExactlyAsBefore() {
        // The regression this pins. A bookmark dropped offline at 40s into part
        // two was stored as a bare 40.0. Reading it as whole-book puts it in
        // part one, so the part on disk refuses it and the jump silently does
        // nothing — worse than the old behaviour, which seeked to local 40.
        assertEquals(
            BookmarkSeekTarget.Seek(40.0),
            seek(offline(second), 40.0, BookmarkPositionSpace.AsRecorded),
        )
    }

    @Test
    fun asRecordedBookmarksArePassedThroughOnlineExactlyAsBefore() {
        assertEquals(
            BookmarkSeekTarget.Seek(250.0),
            seek(WholeBookProgressMode.AlreadyGlobal, 250.0, BookmarkPositionSpace.AsRecorded),
        )
    }

    @Test
    fun asRecordedBookmarksAreNotRefusedByAnUnmappableBook() {
        // Refusing here would break a bookmark that used to work: without a
        // timeline the old code seeked straight to the stored number, and that
        // number was written in the same space it is being read in.
        assertEquals(
            BookmarkSeekTarget.Seek(140.0),
            seek(WholeBookProgressMode.Unmappable, 140.0, BookmarkPositionSpace.AsRecorded),
        )
    }
}
