package org.siloserver.silo.tv.ui.screens.player

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvPlayerRemoteKeyActionTest {

    @Test
    fun mediaPlayPauseKeysTogglePlaybackOnKeyDown() {
        listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        ).forEach { keyCode ->
            assertEquals(
                TvPlayerRemoteKeyAction.PlayPause,
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 0,
                ),
            )
        }
    }

    @Test
    fun mediaPlayPauseKeyUpIsConsumedWithoutTogglingPlayback() {
        listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        ).forEach { keyCode ->
            assertEquals(
                TvPlayerRemoteKeyAction.ConsumeOnly,
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_UP,
                    repeatCount = 0,
                ),
            )
        }
    }

    @Test
    fun repeatedMediaKeyDownIsConsumedWithoutTogglingPlayback() {
        listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        ).forEach { keyCode ->
            assertEquals(
                TvPlayerRemoteKeyAction.ConsumeOnly,
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 1,
                ),
            )
        }
    }

    @Test
    fun downMovesFocusToTransportAndMenuAndSettingsOpenHudFromIdleOverlay() {
        // Down reveals the transport row from clean playback, and belongs to the
        // overlay once it is up — the scrubber hands focus down to the transport
        // row itself. Menu/Settings open the HUD directly, as does the Tune
        // button in that row, so the HUD is never stranded.
        assertEquals(
            TvPlayerRemoteKeyAction.FocusTransport,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertNull(
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                dpadHorizontalSeek = false,
                overlayOwnsFocus = true,
            ),
            "the root handler must not consume Down while the overlay owns focus",
        )
        assertNull(
            tvPlayerIdleOverlayRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
            "TvPlayerScrubber.onMoveDownToTransport is dead code if this consumes",
        )
        listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS).forEach { keyCode ->
            assertEquals(
                TvPlayerRemoteKeyAction.OpenHud,
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_UP,
                    repeatCount = 0,
                ),
            )
        }
    }

    @Test
    fun leftAndRightSeekDuringPlaybackInsteadOfOpeningChrome() {
        assertEquals(
            TvPlayerRemoteKeyAction.SkipBack,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertEquals(
            TvPlayerRemoteKeyAction.SkipForward,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertEquals(
            TvPlayerRemoteKeyAction.ConsumeOnly,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
            ),
        )
        assertEquals(
            TvPlayerRemoteKeyAction.ConsumeOnly,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 1,
            ),
        )
    }

    @Test
    fun visibleIdleOverlayLeftAndRightFallThroughToFocusNavigation() {
        // With the transport overlay visible, Left/Right must reach Compose
        // focus navigation (scrubber ticks, transport-cluster movement) —
        // never seek or get swallowed at the overlay level.
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT).forEach { keyCode ->
            assertNull(
                tvPlayerIdleOverlayRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 0,
                ),
            )
            assertNull(
                tvPlayerIdleOverlayRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_UP,
                    repeatCount = 0,
                ),
            )
            assertNull(
                tvPlayerIdleOverlayRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 2,
                ),
            )
        }
    }

    @Test
    fun leftAndRightFallThroughWhenHorizontalSeekIsDisabled() {
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT).forEach { keyCode ->
            assertNull(
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 0,
                    dpadHorizontalSeek = false,
                ),
            )
            assertNull(
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_UP,
                    repeatCount = 0,
                    dpadHorizontalSeek = false,
                ),
            )
        }
    }

    @Test
    fun idleOverlayStillHandlesTransportAndHudKeys() {
        assertEquals(
            TvPlayerRemoteKeyAction.PlayPause,
            tvPlayerIdleOverlayRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertEquals(
            TvPlayerRemoteKeyAction.OpenHud,
            tvPlayerIdleOverlayRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_MENU,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
            ),
        )
    }

    @Test
    fun nonMatchingActionsAndUnhandledKeysFallThrough() {
        assertNull(
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
            ),
        )
        assertNull(
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_MENU,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertNull(
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
    }
}
