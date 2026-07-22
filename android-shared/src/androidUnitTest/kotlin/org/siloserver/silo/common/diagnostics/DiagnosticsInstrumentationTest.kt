package org.siloserver.silo.common.diagnostics

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsInstrumentationTest {
    @AfterTest
    fun tearDown() {
        SiloLog.installSink(null)
    }

    @Test
    fun networkEvidenceHasNoQueryBodyOrIdentifier() {
        val lines = mutableListOf<String>()
        SiloLog.installSink { lines += it }

        DiagnosticsNetworkLogger.completed(
            method = "GET",
            rawPath = "/api/v1/items/private-item-id?access_token=secret",
            status = 200,
            durationMs = 42,
        )

        val line = lines.single()
        assertFalse(line.contains("?"))
        assertFalse(line.contains("body", ignoreCase = true))
        assertFalse(line.contains("private-item-id"))
        assertFalse(line.contains("secret"))
        assertTrue(line.contains("duration_ms"))
        assertTrue(line.contains("/api/v1/items/{id}"))
    }

    @Test
    fun statsSnapshotsRequireDetailedCaptureAndFiveSecondCadence() {
        val cadence = DiagnosticsStatsCadence(intervalMs = 5_000)

        assertFalse(cadence.shouldEmit(detailedCapture = false, nowMs = 10_000))
        assertTrue(cadence.shouldEmit(detailedCapture = true, nowMs = 10_000))
        assertFalse(cadence.shouldEmit(detailedCapture = true, nowMs = 14_999))
        assertTrue(cadence.shouldEmit(detailedCapture = true, nowMs = 15_000))
    }

    @Test
    fun trackAndSubtitleTextAreNeverCaptured() {
        val lines = mutableListOf<String>()
        SiloLog.installSink { lines += it }

        DiagnosticsPlaybackLogger.tracksChanged()

        val line = lines.single()
        assertTrue(line.contains("tracks changed"))
        assertFalse(line.contains("onCues"))
        assertFalse(line.contains("cue.text"))
        assertFalse(line.contains("subtitle text"))
    }

    @Test
    fun unknownNavigationRouteCannotBecomeEvidence() {
        val lines = mutableListOf<String>()
        SiloLog.installSink { lines += it }

        DiagnosticsLifecycleLogger.route("private-title/123")

        val line = lines.single()
        assertFalse(line.contains("private-title"))
        assertTrue(line.contains("route:unknown"))
    }
}
