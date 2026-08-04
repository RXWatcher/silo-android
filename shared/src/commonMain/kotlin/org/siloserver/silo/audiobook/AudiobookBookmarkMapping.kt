package org.siloserver.silo.audiobook

import org.siloserver.silo.model.audiobook.BookmarkPositionSpace

/*
 * Bookmarks are durable and scoped to the book, so — like the resume position —
 * they are stored in whole-book time wherever that can be established, and
 * converted on the way in and back out again. Where it cannot be established
 * they are stored exactly as recorded and marked as such, because a guess that
 * a later session acts on is worse than an honest unknown.
 *
 * The conversion is the same one [wholeBookProgress] performs, but the rules
 * around it differ, which is why this is not folded into that function:
 *
 * - Zero is a legitimate bookmark. "The very start of the book" is a place a
 *   listener can deliberately mark, whereas a *resume* position of zero means
 *   there is nothing to resume and must not clobber a real position.
 * - There is no total to pair it with. A bookmark is a point, not progress.
 *
 * Reading is not symmetrical with writing, because offline playback can only
 * reach the one part it has on disk — so a stored position that converts
 * perfectly well may still be somewhere this session cannot go.
 *
 * Neither is it symmetrical in time: bookmarks written before the coordinate
 * space was recorded carry no way to tell whole-book from part-local, so they
 * are read back exactly as they were written rather than reinterpreted.
 */

/**
 * What to record for a bookmark taken at [positionSeconds] in the current
 * playback space.
 */
data class BookmarkPosition(
    val seconds: Double,
    val space: BookmarkPositionSpace,
    /** The part the bookmark was dropped in, when playback knows which. */
    val sourceFileId: Int?,
)

/**
 * The position to store for a bookmark taken at [positionSeconds], or `null`
 * when this playback cannot be expressed against the book at all.
 *
 * Only a position whose relationship to the book is actually known is stamped
 * whole-book. Offline playback of a file with an unknown layout records what it
 * has, unconverted: the number is right in the space that produced it, and a
 * later session that does know the layout must not act on it as though it were
 * whole-book time.
 */
fun bookmarkPositionFor(
    mode: WholeBookProgressMode,
    positionSeconds: Double,
    /**
     * The part file playing right now, when the caller knows it. Online
     * multi-part playback does: its position is already whole-book, but the
     * part still matters, because a bookmark dropped at a part boundary is
     * later read back by an offline session that can only reach one part.
     * Null for single-file playback, which has no parts to disambiguate.
     */
    activeFileId: Int? = null,
): BookmarkPosition? {
    val position = positionSeconds.coerceAtLeast(0.0)
    return when (mode) {
        WholeBookProgressMode.AlreadyGlobal -> BookmarkPosition(
            seconds = position,
            space = BookmarkPositionSpace.WholeBook,
            sourceFileId = activeFileId,
        )

        is WholeBookProgressMode.UnknownOfflineLayout -> BookmarkPosition(
            seconds = position,
            space = BookmarkPositionSpace.AsRecorded,
            // The position cannot be placed in the book, but the file it came
            // from is known — and that is what lets a later session tell "this
            // number is local to the file I am playing" from "this number is
            // local to some other file", which is the difference between a
            // usable bookmark and a seek into the wrong content.
            sourceFileId = mode.fileId ?: activeFileId,
        )

        // globalTimeFor clamps into the track's own span, so a converted
        // bookmark can never land in a neighbouring part.
        is WholeBookProgressMode.OfflinePart -> BookmarkPosition(
            seconds = mode.timeline.globalTimeFor(position, mode.track),
            space = BookmarkPositionSpace.WholeBook,
            sourceFileId = mode.track.fileId,
        )

        WholeBookProgressMode.Unmappable -> null
    }
}

/** What to do with a stored bookmark in the current playback space. */
sealed interface BookmarkSeekTarget {
    /**
     * Seek to this many seconds, in whatever space the player is currently
     * running. Whole-book and part-local collapse into one case on purpose:
     * `seekTo` already branches on whether a timeline is loaded, and that
     * branch agrees with the mode — offline part playback has no timeline and
     * takes part-local seconds, everything else takes whole-book seconds. One
     * case means there is no way to hand a number to the wrong branch.
     */
    data class Seek(val seconds: Double) : BookmarkSeekTarget

    /**
     * The bookmark belongs to a part this session cannot play. Seeking anyway
     * would clamp into the part on disk and land at an arbitrary point in the
     * wrong chapter, which is worse than not moving.
     */
    data object OutOfReach : BookmarkSeekTarget
}

/**
 * Where to seek for a stored bookmark.
 *
 * [BookmarkPositionSpace.AsRecorded] positions are passed straight through,
 * which is what the playback that produced them would do. Reinterpreting them
 * only makes them worse: a part-local number read as whole-book is either
 * rejected as another part's or offset a second time into the wrong place, and
 * the value alone cannot say which it is.
 *
 * [sourceFileId] settles the part boundary. A part's last instant and the next
 * part's first are the same number, so position alone cannot tell a bookmark
 * dropped at the end of the part on disk from one dropped at the start of the
 * part that follows. With the origin recorded the question does not arise;
 * without it, ownership wins — refusing a reachable bookmark merely annoys,
 * while accepting an unreachable one seeks to the wrong content.
 */
fun bookmarkSeekTarget(
    mode: WholeBookProgressMode,
    space: BookmarkPositionSpace,
    bookmarkSeconds: Double,
    sourceFileId: Int? = null,
): BookmarkSeekTarget {
    val position = bookmarkSeconds.coerceAtLeast(0.0)
    if (space == BookmarkPositionSpace.AsRecorded) {
        // Unconverted seconds are only meaningful against the file that
        // produced them. With no recorded origin there is nothing to check and
        // pass-through is the old behaviour; with one, a different file today
        // means this number describes somewhere else entirely, and seeking it
        // is the wrong-content failure the ownership rule exists to avoid.
        // Both offline modes know which file is playing; only OfflinePart also
        // knows where it sits in the book.
        val currentFileId = when (mode) {
            is WholeBookProgressMode.OfflinePart -> mode.track.fileId
            is WholeBookProgressMode.UnknownOfflineLayout -> mode.fileId
            else -> null
        }
        return if (sourceFileId != null && currentFileId != null && sourceFileId != currentFileId) {
            BookmarkSeekTarget.OutOfReach
        } else {
            BookmarkSeekTarget.Seek(position)
        }
    }

    return when (mode) {
        WholeBookProgressMode.AlreadyGlobal -> BookmarkSeekTarget.Seek(position)

        // A whole-book bookmark cannot be placed against a book whose layout
        // this session does not know.
        is WholeBookProgressMode.UnknownOfflineLayout -> BookmarkSeekTarget.OutOfReach

        is WholeBookProgressMode.OfflinePart -> {
            val belongsHere = if (sourceFileId != null) {
                sourceFileId == mode.track.fileId
            } else {
                mode.timeline.trackIndexAt(position) == mode.track.index
            }
            if (belongsHere) {
                BookmarkSeekTarget.Seek(mode.timeline.localTimeFor(position, mode.track))
            } else {
                BookmarkSeekTarget.OutOfReach
            }
        }

        WholeBookProgressMode.Unmappable -> BookmarkSeekTarget.OutOfReach
    }
}
