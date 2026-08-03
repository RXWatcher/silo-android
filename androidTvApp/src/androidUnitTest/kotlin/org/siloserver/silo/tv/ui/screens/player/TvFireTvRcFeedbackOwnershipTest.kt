package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvFireTvRcFeedbackOwnershipTest {

    @Test
    fun `release display version keeps the complete tag`() {
        val gradle = source("build.gradle.kts")
        val workflow = source("../.github/workflows/release.yml")
        val settings = source(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/settings/TvSettingsScreen.kt",
        )

        assertTrue(gradle.contains("SILO_DISPLAY_VERSION"))
        assertTrue(gradle.contains("\"DISPLAY_VERSION\""))
        assertTrue(workflow.contains("SILO_DISPLAY_VERSION: \${{ needs.setup.outputs.version }}"))
        assertTrue(settings.contains("BuildConfig.DISPLAY_VERSION"))
    }

    @Test
    fun `focused selector rows request visibility without changing menu presentation`() {
        val selector = source(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvAnchoredSelectorMenu.kt",
        )

        assertTrue(selector.contains("DropdownMenu("))
        assertTrue(selector.contains("bringIntoViewRequester"))
        assertTrue(selector.contains("bringIntoView()"))
    }

    @Test
    fun `for you first row restores the true top after scrolling back up`() {
        val recommendations = source(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/recommendations/TvRecommendationsScreen.kt",
        )

        assertTrue(recommendations.contains("recommendationsListState.animateScrollToItem(0)"))
        assertTrue(recommendations.contains("onItemFocused"))
    }

    @Test
    fun `phone confirmation card bounds legacy long codes`() {
        val setup = source(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/auth/TvServerSetupScreen.kt",
        )
        val card = setup.substringAfter("private fun MatchCodeCard(code: String)")
            .substringBefore("private object TvServerSetupTextStyles")

        assertTrue(card.contains("matchCodeTileWidthDp(code)"))
        assertTrue(card.contains("MATCH_CODE_TILE_GAP_DP.dp"))
    }

    @Test
    fun `explicit start position owns both player and source timeline resolution`() {
        val starter = source(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvVideoPlaybackStarter.kt",
        )
        val resolutionCount = Regex.escape("resolvePlaybackStartPosition(")
            .toRegex()
            .findAll(starter)
            .count()

        assertTrue(resolutionCount >= 2)
        assertTrue(starter.contains("overridePosition = request.resumePositionOverride"))
    }

    @Test
    fun `stop snapshots state and reserves durable subtitles before player teardown`() {
        val screen = source(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt",
        )
        val viewModel = source(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt",
        )
        val stopBody = screen
            .substringAfter("val stopPlaybackAndExit =")
            .substringBefore("// A remote")
        val asyncBody = viewModel
            .substringAfter("fun stopSessionForExitAsync(")
            .substringBefore("fun onExit()")

        assertBefore(stopBody, "viewModel.stopSessionForExitAsync(", "controller.stop()")
        assertBefore(
            asyncBody,
            "subtitleTransactions.reserveDurableFinalPersistence()",
            "subtitleTransactions.invalidate()",
        )
        assertTrue(asyncBody.contains("subtitleTransactions::requestDurableFinalPersistence"))
        assertTrue(asyncBody.contains("TvDetailTrackSelectionSession.remember"))
        assertTrue(asyncBody.contains("positionSeconds = state.position"))
        assertFalse(asyncBody.contains("viewModelScope.launch"))

        val detail = source(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailViewModel.kt",
        )
        val refreshBody = detail
            .substringAfter("fun refreshOnReturn()")
            .substringBefore("fun onToggleFavorite()")
        assertTrue(refreshBody.contains("TvDetailTrackSelectionSession.consumePlaybackReturn(contentId)"))
        val returnOverlayCount = Regex.escape("withPlaybackReturn")
            .toRegex()
            .findAll(refreshBody)
            .count()
        assertTrue(returnOverlayCount >= 3)
        assertTrue(detail.contains("saved.positionSeconds"))
    }

    private fun source(path: String): String = File(path).readText()

    private fun assertBefore(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing `$first`")
        assertTrue(secondIndex >= 0, "Missing `$second`")
        assertTrue(firstIndex < secondIndex, "Expected `$first` before `$second`")
    }
}
