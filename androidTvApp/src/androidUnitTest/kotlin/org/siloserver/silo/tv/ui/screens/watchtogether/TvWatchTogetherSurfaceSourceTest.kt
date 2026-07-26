package org.siloserver.silo.tv.ui.screens.watchtogether

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The TV entry point was missing for a long time in a way nothing caught:
 * `TvItemDetailScreen` declared an `onWatchTogether` callback and
 * `TvAppNavigation` wired it to the lobby/synced-player routes, but no code path
 * ever invoked it and `TvWatchTogetherEntryDialog` was never instantiated. The
 * feature compiled, navigated, and was unreachable.
 *
 * These assertions are deliberately about *wiring existing at all*, which is
 * exactly what was absent — a behavioural test would have needed the dialog to
 * be reachable before it could observe anything.
 */
class TvWatchTogetherSurfaceSourceTest {
    private val itemDetailScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
    ).readText()
    private val appNavigation = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt",
    ).readText()

    @Test
    fun detailOverflowOpensTheEntryDialog() {
        assertTrue(itemDetailScreen.contains("CLIENT_WATCH_TOGETHER_SURFACE_ENABLED"))
        assertTrue(itemDetailScreen.contains("key = \"watch-together\""))
        assertTrue(itemDetailScreen.contains("watchTogetherOpen = true"))
        assertTrue(itemDetailScreen.contains("TvWatchTogetherEntryDialog("))
        assertTrue(itemDetailScreen.contains("TvJoinCodeDialog("))
    }

    @Test
    fun aResolvedRoomReachesTheNavigationCallback() {
        assertTrue(itemDetailScreen.contains("onWatchTogether(room)"))
        assertTrue(itemDetailScreen.contains("watchTogetherViewModel.consumeResult()"))
        assertTrue(appNavigation.contains("TvRoute.WatchTogetherLobby(roomId = snapshot.roomId).route"))
    }

    /**
     * A room's transport drives a video player and the lobby's "now playing" has
     * nothing to show for an audiobook, so the action stays off those pages.
     */
    @Test
    fun audiobooksDoNotOfferARoom() {
        assertTrue(
            itemDetailScreen.contains(
                "CLIENT_WATCH_TOGETHER_SURFACE_ENABLED && !isAudiobookItemType(detail.type)",
            ),
        )
    }
}
