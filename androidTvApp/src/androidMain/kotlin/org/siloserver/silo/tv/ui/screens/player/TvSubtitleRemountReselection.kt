package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.player.MountedSubtitleTrack
import org.siloserver.silo.common.player.downloadedSubtitleArtifactTrackId
import org.siloserver.silo.common.player.resolveMountedSubtitle
import org.siloserver.silo.common.player.subtitleArtifactTrackId
import org.siloserver.silo.model.playback.SubtitleIdentity

internal data class TvSubtitleRemountOwner(
    val identity: SubtitleIdentity,
    val generation: Long,
)

internal class TvSubtitleSnapshotSettlementTracker {
    private var previousKey: String? = null

    fun observe(tracks: List<PlayerTrackEntry>): Boolean {
        val key = tracks
            .takeIf(List<PlayerTrackEntry>::isNotEmpty)
            ?.joinToString("|") {
                "${it.index}:${it.trackId}:${it.label}:${it.language}:${it.codecOrMime}:${it.isSelected}"
            }
        val settled = key != null && key == previousKey
        previousKey = key
        return settled
    }

    fun reset() {
        previousKey = null
    }
}

internal enum class TvSubtitleRemountFailure {
    Missing,
    Ambiguous,
}

internal sealed interface TvSubtitleRemountEvent {
    val owner: TvSubtitleRemountOwner

    data class Select(
        override val owner: TvSubtitleRemountOwner,
        val trackIndex: Int,
    ) : TvSubtitleRemountEvent

    data class Failed(
        override val owner: TvSubtitleRemountOwner,
        val reason: TvSubtitleRemountFailure,
    ) : TvSubtitleRemountEvent
}

internal class SubtitleRemountReselection(
    private val maxMeaningfulSnapshots: Int = 3,
) {
    private var pendingOwner: TvSubtitleRemountOwner? = null
    private val meaningfulSnapshotKeys = linkedSetOf<String>()

    val hasPendingOwner: Boolean
        get() = pendingOwner != null

    fun requiresRemount(identity: SubtitleIdentity): Boolean = when (identity) {
        SubtitleIdentity.Off,
        is SubtitleIdentity.ServerSidecar,
        is SubtitleIdentity.Embedded,
        is SubtitleIdentity.Downloaded,
        is SubtitleIdentity.LocalMedia3,
        -> true
        is SubtitleIdentity.ServerBurnIn -> false
    }

    fun arm(identity: SubtitleIdentity, generation: Long) {
        pendingOwner = if (requiresRemount(identity)) {
            TvSubtitleRemountOwner(identity, generation)
        } else {
            null
        }
        meaningfulSnapshotKeys.clear()
    }

    fun consume(
        subtitleTracks: List<PlayerTrackEntry>,
        snapshotKey: String?,
        settled: Boolean,
    ): TvSubtitleRemountEvent? {
        val owner = pendingOwner ?: return null
        if (owner.identity == SubtitleIdentity.Off) {
            clear()
            return TvSubtitleRemountEvent.Select(owner, trackIndex = -1)
        }

        val mounted = subtitleTracks.map(PlayerTrackEntry::toMountedTvSubtitleTrack)
        val exactTrackId = owner.identity.exactTvMountTrackId()
        val matchIndex = if (exactTrackId != null) {
            mounted.filter { it.trackId == exactTrackId }.singleOrNull()?.index
        } else {
            resolveMountedSubtitle(identity = owner.identity, tracks = mounted)?.track?.index
        }
        if (matchIndex != null) {
            clear()
            return TvSubtitleRemountEvent.Select(owner, matchIndex)
        }

        val meaningfulKey = snapshotKey?.takeIf { subtitleTracks.isNotEmpty() }
        if (meaningfulKey != null) meaningfulSnapshotKeys += meaningfulKey
        if (!settled && meaningfulSnapshotKeys.size < maxMeaningfulSnapshots) return null

        clear()
        return TvSubtitleRemountEvent.Failed(
            owner = owner,
            reason = if (owner.identity.hasAmbiguousTvLabel(subtitleTracks)) {
                TvSubtitleRemountFailure.Ambiguous
            } else {
                TvSubtitleRemountFailure.Missing
            },
        )
    }

    fun clear() {
        pendingOwner = null
        meaningfulSnapshotKeys.clear()
    }
}

private fun SubtitleIdentity.exactTvMountTrackId(): String? = when (this) {
    is SubtitleIdentity.ServerSidecar -> subtitleArtifactTrackId(serverIndex)
    is SubtitleIdentity.Downloaded -> downloadedSubtitleArtifactTrackId(downloadId)
    is SubtitleIdentity.Embedded -> media.trackId?.trim()?.takeIf(String::isNotEmpty)
    is SubtitleIdentity.LocalMedia3 -> media.trackId?.trim()?.takeIf(String::isNotEmpty)
    SubtitleIdentity.Off,
    is SubtitleIdentity.ServerBurnIn,
    -> null
}

private fun PlayerTrackEntry.toMountedTvSubtitleTrack(): MountedSubtitleTrack =
    MountedSubtitleTrack(
        index = index,
        trackId = trackId,
        label = label,
        language = language,
        codec = codecOrMime,
        forced = isForced,
        hearingImpaired = isHearingImpaired,
    )

private fun SubtitleIdentity.hasAmbiguousTvLabel(tracks: List<PlayerTrackEntry>): Boolean {
    val label = when (this) {
        is SubtitleIdentity.ServerSidecar -> media?.label
        is SubtitleIdentity.ServerBurnIn -> media?.label
        is SubtitleIdentity.Embedded -> media.label
        is SubtitleIdentity.Downloaded -> media.label
        is SubtitleIdentity.LocalMedia3 -> media.label
        SubtitleIdentity.Off -> null
    }?.trim()?.lowercase() ?: return false
    return tracks.count {
        it.label.trim().lowercase() == label ||
            it.displayLabel.trim().lowercase() == label
    } > 1
}
