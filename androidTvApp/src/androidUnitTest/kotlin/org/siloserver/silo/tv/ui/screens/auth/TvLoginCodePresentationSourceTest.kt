package org.siloserver.silo.tv.ui.screens.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvLoginCodePresentationSourceTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/auth/TvLoginScreen.kt",
    ).readText()

    @Test
    fun `phone sign-in surface shows the eight-character manual code as well as the match code`() {
        assertTrue(
            source.contains("MatchCodeTiles(code = state.session.matchCode)"),
            "The security match code must remain visible for scan confirmation",
        )
        assertTrue(
            source.contains("ManualSignInCode(code = state.session.userCode)"),
            "The server-issued eight-character user code must be visible for manual sign-in",
        )
    }

    @Test
    fun `variable-length match code is measured against the available card width`() {
        assertTrue(
            source.contains("BoxWithConstraints("),
            "Variable-length server match codes must be measured inside the card constraints",
        )
        assertTrue(
            source.contains("val metrics = matchCodeTileMetrics("),
            "Match-code tile width must adapt instead of using a fixed width that can clip",
        )
    }

    @Test
    fun `long word-pair match code fits the login card`() {
        val code = "ALPHA-BETA"
        val availableWidthDp = 252f
        val metrics = matchCodeTileMetrics(code, availableWidthDp)
        val separatorCount = code.count { it == '-' || it == ' ' }
        val tileCount = code.length - separatorCount
        val renderedWidth =
            tileCount * metrics.tileWidthDp +
                separatorCount * metrics.separatorWidthDp +
                (code.length - 1) * metrics.gapDp

        assertTrue(renderedWidth <= availableWidthDp)
        assertTrue(metrics.tileWidthDp < 24f)
    }

    @Test
    fun `short match code keeps the full-size tiles`() {
        assertEquals(
            24f,
            matchCodeTileMetrics(code = "RED-BLUE", availableWidthDp = 252f).tileWidthDp,
        )
    }
}
