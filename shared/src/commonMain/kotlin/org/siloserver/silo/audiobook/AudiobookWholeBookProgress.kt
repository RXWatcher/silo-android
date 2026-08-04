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
    WholeBookProgressMode.AlreadyGlobal ->
        WholeBookProgress(positionSeconds, durationSeconds).takeIf { positionSeconds > 0.0 }

    is WholeBookProgressMode.OfflinePart -> {
        val global = mode.timeline.globalTimeFor(positionSeconds, mode.track)
        WholeBookProgress(global, mode.timeline.totalSeconds).takeIf { global > 0.0 }
    }

    WholeBookProgressMode.Unmappable -> null
}
