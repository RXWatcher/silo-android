package org.siloserver.silo.common.player

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fractions of a decoded video frame occupied by hard-coded black bars.
 *
 * Some sources — Disney+/Netflix WEB-DLs in particular — encode a 2.39:1 image
 * inside a 1920x1080 frame with the letterbox baked into the picture. The
 * player only sees a full-height 1080 video, so subtitles anchored to the
 * bottom of the frame land inside the black bar instead of over the image.
 * Measuring the bars lets the subtitle layer be inset to the real picture.
 */
data class LetterboxInsets(
    val topFraction: Float,
    val bottomFraction: Float,
) {
    val isDetected: Boolean
        get() = topFraction > 0f || bottomFraction > 0f

    /**
     * Combines two measurements by taking the smaller bar on each edge.
     *
     * A single frame can over-estimate — a fade to black, or a dark scene whose
     * top rows happen to be black, looks exactly like a bar. A genuine hard
     * -coded bar is present in *every* frame, so the minimum across samples
     * converges on the true inset while a transient dark frame is discarded.
     */
    fun intersect(other: LetterboxInsets): LetterboxInsets = LetterboxInsets(
        topFraction = min(topFraction, other.topFraction),
        bottomFraction = min(bottomFraction, other.bottomFraction),
    )

    companion object {
        val NONE = LetterboxInsets(0f, 0f)
    }
}

internal fun SubtitleVideoRect.insetByLetterbox(insets: LetterboxInsets): SubtitleVideoRect {
    if (!insets.isDetected || height <= 0) return this
    val topInset = (height * insets.topFraction).roundToInt()
    val bottomInset = (height * insets.bottomFraction).roundToInt()
    val remaining = height - topInset - bottomInset
    if (remaining <= 0) return this
    return copy(top = top + topInset, height = remaining)
}
