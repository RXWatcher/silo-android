package org.siloserver.silo.common.telemetry

import android.util.Log

/** Severity for structured playback telemetry lines. */
enum class TelemetryLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Structured playback telemetry sink. Playback and cast code report notable
 * decisions and failures here as a single message plus a flat key/value map,
 * so the "why did playback do that" trail is greppable in one place. Backed
 * by logcat.
 */
object PlaybackTelemetry {
    private const val TAG = "PlaybackTelemetry"

    fun log(
        message: String,
        data: Map<String, Any?> = emptyMap(),
        level: TelemetryLevel = TelemetryLevel.INFO,
    ) {
        val line = if (data.isEmpty()) {
            message
        } else {
            message + " " + data.entries.joinToString(" ") { "${it.key}=${it.value}" }
        }
        when (level) {
            TelemetryLevel.ERROR -> Log.e(TAG, line)
            TelemetryLevel.WARN -> Log.w(TAG, line)
            else -> Log.i(TAG, line)
        }
    }
}
