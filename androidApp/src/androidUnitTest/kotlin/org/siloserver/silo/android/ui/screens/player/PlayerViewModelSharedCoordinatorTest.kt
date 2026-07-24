package org.siloserver.silo.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerViewModelSharedCoordinatorTest {
    private val viewModelSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt",
    ).readText()
    private val moduleSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/di/AndroidModule.kt",
    ).readText()
    private val playerScreenSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt",
    ).readText()
    private val freshRestoreSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileFreshSubtitleRestore.kt",
    ).readText()

    @Test
    fun mobilePlayerViewModelStartsRemotePlaybackThroughSharedCoordinator() {
        assertTrue(
            viewModelSource.contains("VideoPlaybackSessionCoordinator"),
            "Mobile player ViewModel must depend on the shared video playback coordinator",
        )
        assertTrue(
            viewModelSource.contains("VideoPlaybackStartRequest("),
            "Mobile player ViewModel must build the shared video playback start request",
        )
        assertTrue(
            viewModelSource.contains("contentId = contentId"),
            "Mobile player ViewModel must pass contentId into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("preferredFileId = preferredFileId"),
            "Mobile player ViewModel must pass the requested file id into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("roomId = null"),
            "Mobile player ViewModel must explicitly pass no Watch Together room id for normal mobile startup",
        )
        assertTrue(
            viewModelSource.contains("resumePositionOverride = resumePositionOverride"),
            "Mobile player ViewModel must preserve explicit resume override semantics",
        )
        assertTrue(
            viewModelSource.contains("subtitleTrackIndex = initialSubtitleTrackIndex"),
            "Mobile player ViewModel must pass initial subtitle selection into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("videoPlaybackCoordinator.start("),
            "Mobile player ViewModel must delegate remote startup to the shared coordinator",
        )
    }

    @Test
    fun mobilePlayerViewModelKeepsOfflineFallbackBeforeRemoteCoordinatorStartup() {
        assertTrue(
            viewModelSource.contains("private suspend fun tryLocalPlayback"),
            "Mobile player ViewModel must retain the offline/local playback fallback",
        )

        val loadContentBody = viewModelSource
            .substringAfter("fun loadContent(")
            .substringBefore("private fun startIntroAutoSkipObserver")
        val localIndex = loadContentBody.indexOf("tryLocalPlayback(")
        val coordinatorIndex = loadContentBody.indexOf("videoPlaybackCoordinator.start(")

        assertTrue(localIndex >= 0, "loadContent must try local playback")
        assertTrue(coordinatorIndex >= 0, "loadContent must start remote playback through the coordinator")
        assertTrue(
            localIndex < coordinatorIndex,
            "Mobile offline/local playback fallback must run before remote coordinator startup",
        )
        assertFalse(
            loadContentBody.contains("playbackSessionManager.startSession("),
            "Mobile initial remote startup must not start sessions directly in PlayerViewModel",
        )
    }

    @Test
    fun mobilePlayerRefreshesServerBackedSettingsAfterOfflineFallbackBeforeRemoteStartup() {
        val loadContentBody = viewModelSource
            .substringAfter("fun loadContent(")
            .substringBefore("private fun startIntroAutoSkipObserver")
        val localIndex = loadContentBody.indexOf("tryLocalPlayback(")
        val refreshIndex = loadContentBody.indexOf("playerSettingsStore.refreshFromServer()")
        val coordinatorIndex = loadContentBody.indexOf("videoPlaybackCoordinator.start(")

        assertTrue(localIndex >= 0, "loadContent must keep the offline/local fast path")
        assertTrue(refreshIndex >= 0, "Remote startup must refresh effective server settings")
        assertTrue(coordinatorIndex >= 0, "Remote startup must use the coordinator")
        assertTrue(
            localIndex < refreshIndex,
            "Mobile must not force a settings network call before local downloaded playback",
        )
        assertTrue(
            refreshIndex < coordinatorIndex,
            "Mobile must pull user/device effective settings before choosing remote quality/subtitle defaults",
        )
    }

    @Test
    fun freshPlaybackHydratesDownloadedRowsBeforePersistedSubtitleRestore() {
        val body = viewModelSource
            .substringAfter("private suspend fun applyCoordinatorStateToUi(")
            .substringBefore("private fun startIntroAutoSkipObserver")
        val hydrateIndex = freshRestoreSource.indexOf("loadDownloadedSubtitles(mediaFileId)")
        val mergeIndex = freshRestoreSource.indexOf("mergeDownloadedSubtitles(")
        val restoreIndex = freshRestoreSource.indexOf("decodeSubtitleIdentityPreference(")

        assertTrue(body.contains("prepareMobileFreshSubtitleRestore("))
        assertTrue(body.contains("loadDownloadedSubtitles = subtitlesRepository::list"))
        assertTrue(hydrateIndex >= 0, "Fresh remote playback must fetch downloaded subtitle rows")
        assertTrue(mergeIndex > hydrateIndex, "Fresh remote playback must merge fetched downloads")
        assertTrue(
            restoreIndex > mergeIndex,
            "Persisted Downloaded/legacy Local identity must resolve only after download hydration",
        )
        assertTrue(
            freshRestoreSource.contains("persistedSelectionIdentity"),
            "Fresh restore must preserve authoritative downloaded subtitle identity",
        )
    }

    @Test
    fun freshPlaybackDownloadHydrationFailureIsBestEffort() {
        val body = viewModelSource
            .substringAfter("private suspend fun applyCoordinatorStateToUi(")
            .substringBefore("private fun startIntroAutoSkipObserver")

        assertTrue(freshRestoreSource.contains("is ApiResult.Success ->"))
        assertTrue(freshRestoreSource.contains("else -> emptyList()"))
        assertTrue(body.contains("freshSubtitleRestore.persistedPreferencePresent"))
        assertTrue(body.contains("MobileSubtitleAutoSelection.NoChange -> null"))
    }

    @Test
    fun loadContentCreatesAndCancelsGenerationOwnedJob() {
        val body = viewModelSource
            .substringAfter("fun loadContent(")
            .substringBefore("private fun startIntroAutoSkipObserver")

        assertTrue(viewModelSource.contains("private val loadOwners"))
        assertTrue(viewModelSource.contains("private var loadJob: Job?"))
        assertTrue(body.contains("loadJob?.cancel()"))
        assertTrue(body.contains("loadOwners.begin("))
    }

    @Test
    fun staleReadyLoadStopsReturnedSessionBeforePublishing() {
        val body = viewModelSource
            .substringAfter("private suspend fun applyCoordinatorStateToUi(")
            .substringBefore("private fun startIntroAutoSkipObserver")
        val cleanupBody = viewModelSource
            .substringAfter("private suspend fun stopStaleReadySession(")
            .substringBefore("private suspend fun applyCoordinatorStateToUi(")

        assertTrue(body.contains("ownsLoad("))
        assertTrue(body.contains("stopStaleReadySession("))
        assertTrue(cleanupBody.contains("playbackSessionManager.stopSession(sessionId)"))
        assertTrue(body.contains("return"))
    }

    @Test
    fun exitAndClearInvalidatePlayerLoadOwner() {
        val exitBody = viewModelSource
            .substringAfter("fun onExit()")
            .substringBefore("private fun scheduleControlsHide")
        val clearBody = viewModelSource.substringAfter("override fun onCleared()")

        assertTrue(exitBody.contains("loadOwners.invalidate()"))
        assertTrue(exitBody.contains("loadJob?.cancel()"))
        assertTrue(clearBody.contains("loadOwners.invalidate()"))
        assertTrue(clearBody.contains("loadJob?.cancel()"))
    }

    @Test
    fun trackPersistenceUsesAtomicLogicalPortWrite() {
        val persistenceBody = viewModelSource
            .substringAfter("persistencePort = object : MobileSubtitlePersistencePort")
            .substringBefore("onSnapshotChanged =")

        assertTrue(persistenceBody.contains("userItemStatePort.recordTrackSelection("))
        assertFalse(persistenceBody.contains("recordAudioTrackSelection("))
        assertFalse(persistenceBody.contains("recordSubtitleTrackSelection("))
    }

    @Test
    fun subtitleOnlyPersistencePreservesUnresolvedAudioFingerprint() {
        val persistenceBody = viewModelSource
            .substringAfter("persistencePort = object : MobileSubtitlePersistencePort")
            .substringBefore("onSnapshotChanged =")

        assertTrue(persistenceBody.contains("TrackSelectionFingerprintUpdate.Preserve"))
        assertTrue(persistenceBody.contains("TrackSelectionFingerprintUpdate.Set"))
        assertTrue(
            persistenceBody.contains(
                "subtitleUpdate = TrackSelectionFingerprintUpdate.Set(",
            ),
        )
        assertTrue(
            persistenceBody.indexOf("TrackSelectionFingerprintUpdate.Preserve") <
                persistenceBody.indexOf("userItemStatePort.recordTrackSelection("),
        )
    }

    @Test
    fun normalExitAwaitsFinalTrackPreferenceFlushBeforeTeardown() {
        val exitBody = viewModelSource
            .substringAfter("fun onExit()")
            .substringBefore("private fun scheduleControlsHide")

        assertTrue(exitBody.contains("persistCommittedSelectionAndFlush("))
        assertTrue(
            exitBody.indexOf("persistCommittedSelectionAndFlush(") <
                exitBody.indexOf("sessionLifecycle.stop("),
        )
    }

    @Test
    fun onClearedRequestsContainedNonBlockingTrackPersistenceFallback() {
        val clearBody = viewModelSource
            .substringAfter("override fun onCleared()")

        assertTrue(
            clearBody.contains("requestDurableFinalPersistence("),
            "onCleared must request a contained non-blocking fallback, not add a main-thread blocking track write",
        )
    }

    @Test
    fun loadContentRethrowsCoroutineCancellation() {
        val loadContentBody = viewModelSource
            .substringAfter("fun loadContent(")
            .substringBefore("private fun startIntroAutoSkipObserver")

        assertTrue(viewModelSource.contains("import kotlinx.coroutines.CancellationException"))
        assertTrue(loadContentBody.contains("catch (e: CancellationException)"))
        assertTrue(loadContentBody.contains("throw e"))
        assertTrue(loadContentBody.contains("playerSettingsStore.refreshFromServer()"))
    }

    @Test
    fun mobileVersionSwitchRestartsThroughTheV3Coordinator() {
        val body = viewModelSource
            .substringAfter("private fun startVersionPlayback(")
            .substringBefore("/**\n     * Handles a failed quality/version switch.")
        assertTrue(body.contains("sessionLifecycle.stop("))
        assertTrue(body.contains("loadContent("))
        assertTrue(body.contains("preferredFileId = version.fileId"))
        assertTrue(body.contains("resumePositionOverride = state.position"))
        assertTrue(body.contains("suppressResumeRewind = true"))
        assertFalse(body.contains("startSessionV2("))
        assertFalse(body.contains("startTranscodeFallback("))
    }

    @Test
    fun mobileUnsupportedPlaybackUsesV3ReplanAndAdoptsReplacement() {
        val body = viewModelSource
            .substringAfter("private fun startProtocolV3Replan(")
            .substringBefore("private fun Playability.failureClassification")
        assertTrue(body.contains("replanActiveVideoSession("))
        assertTrue(body.contains("sessionLifecycle.adoptActiveSession("))
        assertTrue(body.contains("decision.plan.stream.headers"))
        assertFalse(body.contains("startTranscodeFallback"))
    }

    @Test
    fun mobileSubtitleSelectionDelegatesToTransactionalAdapterWithoutOptimisticMutation() {
        val body = viewModelSource
            .substringAfter("fun onSelectSubtitle(index: Int)")
            .substringBefore("/** Select an audio track")

        assertTrue(body.contains("mobileSubtitleTransactions.select("))
        assertFalse(body.contains("selectedSubtitleIndex = index"))
        assertFalse(body.contains("startProtocolV3Replan("))
        assertFalse(body.contains("recordSubtitleTrackSelection("))
    }

    @Test
    fun mobileAudioSelectionJoinsTheTransactionalReducerWithoutOptimisticMutation() {
        val body = viewModelSource
            .substringAfter("fun onSelectAudio(index: Int)")
            .substringBefore("// ---- Subtitle suite")

        assertTrue(body.contains("mobileSubtitleTransactions.selectAudio("))
        assertTrue(body.contains("selectedServerAudioTrackIndex("))
        assertFalse(body.contains("selectedAudioIndex = index"))
        assertFalse(body.contains("persistAudioTrackSelection("))
        assertFalse(body.contains("startProtocolV3Replan("))
    }

    @Test
    fun mobileSubtitleRefreshUsesFullOwnerAndReducerAutoSelection() {
        val body = viewModelSource
            .substringAfter("private suspend fun doRefreshSubtitles(")
            .substringBefore("/** Refresh the transcription quota")

        assertTrue(body.contains("mobileSubtitleTransactions.beginRefresh()"))
        assertTrue(body.contains("mobileSubtitleTransactions.ownsRefresh(owner)"))
        assertTrue(body.contains("mobileSubtitleTransactions.selectFromRefresh("))
        assertFalse(body.contains("selectedSubtitleIndex = autoIndex"))
    }

    @Test
    fun mobilePlayerPublishesCommittedAndPendingSubtitleStateSeparately() {
        assertTrue(viewModelSource.contains("val committedSubtitleIdentity: SubtitleIdentity = SubtitleIdentity.Off"))
        assertTrue(viewModelSource.contains("val pendingSubtitleIdentity: SubtitleIdentity? = null"))
        assertTrue(viewModelSource.contains("val localSubtitleMountIdentity: SubtitleIdentity? = null"))
        assertTrue(viewModelSource.contains("val subtitleApplying: Boolean = false"))
        assertTrue(viewModelSource.contains("committedSubtitleIdentity = snapshot.committedIdentity"))
        assertTrue(viewModelSource.contains("selectedSubtitleIndex = resolveMobileSubtitleOrdinal("))
        assertTrue(viewModelSource.contains("pendingSubtitleIdentity = snapshot.pendingIdentity"))
        assertTrue(viewModelSource.contains("localSubtitleMountIdentity = snapshot.localMountIdentity"))
    }

    @Test
    fun mobilePlayerCommitsLocalSubtitleOnlyAfterMountedResolverConfirmation() {
        assertTrue(
            viewModelSource.contains("mobileSubtitleTransactions.reportMountedSelection("),
            "PlayerViewModel must report the actual Media3 mount result to the transaction adapter",
        )
        assertTrue(
            playerScreenSource.contains("liveState.localSubtitleMountIdentity"),
            "Track resolution must target the provisional local subtitle, not the prior committed row",
        )
        assertTrue(
            playerScreenSource.contains("selectMountedSubtitle(identity = targetIdentity)"),
            "Track resolution must select by typed identity rather than a lossy app-list ordinal",
        )
        assertFalse(
            playerScreenSource.contains("committedSubtitleOrdinal("),
            "PlayerScreen must not gate typed mounted selection through a lossy ordinal mapper",
        )
        assertTrue(
            playerScreenSource.contains("viewModel.onPendingSubtitleMountResult("),
            "PlayerScreen must return mounted resolver success or failure to PlayerViewModel",
        )
        assertTrue(
            playerScreenSource.contains("media3TextTrackSnapshotKey("),
            "Mount confirmation must use the real Media3 track snapshot",
        )
        assertTrue(
            playerScreenSource.contains("settled = backend.player.playbackState == Player.STATE_READY") &&
                playerScreenSource.contains("settled = videoBackend?.player?.playbackState == Player.STATE_READY"),
            "A stable READY snapshot must make an unresolved local mount terminal",
        )
    }

    @Test
    fun burnInCommitDisablesMedia3TextWithoutExternalSubtitleRefresh() {
        val selectionEffect = playerScreenSource
            .substringAfter("// Handle subtitle selection")
            .substringBefore("LaunchedEffect(mediaController)")

        assertTrue(selectionEffect.contains("targetIdentity is SubtitleIdentity.ServerBurnIn"))
        assertTrue(
            selectionEffect.contains(
                "backend.selectMountedSubtitle(identity = SubtitleIdentity.Off)",
            ),
            "burn-in must explicitly disable Media3 text while pixels come from video",
        )
    }

    @Test
    fun mobileRecoveryIsSingleFlightAndAttemptScoped() {
        val body = viewModelSource
            .substringAfter("private fun startProtocolV3Replan(")
            .substringBefore("private fun Playability.failureClassification")
        // Single flight, but a user track/quality change is queued (newest
        // wins) and re-driven on completion instead of being silently dropped.
        assertTrue(body.contains("if (recoveryJob?.isActive == true || serverSeekRecoveryInFlight) {"))
        assertTrue(body.contains("queuedInvalidationReplan = classification to notice"))
        assertTrue(body.contains("clientPlaybackContext = playbackContext"))
        assertTrue(body.contains("capabilities = capabilities"))
        assertTrue(body.contains("positionSeconds = state.position"))
        assertTrue(body.contains("audioTrackIndex = selectedAudioTrackIndex"))
        assertTrue(body.contains("selectedServerAudioTrackIndex("))
        assertTrue(body.contains("subtitleTrackIndex = selectedSubtitleTrackIndex"))
        assertTrue(body.contains("mobileSubtitleTransactions.hasActiveTransaction"))
        assertTrue(body.contains("mobileSubtitleTransactions.invalidate()"))
        assertTrue(body.contains("mobileSubtitleTransactions.restoreCommittedLocalMount()"))
        assertTrue(body.contains("rebaseDownloadedSubtitleUrl("))
    }

    @Test
    fun mobilePlaybackStarterOwnsProtocolV3Startup() {
        val starterSource = java.io.File(
            "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileVideoPlaybackStarter.kt",
        ).readText()
        assertTrue(starterSource.contains("startVideoSessionV3("))
        assertTrue(starterSource.contains("detectPlaybackContext("))
        assertTrue(starterSource.contains("formFactor = \"mobile\""))
        assertTrue(starterSource.contains("subtitleTrackIndex = request.subtitleTrackIndex"))
        assertTrue(starterSource.contains("VideoSessionStartV3.ServerUpgradeRequired"))
        assertTrue(starterSource.contains("playbackPlanV3 = readyV3.plan"))
        assertTrue(starterSource.contains("requestHeaders = readyV3.plan.stream.headers"))
        assertFalse(starterSource.contains("startSessionV2("))
        assertFalse(starterSource.contains("startTranscodeFallback("))
        assertFalse(starterSource.contains("playMethod = PlayMethod.DIRECT"))
        assertTrue(starterSource.contains("resolvePlaybackStartRequestPosition("))
        assertTrue(starterSource.contains("readyV3.plan.timeline.playerStartSeconds"))
        assertTrue(starterSource.contains("readyV3.plan.timeline.sourceStartSeconds"))
        assertTrue(starterSource.contains("adoptActiveSession("))
    }

    @Test
    fun mobilePlayerViewModelUsesSharedVersionSelectorForUiFallback() {
        assertTrue(
            viewModelSource.contains("selectPlaybackVersion("),
            "Mobile UI fallback selection must use the same shared version selector as playback startup.",
        )
        assertFalse(
            viewModelSource.contains("private fun preferredVersionIndex("),
            "Mobile PlayerViewModel must not carry a second quality-ranking implementation.",
        )
        assertFalse(
            viewModelSource.contains("private fun resolutionRank("),
            "Resolution ranking belongs in the shared playback selector.",
        )
    }

    @Test
    fun androidModuleWiresMobileStarterAndCoordinatorIntoPlayerViewModel() {
        assertTrue(
            moduleSource.contains("MobileVideoPlaybackStarter"),
            "Android Koin module must provide the mobile video playback starter",
        )
        assertTrue(
            moduleSource.contains("VideoPlaybackSessionCoordinator"),
            "Android Koin module must provide the shared video playback coordinator",
        )
        assertTrue(
            moduleSource.contains("mobileVideoPlaybackStarter"),
            "Android Koin module should name the mobile starter binding",
        )
        assertTrue(
            moduleSource.contains("videoPlaybackCoordinator = get()"),
            "Android Koin module must inject the shared coordinator into PlayerViewModel",
        )
    }
}
