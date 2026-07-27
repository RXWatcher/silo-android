package org.siloserver.silo.common.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Televisions crop the outer edge of the picture. Media3's text layer clears
 * the bottom via its own padding fraction, but libass places ASS/SSA cues
 * against whatever surface it is handed, so a cue authored at the bottom of the
 * frame is composited into pixels the panel never shows — the line just never
 * appears, with nothing logged. silo-apple hit the same thing (#95).
 */
class TitleSafeInsetTest {

    @Test
    fun `pulls the surface in on every edge`() {
        val rect = SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 1080)

        val safe = rect.insetByTitleSafe(0.05f)

        assertEquals(96, safe.left)
        assertEquals(54, safe.top)
        assertEquals(1920 - 96 * 2, safe.width)
        assertEquals(1080 - 54 * 2, safe.height)
    }

    @Test
    fun `zero is a no-op so phones are untouched`() {
        val rect = SubtitleVideoRect(left = 10, top = 20, width = 800, height = 400)

        assertEquals(rect, rect.insetByTitleSafe(0f))
    }

    /**
     * Bars first (what the source wastes), overscan second (what the panel
     * eats). The two compose rather than fight: the title-safe inset applies to
     * what the letterbox inset left behind, not to the original frame.
     */
    @Test
    fun `composes with the letterbox inset`() {
        val frame = SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 1080)

        val picture = frame.insetByLetterbox(
            LetterboxInsets(topFraction = 0.13f, bottomFraction = 0.13f),
        )
        val safe = picture.insetByTitleSafe(0.05f)

        // 1080 - 140 - 140 = 800 of picture, then 5% of *that* per edge.
        assertEquals(800, picture.height)
        assertEquals(800 - 40 * 2, safe.height)
        assertEquals(picture.top + 40, safe.top)
    }

    /**
     * A nonsensical fraction must leave the surface alone rather than collapse
     * it to nothing — subtitles that vanish are the bug being fixed.
     */
    @Test
    fun `refuses an inset that would consume the surface`() {
        val rect = SubtitleVideoRect(left = 0, top = 0, width = 100, height = 100)

        assertEquals(rect, rect.insetByTitleSafe(0.6f))
    }
}
