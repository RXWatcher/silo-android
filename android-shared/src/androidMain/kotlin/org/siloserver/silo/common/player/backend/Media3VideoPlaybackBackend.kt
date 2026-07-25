package org.siloserver.silo.common.player.backend

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.common.player.AudioTrackManager
import org.siloserver.silo.common.player.SiloPlayerFactory
import org.siloserver.silo.common.player.VideoPlayerMediaSpec
import org.siloserver.silo.common.player.mountVideoMedia
import org.siloserver.silo.common.player.refreshMountedVideoMedia
import org.siloserver.silo.common.player.video.VideoPlayerTrackEntry
import org.siloserver.silo.common.player.video.VideoTrackSelectionCoordinator
import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.HdrCapabilities
import org.siloserver.silo.model.playback.SubtitleIdentity

@UnstableApi
class Media3VideoPlaybackBackend(
    private val playerFactory: SiloPlayerFactory,
    private val audioTrackManager: AudioTrackManager,
    private val trackSelectionCoordinator: VideoTrackSelectionCoordinator,
    override val player: Player,
) : VideoPlaybackBackend {
    override val kind: VideoPlaybackBackendKind = VideoPlaybackBackendKind.Media3
    override val capabilities: VideoBackendCapabilities = VideoBackendCapabilities.media3()

    private var mountedSpec: VideoPlayerMediaSpec? = null

    override fun mount(
        spec: VideoPlayerMediaSpec,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        mountedSpec = spec
        mountVideoMedia(
            player = player,
            playerFactory = playerFactory,
            spec = spec,
            startPositionMs = startPositionMs,
            playWhenReady = playWhenReady,
        )
    }

    override fun refresh(spec: VideoPlayerMediaSpec) {
        mountedSpec = spec
        refreshMountedVideoMedia(
            player = player,
            playerFactory = playerFactory,
            spec = spec,
        )
    }

    override fun selectSubtitle(track: VideoPlayerTrackEntry?): Boolean {
        // A sidecar cannot be merged before the video media exists. This used to
        // throw, which crashed the app outright when a same-route retry asked
        // for a subtitle before the remount had happened; the caller already
        // treats false as "not selected" and retries once tracks publish.
        val mediaSpec = mediaSpecForExternalSubtitle(track) ?: return false
        return trackSelectionCoordinator.selectSubtitle(
            player = player,
            playerFactory = playerFactory,
            mediaSpec = mediaSpec,
            selectedTrack = track,
        )
    }

    override fun selectMountedSubtitle(
        identity: SubtitleIdentity,
    ): Boolean = trackSelectionCoordinator.selectMountedSubtitle(
        player = player,
        identity = identity,
    )

    override fun selectAudioTrack(track: VideoPlayerTrackEntry) {
        trackSelectionCoordinator.selectAudioTrack(
            player = player,
            audioTrackManager = audioTrackManager,
            selectedTrack = track,
        )
    }

    override fun applyTrackSelection(
        audioCaps: AudioPassthroughCapabilities,
        displayHdr: HdrCapabilities,
        preferredAudioLanguage: String?,
        preferredTextLanguage: String?,
        hdrEnabled: Boolean,
    ) {
        playerFactory.applyTrackSelectionPresets(
            player = player,
            audioCaps = audioCaps,
            displayHdr = displayHdr,
            preferredAudioLanguage = preferredAudioLanguage,
            preferredTextLanguage = preferredTextLanguage,
            hdrEnabled = hdrEnabled,
        )
    }

    override fun release() {
        // Through the factory so the libass handler and its embedded fonts go
        // with the player, instead of surviving until the next playback.
        playerFactory.releasePlayer(player)
    }

    /** Null when a sidecar is requested before any video media has been mounted. */
    private fun mediaSpecForExternalSubtitle(
        track: VideoPlayerTrackEntry?,
    ): VideoPlayerMediaSpec? {
        val spec = mountedSpec
        if (spec != null) return spec
        if (track?.subtitle == null) {
            return VideoPlayerMediaSpec(
                streamUrl = "",
                playMethod = org.siloserver.silo.model.playback.PlayMethod.DIRECT,
                serverUrl = "",
            )
        }
        return null
    }
}
