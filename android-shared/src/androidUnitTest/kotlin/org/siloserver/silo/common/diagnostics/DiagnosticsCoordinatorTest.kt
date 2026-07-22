package org.siloserver.silo.common.diagnostics

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun profileChangeClosesGateBeforeMutationAndInvalidatesTimedCapture() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        val fixture = fixture(
            identity,
            transitions,
            capture,
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.coordinator.startTimedCapture()

        transitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            assertTrue(capture.gateClosed)
            identity.current = ADULT_B
        }
        fixture.coordinator.refresh()

        assertEquals(TimedCaptureStatus.INVALIDATED, fixture.coordinator.state.value.timedCapture.status)
        assertTrue(capture.cancelled.isNotEmpty())
        assertFalse(capture.hasPersistentEvidence)
    }

    @Test
    fun neverClosesCaptureAndPurgesAllBindingEvidence() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        val fixture = fixture(
            identity,
            transitions,
            capture,
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.coordinator.startTimedCapture()
        fixture.evidence.add(ADULT_A.binding)

        fixture.coordinator.setConsent(DiagnosticsConsentMode.NEVER)

        assertEquals(DiagnosticsConsentMode.NEVER, fixture.coordinator.state.value.consent)
        assertFalse(capture.hasPersistentEvidence)
        assertFalse(ADULT_A.binding in fixture.evidence)
        assertEquals(listOf(ADULT_A.binding), fixture.purgedBindings)
    }

    @Test
    fun signOutPurgesTheOldBindingAfterTheSynchronousGateCloses() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        val fixture = fixture(
            identity,
            transitions,
            capture,
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.evidence.add(ADULT_A.binding)

        transitions.changing(IdentityTransitionKind.SIGN_OUT) {
            assertTrue(capture.gateClosed)
            identity.current = null
        }
        fixture.coordinator.refresh()

        assertFalse(ADULT_A.binding in fixture.evidence)
        assertEquals(listOf(ADULT_A.binding), fixture.purgedBindings)
    }

    @Test
    fun oneShotCaptureBuildsAProfileBoundManualReportFromTheCurrentRing() = runTest {
        val files = temporaryFolder.newFolder()
        val ring = LogRing()
        ring.offer("{\"cat\":\"playback\",\"msg\":\"safe\"}")
        ring.offer("{\"cat\":\"network\",\"msg\":\"safe\"}")
        val store = FilePendingReportStore(files, nowMs = { 20L })
        val controller = FileDiagnosticsCaptureController(
            logBuffer = ring,
            fileLogger = DiagnosticsFileLogger(files, UnconfinedTestDispatcher(testScheduler)),
            reports = store,
            deviceSnapshots = DeviceSnapshotCollector(StableDeviceProbe(), nowRfc3339 = { "2026-07-22T00:00:00Z" }),
            deviceSnapshotCache = DeviceSnapshotCache(),
            environment = ExitReportEnvironment(
                appVersion = "1.0",
                appBuild = "1",
                platform = DiagnosticsPlatform.ANDROID_TV,
                osVersion = "36",
                deviceSummary = DiagnosticsDeviceSummary("NVIDIA", "Shield", "Android 36", "tv"),
            ),
            nowMs = { 20L },
            sessionIdFactory = { "manual-session" },
        )

        val report = controller.captureNow(ADULT_A)

        requireNotNull(report)
        assertEquals(DiagnosticsReportType.MANUAL, report.manifest.report.type)
        assertEquals("adult-a", report.binding.profileId)
        assertEquals("server-1", report.manifest.destination.serverInstanceId)
        assertTrue(report.directory.resolve("device.json").isFile)
        assertTrue(report.directory.resolve("logs.jsonl").readText().contains("safe"))
        assertEquals(2, report.manifest.logSummary.lines)
        assertTrue(report.manifest.logSummary.bytesGzip > 0)
        assertEquals(
            listOf(DiagnosticsLogCategory.PLAYBACK, DiagnosticsLogCategory.NETWORK),
            report.manifest.logSummary.categories,
        )
    }

    private fun fixture(
        identity: MutableIdentityResolver,
        transitions: DefaultIdentityTransitionBarrier,
        capture: RecordingCaptureController,
        scope: CoroutineScope,
        actorDispatcher: CoroutineDispatcher,
    ): Fixture {
        val files = temporaryFolder.newFolder()
        val purgedBindings = mutableListOf<DiagnosticsBinding>()
        val evidence = mutableSetOf<DiagnosticsBinding>()
        val settings = DiagnosticsSettingsStore(
            dataStore = PreferenceDataStoreFactory.create {
                File(files, "diagnostics-${System.nanoTime()}.preferences_pb")
            },
            bindingPurger = DiagnosticsBindingPurger { binding ->
                purgedBindings += binding
                evidence -= binding
                capture.purge(binding)
            },
        )
        val reports = FilePendingReportStore(files)
        val coordinator = DefaultDiagnosticsCoordinator(
            scope = scope,
            actorDispatcher = actorDispatcher,
            identity = identity,
            identityTransitions = transitions,
            settings = settings,
            reports = reports,
            capture = capture,
            uploader = DiagnosticsUploader { DiagnosticsUploadDecision.KeptUnavailable },
            uploadScheduler = DiagnosticsUploadScheduler { },
        )
        return Fixture(coordinator, evidence, purgedBindings)
    }

    private class MutableIdentityResolver(
        var current: DiagnosticsCaptureContext?,
    ) : DiagnosticsIdentityResolver {
        override suspend fun resolve(requirePersistentCapture: Boolean): DiagnosticsCaptureContext? = current
    }

    private class RecordingCaptureController : DiagnosticsCaptureController {
        var gateClosed = false
        var hasPersistentEvidence = false
        val cancelled = mutableListOf<Long>()
        private var nextGeneration = 0L

        override fun closeGate() {
            gateClosed = true
            hasPersistentEvidence = false
        }

        override suspend fun start(context: DiagnosticsCaptureContext): ActiveDiagnosticsCapture {
            gateClosed = false
            hasPersistentEvidence = true
            return ActiveDiagnosticsCapture(++nextGeneration, context.identityKey, 10L)
        }

        override suspend fun stop(
            active: ActiveDiagnosticsCapture,
            context: DiagnosticsCaptureContext,
        ): PendingReport? {
            hasPersistentEvidence = false
            return null
        }

        override suspend fun cancel(active: ActiveDiagnosticsCapture) {
            cancelled += active.generation
            hasPersistentEvidence = false
        }

        override suspend fun captureNow(context: DiagnosticsCaptureContext): PendingReport? = null

        override suspend fun purge(binding: DiagnosticsBinding) {
            hasPersistentEvidence = false
        }
    }

    private class StableDeviceProbe : DiagnosticsDeviceProbe {
        override fun identity() = DiagnosticsIdentitySnapshot(
            manufacturer = "NVIDIA",
            model = "Shield",
            device = "foster",
            osRelease = "16",
            sdkInt = 36,
            formFactor = "tv",
            buildFingerprintHash = "0".repeat(32),
        )

        override fun display(): DiagnosticsDisplaySnapshot? = null
        override fun audio(): DiagnosticsAudioSnapshot? = null
        override fun codecs(): List<DiagnosticsCodecSnapshot>? = null
        override fun network(): DiagnosticsNetworkSnapshot? = null
    }

    private data class Fixture(
        val coordinator: DiagnosticsCoordinator,
        val evidence: MutableSet<DiagnosticsBinding>,
        val purgedBindings: MutableList<DiagnosticsBinding>,
    )

    private companion object {
        val ADULT_A = DiagnosticsCaptureContext(
            binding = DiagnosticsBinding("server-1", "user-1"),
            profileId = "adult-a",
            profileEligible = true,
            noticeVersion = 2,
            status = DiagnosticsAvailabilityStatus.AVAILABLE,
            ownershipGeneration = 1,
        )
        val ADULT_B = ADULT_A.copy(profileId = "adult-b", ownershipGeneration = 2)
    }
}
