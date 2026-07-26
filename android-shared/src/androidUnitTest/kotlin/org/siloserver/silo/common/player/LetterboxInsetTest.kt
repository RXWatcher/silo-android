package org.siloserver.silo.common.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Insetting the subtitle layer to the real picture when the source has black
 * bars encoded into the frame. The server measures them; this is the geometry
 * that keeps cues off the bar.
 */
class LetterboxInsetTest {

    private val fullFrame = SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 1080)

    // A 2.39:1 image inside a 1920x1080 frame: ~12.8% of the height is black at
    // the top and the same at the bottom.
    @Test
    fun scopeContentInsetsToThePicture() {
        val inset = fullFrame.insetByLetterbox(LetterboxInsets(0.1278f, 0.1278f))

        assertEquals(138, inset.top)
        assertEquals(804, inset.height)
        // Horizontal geometry is untouched: the bars are top and bottom only.
        assertEquals(0, inset.left)
        assertEquals(1920, inset.width)
    }

    @Test
    fun noMeasurementLeavesTheRectAlone() {
        assertEquals(fullFrame, fullFrame.insetByLetterbox(LetterboxInsets.NONE))
    }

    // Bars that would consume the whole frame are a bad measurement, and
    // insetting by them would leave nowhere to draw.
    @Test
    fun barsLargerThanTheFrameAreRefused() {
        assertEquals(fullFrame, fullFrame.insetByLetterbox(LetterboxInsets(0.6f, 0.6f)))
    }

    // One sample cannot tell a bar from a dark scene; the smaller bar per edge
    // is the one present in every frame.
    @Test
    fun intersectKeepsTheSmallerBarPerEdge() {
        val measured = LetterboxInsets(0.13f, 0.13f)
        val darkFrame = LetterboxInsets(0.40f, 0.35f)

        val combined = measured.intersect(darkFrame)

        assertEquals(0.13f, combined.topFraction, 0.0001f)
        assertEquals(0.13f, combined.bottomFraction, 0.0001f)
    }
}
