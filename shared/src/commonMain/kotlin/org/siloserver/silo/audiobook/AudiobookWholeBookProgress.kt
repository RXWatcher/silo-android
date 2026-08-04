package org.siloserver.silo.audiobook

/**
 * How a playback position relates to the whole book, which decides whether it
 * can be written to the durable whole-book sink and how it must be converted
 * first.
 *
 * Online playback already tracks whole-book time. Offline playback of one
 * downloaded part runs on part-local time, which must never be recorded as the
 * book's position — but when the timeline and the playing part are both known
 * it converts straight back, and dropping the write instead would discard the
 * viewer's entire offline listening session.
 */
sealed interface WholeBookProgressMode {
    /** Position is already whole-book time; no conversion needed. */
    data object AlreadyGlobal : WholeBookProgressMode

    /**
     * Offline playback of one downloaded file whose place in the book is
     * unknown — no cached item detail to build a timeline from, and no saved
     * resume position overshooting the file to give the game away. Position is
     * part-local if the book has parts and whole-book if it does not, and
     * nothing available offline says which: the download sidecar records no
     * part index or part count.
     *
     * Kept distinct from [AlreadyGlobal] because the two are only
     * interchangeable for callers willing to guess. [wholeBookProgress] does
     * guess, preserving long-standing behaviour rather than dropping progress
     * for every offline listen with a cold detail cache. Anything writing
     * durable data that will be re-read against a *different* timeline later
     * must not: see the bookmark mapping, which records such positions as
     * unconverted instead of asserting a whole-book value it cannot prove.
     */
    data class UnknownOfflineLayout(
        /**
         * The file being played. The position cannot be placed in the book,
         * but knowing which file produced it is what lets a later session tell
         * an unconverted position of its own from one belonging to a different
         * download.
         */
        val fileId: Int?,
    ) : WholeBookProgressMode

    /** Position is local to [track] within [timeline] and converts back. */
    data class OfflinePart(
        val timeline: AudiobookTimeline,
        val track: AudioPlaybackTrack,
    ) : WholeBookProgressMode

    /**
     * Part-local playback of a book we hold no timeline for — a lone part whose
     * part list we never had. There is no sound conversion, so nothing may be
     * written against the book.
     */
    data object Unmappable : WholeBookProgressMode
}

/** A whole-book position paired with the whole-book total it is measured against. */
data class WholeBookProgress(
    val positionSeconds: Double,
    val totalSeconds: Double,
)

/**
 * The whole-book progress to persist, or `null` when this playback cannot be
 * expressed against the book at all.
 *
 * A non-positive result is never written: at global zero there is nothing to
 * resume to, and writing it would clobber a real position recorded elsewhere.
 * [AudiobookTimeline.globalTimeFor] clamps into the track's own span, so a
 * converted value can never overshoot into a later part.
 */
fun wholeBookProgress(
    mode: WholeBookProgressMode,
    positionSeconds: Double,
    durationSeconds: Double,
): WholeBookProgress? = when (mode) {
    // The guess called out on UnknownOfflineLayout: a part-local position is
    // recorded as the book's. It is the behaviour that shipped, and the
    // alternative — persisting nothing for these sessions — is a product call,
    // not a correctness one.
    WholeBookProgressMode.AlreadyGlobal, is WholeBookProgressMode.UnknownOfflineLayout ->
        WholeBookProgress(positionSeconds, durationSeconds).takeIf { positionSeconds > 0.0 }

    is WholeBookProgressMode.OfflinePart -> {
        val global = mode.timeline.globalTimeFor(positionSeconds, mode.track)
        WholeBookProgress(global, mode.timeline.totalSeconds).takeIf { global > 0.0 }
    }

    WholeBookProgressMode.Unmappable -> null
}

/**
 * The playback mode for an offline session with no server detail, derived from
 * the part layout recorded on the download.
 *
 * This is what closes the ambiguity [WholeBookProgressMode.UnknownOfflineLayout]
 * exists for. Offline there is nothing to say whether a downloaded file is a
 * whole book or one part of one, so it was captured at download time instead —
 * and a download that carries it needs no guess:
 *
 * - One part means the file's clock IS the book's clock.
 * - More than one, with the offset and totals to convert by, is an ordinary
 *   mapped part. The timeline built here describes only the downloaded part,
 *   which is all a single download can play; that is why part membership is
 *   asked of the track ([AudiobookTimeline.partContains]) rather than of the
 *   timeline as a whole.
 *
 * Downloads made before the layout was recorded, and any with a part count but
 * missing numbers, stay ambiguous.
 */
fun offlineWholeBookMode(
    partCount: Int?,
    partIndex: Int?,
    partStartOffsetSeconds: Double?,
    partDurationSeconds: Double?,
    bookTotalSeconds: Double?,
    fileId: Int?,
): WholeBookProgressMode {
    if (partCount == null) return WholeBookProgressMode.UnknownOfflineLayout(fileId)
    if (partCount <= 1) return WholeBookProgressMode.AlreadyGlobal

    val offset = partStartOffsetSeconds
    val duration = partDurationSeconds
    val total = bookTotalSeconds
    // All of it or none of it. fileId is included because the track's identity
    // is compared against a bookmark's recorded source, and partIndex because
    // "is this the last part" cannot be answered from one track alone. Half a
    // layout is not a layout.
    if (offset == null || duration == null || duration <= 0.0 ||
        total == null || fileId == null || partIndex == null
    ) {
        return WholeBookProgressMode.UnknownOfflineLayout(fileId)
    }

    val track = AudioPlaybackTrack(
        index = partIndex,
        fileId = fileId,
        durationSeconds = duration,
        startOffsetSeconds = offset,
    )
    return WholeBookProgressMode.OfflinePart(
        timeline = AudiobookTimeline(
            tracks = listOf(track),
            chapters = emptyList(),
            totalSeconds = total,
            finalTrackIndex = partCount - 1,
        ),
        track = track,
    )
}
