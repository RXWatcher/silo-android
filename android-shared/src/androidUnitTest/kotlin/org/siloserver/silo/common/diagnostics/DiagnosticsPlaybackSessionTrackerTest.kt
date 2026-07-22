package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsPlaybackSessionTrackerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun trackerDeduplicatesBoundsAndKeepsNewestSessions() {
        val tracker = DiagnosticsPlaybackSessionTracker(maxSessions = 3, maxIdChars = 8)

        tracker.record("one-long-session")
        tracker.record("two-long-session")
        tracker.record("three-long-session")
        tracker.record("two-long-session")
        tracker.record("four-long-session")
        tracker.record(" ")

        assertEquals(listOf("three-lo", "two-long", "four-lon"), tracker.snapshot())
        assertTrue(tracker.snapshot().all { it.length <= 8 })
    }

    @Test
    fun clearDropsAllCorrelationState() {
        val tracker = DiagnosticsPlaybackSessionTracker()
        tracker.record("session-1")

        tracker.clear()

        assertEquals(emptyList(), tracker.snapshot())
    }

    @Test
    fun runtimePublisherIncludesSessionsAndPrivacyGateClearsThem() = runTest {
        val tracker = DiagnosticsPlaybackSessionTracker().apply { record("session-1") }
        val publisher = DefaultDiagnosticsRuntimePublisher(
            ledger = DiagnosticsRunLedger(temporaryFolder.newFolder()),
            logBuffer = LogRing(),
            deviceSnapshots = DeviceSnapshotCollector(EmptyProbe),
            deviceSnapshotCache = DeviceSnapshotCache(),
            redactionTokens = DiagnosticsRedactionTokenProvider { emptyList() },
            playbackSessions = tracker,
            captureSessionIdFactory = { "capture-1" },
        )
        val context = DiagnosticsCaptureContext(
            binding = DiagnosticsBinding("server-1", "user-1"),
            profileId = "profile-1",
            profileEligible = true,
            noticeVersion = 1,
            status = DiagnosticsAvailabilityStatus.AVAILABLE,
            ownershipGeneration = 1,
        )

        publisher.publish(context)

        assertEquals(listOf("session-1"), CrashCapture.currentSnapshotForTests().playbackSessionIds)

        publisher.closeGate()

        assertEquals(emptyList(), tracker.snapshot())
        assertEquals(emptyList(), CrashCapture.currentSnapshotForTests().playbackSessionIds)
    }

    private object EmptyProbe : DiagnosticsDeviceProbe {
        override fun identity() = DiagnosticsIdentitySnapshot(
            manufacturer = "Google",
            model = "Streamer",
            device = "streamer",
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
}
