package org.siloserver.silo.model.audiobook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which clock [AudiobookBookmark.positionSeconds] is measured on.
 *
 * Recorded because it cannot be recovered afterwards. A multi-part audiobook
 * played offline runs on part-local time, so `150` may equally mean 150s into
 * the book or 150s into the third downloaded part, and nothing else stored on
 * the bookmark tells the two apart — `chapterTitle` is a label, not a position.
 */
@Serializable
enum class BookmarkPositionSpace {
    /**
     * Whole-book seconds, provably converted at drop time. Only stamped when
     * the position's relationship to the book was actually known.
     */
    @SerialName("whole_book")
    WholeBook,

    /**
     * Seconds in whatever space the player was running when the bookmark was
     * dropped, unconverted — because it could not be converted with certainty.
     * Read back exactly as written and never reinterpreted.
     *
     * Covers two cases that must behave identically. Bookmarks written before
     * the space was recorded at all decode to this by default. So do bookmarks
     * dropped during offline playback of a file whose place in the book is
     * unknown, where asserting whole-book time would be a guess that a later
     * session — one that *does* have the timeline — would then act on.
     */
    @SerialName("as_recorded")
    AsRecorded,
}

/**
 * A user-dropped bookmark in an audiobook. Stored locally (per
 * server / profile / contentId) until the server exposes a sync
 * endpoint; the [id] is a client-generated UUID-ish string so it
 * survives the eventual server round-trip.
 */
@Serializable
data class AudiobookBookmark(
    val id: String,
    val positionSeconds: Double,
    /** Defaults to [BookmarkPositionSpace.AsRecorded] so bookmarks stored
     *  before this field existed keep deserializing, and are read back the way
     *  they were written rather than being reinterpreted. */
    @SerialName("position_space")
    val positionSpace: BookmarkPositionSpace = BookmarkPositionSpace.AsRecorded,
    /**
     * The part file this bookmark was dropped in, when it was known.
     *
     * Resolves the one place a whole-book position is genuinely ambiguous: a
     * part boundary belongs to two parts at once. The instant part two ends is
     * numerically the instant part three begins, so an offline session holding
     * only part two cannot tell "the end of what I have" from "the start of
     * what I do not" by position alone. Null for bookmarks written before this
     * existed, and for playback with no known part.
     */
    @SerialName("source_file_id")
    val sourceFileId: Int? = null,
    /** Title of the chapter the bookmark fell in, captured at drop
     *  time so the list can render it without re-resolving against the
     *  current chapter list. */
    val chapterTitle: String? = null,
    val note: String? = null,
    val createdAtMs: Long,
)
