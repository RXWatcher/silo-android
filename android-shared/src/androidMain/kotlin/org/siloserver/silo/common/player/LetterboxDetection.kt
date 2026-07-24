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

/** Rec. 709 luma, matching how the eye weights the channels. */
internal fun pixelLuma(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return ((r * 2126 + g * 7152 + b * 722) / 10000)
}

/**
 * Measures the black bars of a single decoded frame.
 *
 * [pixels] is row-major ARGB, [width] * [height] entries. Columns are sampled
 * rather than fully scanned: a bar spans the whole width, so a stride is enough
 * to reject any row containing picture.
 *
 * Returns [LetterboxInsets.NONE] when the bars exceed [maxTotalFraction] of the
 * frame, which means the frame is mostly dark (a fade, a night scene) rather
 * than letterboxed — never inset the subtitle layer on that evidence.
 */
fun detectLetterboxInsets(
    pixels: IntArray,
    width: Int,
    height: Int,
    lumaThreshold: Int = 12,
    columnStride: Int = 8,
    maxTotalFraction: Float = 0.35f,
): LetterboxInsets {
    if (width <= 0 || height <= 0 || pixels.size < width * height) {
        return LetterboxInsets.NONE
    }
    val stride = columnStride.coerceIn(1, width)

    fun rowIsBlack(row: Int): Boolean {
        val base = row * width
        var column = 0
        while (column < width) {
            if (pixelLuma(pixels[base + column]) > lumaThreshold) return false
            column += stride
        }
        // Always inspect the final column: a stride can otherwise skip the only
        // lit pixels on an image that is bright solely at its right edge.
        return pixelLuma(pixels[base + width - 1]) <= lumaThreshold
    }

    var top = 0
    while (top < height && rowIsBlack(top)) top++
    if (top == height) return LetterboxInsets.NONE // fully black frame carries no evidence

    var bottom = 0
    while (bottom < height - top && rowIsBlack(height - 1 - bottom)) bottom++

    val totalFraction = (top + bottom).toFloat() / height.toFloat()
    if (totalFraction > maxTotalFraction) return LetterboxInsets.NONE

    return LetterboxInsets(
        topFraction = top.toFloat() / height.toFloat(),
        bottomFraction = bottom.toFloat() / height.toFloat(),
    )
}

/**
 * Shrinks a displayed video rect to the picture inside the black bars.
 *
 * Bars are measured against the decoded frame, so the fractions apply to the
 * displayed video height — not to the whole view, which may itself be
 * letterboxing the frame.
 */
internal fun SubtitleVideoRect.insetByLetterbox(insets: LetterboxInsets): SubtitleVideoRect {
    if (!insets.isDetected || height <= 0) return this
    val topInset = (height * insets.topFraction).roundToInt()
    val bottomInset = (height * insets.bottomFraction).roundToInt()
    val remaining = height - topInset - bottomInset
    if (remaining <= 0) return this
    return copy(top = top + topInset, height = remaining)
}
