package org.siloserver.silo.tv.ui.screens.player

import android.view.KeyEvent

internal enum class TvPlayerRemoteKeyAction {
    PlayPause,
    FocusTransport,
    SkipBack,
    SkipForward,
    OpenHud,
    // Unconsumed media-key events reach the system media-key fallback, which
    // toggles the Media3 session a second time — so both the UP half and any
    // auto-repeat DOWN events must be swallowed here without acting on them.
    ConsumeOnly,
}

internal fun tvPlayerRemoteKeyAction(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
    // Left/Right = seek is only safe while no focus-owning surface (transport
    // overlay, HUD, Up Next) is on screen. When one is, Left/Right must fall
    // through so Compose focus navigation keeps moving the selection.
    dpadHorizontalSeek: Boolean = true,
    // True once the transport overlay owns focus, which makes Down the
    // overlay's own business rather than the root handler's — see the Down
    // branch below.
    overlayOwnsFocus: Boolean = false,
): TvPlayerRemoteKeyAction? = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    -> if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
        TvPlayerRemoteKeyAction.PlayPause
    } else {
        TvPlayerRemoteKeyAction.ConsumeOnly
    }

    KeyEvent.KEYCODE_DPAD_DOWN ->
        when {
            action != KeyEvent.ACTION_DOWN -> null
            // Once the overlay is up it owns Down, so the root handler must let
            // it through: the scrubber hands focus down to the transport row
            // (silo-apple TVPlayerScrubber .down -> onMoveToTransport), and the
            // transport row has nothing below it (TVPlayerTransportCluster
            // answers .up only, `default: break`). Mapping Down to the HUD here
            // consumed the event in the root preview handler, which sits above
            // the scrubber in the tree, and left that hand-off dead.
            //
            // The HUD keeps its two tvOS entry points: the Tune button in the
            // transport row, and Menu/Settings below.
            overlayOwnsFocus -> null
            // From clean playback Down reveals the transport row — skip back /
            // play-pause / skip forward / subtitles / options / close — which is
            // what Down is for on a video screen.
            else -> TvPlayerRemoteKeyAction.FocusTransport
        }

    KeyEvent.KEYCODE_DPAD_LEFT ->
        when {
            !dpadHorizontalSeek -> null
            action == KeyEvent.ACTION_DOWN && repeatCount == 0 -> TvPlayerRemoteKeyAction.SkipBack
            else -> TvPlayerRemoteKeyAction.ConsumeOnly
        }

    KeyEvent.KEYCODE_DPAD_RIGHT ->
        when {
            !dpadHorizontalSeek -> null
            action == KeyEvent.ACTION_DOWN && repeatCount == 0 -> TvPlayerRemoteKeyAction.SkipForward
            else -> TvPlayerRemoteKeyAction.ConsumeOnly
        }

    KeyEvent.KEYCODE_MENU,
    KeyEvent.KEYCODE_SETTINGS,
    -> if (action == KeyEvent.ACTION_UP) TvPlayerRemoteKeyAction.OpenHud else null

    else -> null
}

// The idle overlay is a focus-owning surface: the scrubber handles its own
// Left/Right skips when focused, and the transport cluster needs Left/Right
// for moving between buttons — so horizontal seek mapping stays off here.
// Down is likewise the overlay's own (scrubber -> transport hand-off).
internal fun tvPlayerIdleOverlayRemoteKeyAction(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
): TvPlayerRemoteKeyAction? =
    tvPlayerRemoteKeyAction(
        keyCode = keyCode,
        action = action,
        repeatCount = repeatCount,
        dpadHorizontalSeek = false,
        overlayOwnsFocus = true,
    )
