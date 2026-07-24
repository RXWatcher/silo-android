package org.siloserver.silo.common.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LetterboxDetectionTest {

    private fun frame(
        width: Int,
        height: Int,
        topBar: Int,
        bottomBar: Int,
        pictureColor: Int = 0xFF808080.toInt(),
        barColor: Int = 0xFF000000.toInt(),
    ): IntArray = IntArray(width * height) { i ->
        val row = i / width
        if (row < topBar || row >= height - bottomBar) barColor else pictureColor
    }

    @Test
    fun `measures hard-coded bars of a 2 point 39 image inside a 1080 frame`() {
        // 1920x1080 carrying a 2.39:1 picture: 139 black rows top and bottom.
        val insets = detectLetterboxInsets(
            pixels = frame(width = 192, height = 108, topBar = 14, bottomBar = 14),
            width = 192,
            height = 108,
        )

        assertTrue(insets.isDetected)
        assertEquals(14f / 108f, insets.topFraction)
        assertEquals(14f / 108f, insets.bottomFraction)
    }

    @Test
    fun `full frame picture reports no letterbox`() {
        val insets = detectLetterboxInsets(
            pixels = frame(width = 64, height = 36, topBar = 0, bottomBar = 0),
            width = 64,
            height = 36,
        )

        assertFalse(insets.isDetected)
    }

    @Test
    fun `a mostly dark frame is not mistaken for letterboxing`() {
        // A night scene lit only across a few middle rows would otherwise read
        // as enormous bars and shove subtitles into the middle of the picture.
        val insets = detectLetterboxInsets(
            pixels = frame(width = 64, height = 36, topBar = 16, bottomBar = 16),
            width = 64,
            height = 36,
        )

        assertFalse(insets.isDetected)
    }

    @Test
    fun `a fully black frame carries no evidence`() {
        val insets = detectLetterboxInsets(
            pixels = IntArray(64 * 36) { 0xFF000000.toInt() },
            width = 64,
            height = 36,
        )

        assertFalse(insets.isDetected)
    }

    @Test
    fun `near-black picture rows still count as picture`() {
        // Compression noise leaves bars slightly above 0; picture content that
        // is merely dim must not be swallowed by the threshold.
        val pixels = frame(width = 64, height = 36, topBar = 4, bottomBar = 4)
        for (column in 0 until 64) pixels[10 * 64 + column] = 0xFF1E1E1E.toInt()

        val insets = detectLetterboxInsets(pixels = pixels, width = 64, height = 36)

        assertEquals(4f / 36f, insets.topFraction)
    }

    @Test
    fun `a lit right edge prevents a strided scan from calling the row black`() {
        val pixels = frame(width = 64, height = 36, topBar = 4, bottomBar = 4)
        pixels[2 * 64 + 63] = 0xFFFFFFFF.toInt()

        val insets = detectLetterboxInsets(pixels = pixels, width = 64, height = 36)

        assertEquals(2f / 36f, insets.topFraction)
    }

    @Test
    fun `intersecting samples discards a transient fade to black`() {
        val letterboxed = LetterboxInsets(topFraction = 0.13f, bottomFraction = 0.13f)
        val fadedToBlack = LetterboxInsets(topFraction = 0.30f, bottomFraction = 0.30f)

        val combined = letterboxed.intersect(fadedToBlack)

        assertEquals(0.13f, combined.topFraction)
        assertEquals(0.13f, combined.bottomFraction)
    }

    @Test
    fun `intersecting with a full-frame sample abandons the inset entirely`() {
        val combined = LetterboxInsets(0.13f, 0.13f).intersect(LetterboxInsets.NONE)

        assertFalse(combined.isDetected)
    }

    @Test
    fun `rect is inset to the picture inside the bars`() {
        val rect = SubtitleVideoRect(left = 0, top = 100, width = 1920, height = 1080)

        val inset = rect.insetByLetterbox(LetterboxInsets(0.125f, 0.125f))

        assertEquals(100 + 135, inset.top)
        assertEquals(810, inset.height)
        assertEquals(1920, inset.width)
    }

    @Test
    fun `rect is untouched when nothing was detected`() {
        val rect = SubtitleVideoRect(left = 4, top = 8, width = 100, height = 50)

        assertEquals(rect, rect.insetByLetterbox(LetterboxInsets.NONE))
    }
}
