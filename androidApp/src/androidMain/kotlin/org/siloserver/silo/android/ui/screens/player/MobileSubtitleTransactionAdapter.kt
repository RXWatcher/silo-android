package org.siloserver.silo.android.ui.screens.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.StagedVideoReplan
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.model.playback.CommittedSubtitle
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SelectSubtitle
import org.siloserver.silo.model.playback.StagedSubtitleCandidate
import org.siloserver.silo.model.playback.StagedSubtitleFailed
import org.siloserver.silo.model.playback.StagedSubtitleValidated
import org.siloserver.silo.model.playback.SubtitleContentReset
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleTransitionState
import org.siloserver.silo.model.playback.rebaseDownloadedSubtitleUrl
import org.siloserver.silo.model.playback.reduceSubtitleTransition
import org.siloserver.silo.network.ApiResult

internal data class MobileSubtitlePlaybackContext(
    val contentId: String,
    val mediaFileId: Int,
    val versionId: String,
    val sessionId: String?,
    val positionSeconds: Double,
    val audioTrackIndex: Int?,
    val qualityPreference: String?,
    val subtitleTracks: List<PlayerSubtitleInfo>,
)

internal data class MobileSubtitleStageRequest(
    val generation: Long,
    val contentId: String,
    val mediaFileId: Int,
    val versionId: String,
    val sessionId: String,
    val positionSeconds: Double,
    val audioTrackIndex: Int?,
    val qualityPreference: String?,
    val subtitleTrackIndex: Int,
)

internal data class MobileStagedSubtitleCandidate(
    val id: String,
    val sessionId: String,
    val selectedSubtitleIndex: Int?,
    val subtitleMode: PlaybackSubtitleModeV3,
    val hasSidecar: Boolean,
    val subtitleTracks: List<PlayerSubtitleInfo>,
    internal val managerHandle: StagedVideoReplan? = null,
)

internal data class MobileSubtitleCommittedPlayback(
    val sessionId: String,
    val subtitleTracks: List<PlayerSubtitleInfo>,
    val ready: VideoSessionStartV3.Ready? = null,
)

internal interface MobileSubtitleStagedReplanPort {
    suspend fun stage(request: MobileSubtitleStageRequest): ApiResult<MobileStagedSubtitleCandidate>

    suspend fun commit(
        candidate: MobileStagedSubtitleCandidate,
    ): ApiResult<MobileSubtitleCommittedPlayback>

    suspend fun discard(candidate: MobileStagedSubtitleCandidate)
}

internal interface MobileSubtitlePersistencePort {
    suspend fun persist(
        committed: CommittedSubtitle,
        context: MobileSubtitlePlaybackContext,
    )
}

internal data class MobileSubtitleTransactionSnapshot(
    val transition: SubtitleTransitionState,
    val pendingIdentity: SubtitleIdentity? = transition.pending?.identity,
    val localMountIdentity: SubtitleIdentity? = null,
    val failureMessage: String? = null,
) {
    val committedIdentity: SubtitleIdentity
        get() = transition.committed.identity

    val subtitleApplying: Boolean
        get() = pendingIdentity != null
}

internal data class MobileSubtitleRefreshOwner(
    val contentGeneration: Long,
    val contentId: String,
    val mediaFileId: Int,
    val versionId: String,
    val sessionId: String?,
    val refreshGeneration: Long,
    val subtitleIntentGeneration: Long,
)

/**
 * Mobile execution adapter for the shared subtitle reducer.
 *
 * One conflated worker serializes staged server requests. A newer intent does
 * not cancel an in-flight HTTP request; its eventual candidate is discarded
 * and only the newest queued intent is staged from the still-committed session.
 */
internal class MobileSubtitleTransactionAdapter(
    scope: CoroutineScope,
    private val stagedPort: MobileSubtitleStagedReplanPort,
    private val persistencePort: MobileSubtitlePersistencePort,
    private val onSnapshotChanged: (MobileSubtitleTransactionSnapshot) -> Unit = {},
    private val onCommittedPlayback: suspend (
        MobileSubtitleCommittedPlayback,
        CommittedSubtitle,
    ) -> Unit = { _, _ -> },
) {
    private data class PendingLocalSelection(
        val identity: SubtitleIdentity,
        val proposedState: SubtitleTransitionState,
        val context: MobileSubtitlePlaybackContext,
        val failedSnapshotKeys: MutableSet<String> = mutableSetOf(),
    )

    private data class QueuedSelection(
        val identity: SubtitleIdentity,
    )

    private val stagedRequests = Channel<org.siloserver.silo.model.playback.PendingSubtitle>(
        capacity = Channel.CONFLATED,
    )

    private var transition = SubtitleTransitionState.committed(SubtitleIdentity.Off)
    private var context: MobileSubtitlePlaybackContext? = null
    private var contentGeneration = 0L
    private var refreshGeneration = 0L
    private var subtitleIntentGeneration = 0L
    private var failureMessage: String? = null
    private var pendingLocalSelection: PendingLocalSelection? = null
    private var queuedSelection: QueuedSelection? = null
    private var commitInFlight = false
    private var resetDuringCommit = false

    val snapshot: MobileSubtitleTransactionSnapshot
        get() {
            val queuedIdentity = queuedSelection?.identity
            val localIdentity = pendingLocalSelection?.identity
            return MobileSubtitleTransactionSnapshot(
                transition = transition,
                pendingIdentity = queuedIdentity ?: localIdentity ?: transition.pending?.identity,
                localMountIdentity = if (queuedIdentity == null) localIdentity else null,
                failureMessage = failureMessage,
            )
        }

    init {
        scope.launch {
            for (pending in stagedRequests) {
                processStagedRequest(pending)
            }
        }
    }

    fun resetContent(
        context: MobileSubtitlePlaybackContext,
        committedIdentity: SubtitleIdentity,
    ) {
        if (commitInFlight) {
            resetDuringCommit = true
            queuedSelection = null
        }
        contentGeneration += 1
        refreshGeneration += 1
        subtitleIntentGeneration += 1
        this.context = context
        pendingLocalSelection = null
        transition = reduceSubtitleTransition(
            transition,
            SubtitleContentReset(committedIdentity),
        ).state.copy(
            committed = CommittedSubtitle(
                identity = committedIdentity,
                audioTrackIndex = context.audioTrackIndex,
                qualityPreference = context.qualityPreference,
            ),
        )
        failureMessage = null
        publish()
    }

    fun replaceSession(sessionId: String, subtitleTracks: List<PlayerSubtitleInfo>? = null) {
        val current = context ?: return
        if (commitInFlight) {
            resetDuringCommit = true
            queuedSelection = null
        }
        refreshGeneration += 1
        subtitleIntentGeneration += 1
        pendingLocalSelection = null
        context = current.copy(
            sessionId = sessionId,
            subtitleTracks = subtitleTracks ?: current.subtitleTracks,
        )
        transition = reduceSubtitleTransition(
            transition,
            SubtitleContentReset(transition.committed.identity),
        ).state.copy(committed = transition.committed)
        failureMessage = null
        publish()
    }

    fun updatePlaybackContext(updated: MobileSubtitlePlaybackContext) {
        val current = context
        if (current == null ||
            current.contentId != updated.contentId ||
            current.mediaFileId != updated.mediaFileId ||
            current.versionId != updated.versionId
        ) {
            resetContent(updated, transition.committed.identity)
            return
        }
        if (current.sessionId != updated.sessionId) {
            val nextSessionId = updated.sessionId
            if (nextSessionId == null) {
                if (commitInFlight) {
                    resetDuringCommit = true
                    queuedSelection = null
                }
                contentGeneration += 1
                refreshGeneration += 1
                subtitleIntentGeneration += 1
                context = updated
                pendingLocalSelection = null
                transition = reduceSubtitleTransition(
                    transition,
                    SubtitleContentReset(transition.committed.identity),
                ).state.copy(committed = transition.committed)
                failureMessage = null
                publish()
            } else {
                replaceSession(nextSessionId, updated.subtitleTracks)
                context = updated
            }
            return
        }
        context = updated
    }

    fun select(identity: SubtitleIdentity) {
        select(identity, explicit = true)
    }

    fun persistCommittedSelection() {
        context?.let { persist(transition.committed, it) }
    }

    fun beginRefresh(): MobileSubtitleRefreshOwner {
        refreshGeneration += 1
        val current = requireNotNull(context) {
            "Subtitle refresh cannot start before playback context is installed."
        }
        return MobileSubtitleRefreshOwner(
            contentGeneration = contentGeneration,
            contentId = current.contentId,
            mediaFileId = current.mediaFileId,
            versionId = current.versionId,
            sessionId = current.sessionId,
            refreshGeneration = refreshGeneration,
            subtitleIntentGeneration = subtitleIntentGeneration,
        )
    }

    fun ownsRefresh(owner: MobileSubtitleRefreshOwner): Boolean {
        val current = context ?: return false
        return owner.contentGeneration == contentGeneration &&
            owner.contentId == current.contentId &&
            owner.mediaFileId == current.mediaFileId &&
            owner.versionId == current.versionId &&
            owner.sessionId == current.sessionId &&
            owner.refreshGeneration == refreshGeneration &&
            owner.subtitleIntentGeneration == subtitleIntentGeneration
    }

    fun selectFromRefresh(
        owner: MobileSubtitleRefreshOwner,
        identity: SubtitleIdentity,
    ): Boolean {
        if (!ownsRefresh(owner)) return false
        select(identity, explicit = false)
        return true
    }

    fun reportMountedSelection(
        identity: SubtitleIdentity,
        selected: Boolean,
        snapshotKey: String?,
    ) {
        val pending = pendingLocalSelection?.takeIf { it.identity == identity } ?: return
        if (selected) {
            transition = pending.proposedState
            pendingLocalSelection = null
            failureMessage = null
            publish()
            persist(transition.committed, pending.context)
            return
        }

        val meaningfulSnapshotKey = snapshotKey?.takeIf(String::isNotBlank) ?: return
        if (!pending.failedSnapshotKeys.add(meaningfulSnapshotKey)) return
        if (pending.failedSnapshotKeys.size < MAX_LOCAL_MOUNT_SNAPSHOTS) return

        pendingLocalSelection = null
        failureMessage = "The selected subtitle could not be mounted."
        publish()
    }

    private fun select(identity: SubtitleIdentity, explicit: Boolean) {
        if (explicit) refreshGeneration += 1
        subtitleIntentGeneration += 1
        failureMessage = null

        if (commitInFlight) {
            pendingLocalSelection = null
            queuedSelection = QueuedSelection(identity)
            publish()
            return
        }

        applySelection(identity)
    }

    private fun applySelection(identity: SubtitleIdentity) {
        val selected = reduceSubtitleTransition(transition, SelectSubtitle(identity))
        val current = context
        val commitsSynchronously = current?.sessionId == null

        if (commitsSynchronously) {
            val committedState = if (selected.state.pending == null) {
                selected.state
            } else {
                reduceSubtitleTransition(
                    selected.state,
                    StagedSubtitleValidated(
                        generation = selected.state.pending!!.generation,
                        candidate = StagedSubtitleCandidate("mobile-local"),
                    ),
                ).state
            }
            transition = committedState
            pendingLocalSelection = null
            publish()
            current?.let { persist(committedState.committed, it) }
            return
        }

        if (identity.requiresLocalMountConfirmation()) {
            val proposedState = if (selected.state.pending == null) {
                selected.state
            } else {
                reduceSubtitleTransition(
                    selected.state,
                    StagedSubtitleValidated(
                        generation = selected.state.pending!!.generation,
                        candidate = StagedSubtitleCandidate("mobile-local"),
                    ),
                ).state
            }
            transition = transition.copy(
                pending = null,
                nextGeneration = proposedState.nextGeneration,
            )
            pendingLocalSelection = PendingLocalSelection(
                identity = identity,
                proposedState = proposedState,
                context = requireNotNull(current),
            )
            publish()
            return
        }

        pendingLocalSelection = null
        transition = selected.state
        publish()
        transition.pending?.let(stagedRequests::trySend)
    }

    private suspend fun processStagedRequest(
        requested: org.siloserver.silo.model.playback.PendingSubtitle,
    ) {
        val requestContext = context ?: return
        val requestSessionId = requestContext.sessionId ?: return
        if (transition.pending?.generation != requested.generation) return

        val request = MobileSubtitleStageRequest(
            generation = requested.generation,
            contentId = requestContext.contentId,
            mediaFileId = requestContext.mediaFileId,
            versionId = requestContext.versionId,
            sessionId = requestSessionId,
            positionSeconds = requestContext.positionSeconds,
            audioTrackIndex = requested.audioTrackIndex,
            qualityPreference = requested.qualityPreference,
            subtitleTrackIndex = requested.identity.serverTrackIndex(),
        )
        val staged = try {
            stagedPort.stage(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            ApiResult.NetworkError(error)
        }

        when (staged) {
            is ApiResult.Success -> processCandidate(requested, staged.data)
            is ApiResult.Error -> fail(requested.generation, staged.message)
            is ApiResult.NetworkError -> fail(
                requested.generation,
                staged.exception.message ?: "Subtitle selection failed.",
            )
        }
    }

    private suspend fun processCandidate(
        requested: org.siloserver.silo.model.playback.PendingSubtitle,
        candidate: MobileStagedSubtitleCandidate,
    ) {
        val validationFailure = candidate.validationFailure(requested.identity)
        if (validationFailure != null) {
            stagedPort.discard(candidate)
            fail(requested.generation, validationFailure)
            return
        }

        val validated = reduceSubtitleTransition(
            transition,
            StagedSubtitleValidated(
                generation = requested.generation,
                candidate = StagedSubtitleCandidate(candidate.id),
            ),
        )
        if (validated.state == transition) {
            stagedPort.discard(candidate)
            return
        }

        commitInFlight = true
        val commitResult = try {
            stagedPort.commit(candidate)
        } catch (cancellation: CancellationException) {
            commitInFlight = false
            throw cancellation
        } catch (error: Exception) {
            ApiResult.NetworkError(error)
        }
        commitInFlight = false

        if (resetDuringCommit) {
            resetDuringCommit = false
            val queued = queuedSelection
            queuedSelection = null
            queued?.let { applySelection(it.identity) }
            return
        }

        when (val committed = commitResult) {
            is ApiResult.Success -> {
                val oldContext = context ?: return
                val playback = committed.data.withRebasedDownloads(oldContext)
                transition = validated.state
                context = oldContext.copy(
                    sessionId = playback.sessionId,
                    subtitleTracks = playback.subtitleTracks,
                    audioTrackIndex = transition.committed.audioTrackIndex,
                    qualityPreference = transition.committed.qualityPreference,
                )
                refreshGeneration += 1
                failureMessage = null
                onCommittedPlayback(playback, transition.committed)
                val queued = queuedSelection
                queuedSelection = null
                if (queued == null) {
                    publish()
                    persist(transition.committed, requireNotNull(context))
                } else {
                    applySelection(queued.identity)
                }
            }
            is ApiResult.Error -> finishFailedCommit(
                generation = requested.generation,
                message = committed.message,
            )
            is ApiResult.NetworkError -> finishFailedCommit(
                generation = requested.generation,
                message = committed.exception.message ?: "Subtitle selection failed.",
            )
        }
    }

    private fun finishFailedCommit(generation: Long, message: String) {
        val queued = queuedSelection
        if (queued == null) {
            fail(generation, message)
            return
        }

        transition = reduceSubtitleTransition(
            transition,
            StagedSubtitleFailed(
                generation = generation,
                message = message,
            ),
        ).state
        queuedSelection = null
        failureMessage = null
        applySelection(queued.identity)
    }

    private fun fail(generation: Long, message: String) {
        val failed = reduceSubtitleTransition(
            transition,
            StagedSubtitleFailed(
                generation = generation,
                message = message,
            ),
        )
        if (failed.state == transition && failed.effects.isEmpty()) return
        transition = failed.state
        failureMessage = message
        publish()
    }

    private fun persist(
        committed: CommittedSubtitle,
        committedContext: MobileSubtitlePlaybackContext,
    ) {
        persistenceScope.launch {
            persistencePort.persist(committed, committedContext)
        }
    }

    private val persistenceScope: CoroutineScope = scope

    private fun publish() {
        onSnapshotChanged(snapshot)
    }

    private companion object {
        const val MAX_LOCAL_MOUNT_SNAPSHOTS = 3
    }
}

internal class PlaybackSessionManagerMobileSubtitleStagedReplanPort(
    private val manager: PlaybackSessionManager,
) : MobileSubtitleStagedReplanPort {
    override suspend fun stage(
        request: MobileSubtitleStageRequest,
    ): ApiResult<MobileStagedSubtitleCandidate> = when (
        val result = manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            message = "Applying subtitle selection.",
            positionSeconds = request.positionSeconds,
            audioTrackIndex = request.audioTrackIndex,
            subtitleTrackIndex = request.subtitleTrackIndex,
            qualityPreference = request.qualityPreference,
        )
    ) {
        is ApiResult.Success -> {
            val handle = result.data
            val ready = handle.candidate
            ApiResult.Success(
                MobileStagedSubtitleCandidate(
                    id = handle.candidateSessionId,
                    sessionId = handle.candidateSessionId,
                    selectedSubtitleIndex = ready.plan.selectedTracks.subtitle?.index,
                    subtitleMode = ready.plan.subtitle.mode,
                    hasSidecar = ready.plan.subtitle.artifact?.url?.isNotBlank() == true,
                    subtitleTracks = ready.session.subtitleUrls.orEmpty(),
                    managerHandle = handle,
                ),
            )
        }
        is ApiResult.Error -> result
        is ApiResult.NetworkError -> result
    }

    override suspend fun commit(
        candidate: MobileStagedSubtitleCandidate,
    ): ApiResult<MobileSubtitleCommittedPlayback> {
        val handle = candidate.managerHandle ?: return ApiResult.Error(
            code = 409,
            error = "missing_staged_subtitle_handle",
            message = "The staged subtitle candidate no longer has a commit handle.",
        )
        return when (val result = manager.commitStagedVideoReplan(handle)) {
            is ApiResult.Success -> ApiResult.Success(
                MobileSubtitleCommittedPlayback(
                    sessionId = result.data.session.sessionId,
                    subtitleTracks = result.data.session.subtitleUrls.orEmpty(),
                    ready = result.data,
                ),
            )
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    override suspend fun discard(candidate: MobileStagedSubtitleCandidate) {
        candidate.managerHandle?.let { manager.discardStagedVideoReplan(it) }
    }
}

private fun SubtitleIdentity.serverTrackIndex(): Int = when (this) {
    SubtitleIdentity.Off -> -1
    is SubtitleIdentity.ServerSidecar -> serverIndex
    is SubtitleIdentity.ServerBurnIn -> serverIndex
    is SubtitleIdentity.Embedded -> serverIndex
    is SubtitleIdentity.Downloaded,
    is SubtitleIdentity.LocalMedia3,
    -> error("Local subtitle identities must not request a staged server replan.")
}

private fun SubtitleIdentity.requiresLocalMountConfirmation(): Boolean =
    this is SubtitleIdentity.LocalMedia3 ||
        this is SubtitleIdentity.Downloaded ||
        this is SubtitleIdentity.Embedded

private fun MobileStagedSubtitleCandidate.validationFailure(
    identity: SubtitleIdentity,
): String? = when (identity) {
    SubtitleIdentity.Off -> if (
        selectedSubtitleIndex == null &&
        subtitleMode == PlaybackSubtitleModeV3.OFF &&
        !hasSidecar
    ) {
        null
    } else {
        "The candidate did not keep subtitles off."
    }
    is SubtitleIdentity.ServerSidecar -> when {
        selectedSubtitleIndex != identity.serverIndex ->
            "The candidate did not select the requested subtitle."
        subtitleMode != PlaybackSubtitleModeV3.RENDER &&
            subtitleMode != PlaybackSubtitleModeV3.CONVERT ->
            "The candidate did not render the requested sidecar."
        !hasSidecar -> "The candidate omitted the requested subtitle sidecar."
        else -> null
    }
    is SubtitleIdentity.ServerBurnIn -> when {
        selectedSubtitleIndex != identity.serverIndex ->
            "The candidate did not select the requested subtitle."
        subtitleMode != PlaybackSubtitleModeV3.BURN_IN ->
            "The candidate did not burn in the requested subtitle."
        else -> null
    }
    is SubtitleIdentity.Embedded,
    is SubtitleIdentity.Downloaded,
    is SubtitleIdentity.LocalMedia3,
    -> "A local subtitle identity unexpectedly reached staged validation."
}

private fun MobileSubtitleCommittedPlayback.withRebasedDownloads(
    oldContext: MobileSubtitlePlaybackContext,
): MobileSubtitleCommittedPlayback {
    val downloadedPredicate: (PlayerSubtitleInfo) -> Boolean = {
        it.downloadId != null || it.source.equals("downloaded", ignoreCase = true)
    }
    val downloaded = oldContext.subtitleTracks
        .filter(downloadedPredicate)
        .map { track ->
            track.copy(url = rebaseDownloadedSubtitleUrl(track.url, sessionId))
        }
    val candidateByIndex = subtitleTracks
        .filterNot(downloadedPredicate)
        .associateBy(PlayerSubtitleInfo::index)
    val retainedCatalog = oldContext.subtitleTracks
        .filterNot(downloadedPredicate)
        .map { old ->
            candidateByIndex[old.index]?.let { candidate ->
                candidate.copy(
                    language = candidate.language ?: old.language,
                    codec = candidate.codec ?: old.codec,
                    label = candidate.label ?: old.label,
                    forced = candidate.forced ?: old.forced,
                    catalogLabel = old.catalogLabel ?: candidate.catalogLabel,
                    catalogSource = old.catalogSource ?: candidate.catalogSource,
                    isDefault = old.isDefault ?: candidate.isDefault,
                )
            } ?: old.copy(url = "")
        }
    val retainedIndexes = retainedCatalog.mapTo(mutableSetOf(), PlayerSubtitleInfo::index)
    val additionalCandidates = subtitleTracks.filterNot(downloadedPredicate)
        .filterNot { it.index in retainedIndexes }
    return copy(
        subtitleTracks = retainedCatalog + additionalCandidates + downloaded,
    )
}
