package org.siloserver.silo.common.telemetry

import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryAttributes
import io.sentry.SentryLevel
import io.sentry.SentryLogLevel
import io.sentry.logger.SentryLogParameters

/**
 * Ships playback decisions and player-health signals (plan adoption, track
 * selection, decoder/format events, recovery steps) to the errors server as
 * structured Sentry Logs, so "why did it pick that route" and "what was the
 * player doing" are answerable without a crash. Every call also drops a
 * breadcrumb so the same signal shows up inline on any later error event.
 * All sends are guarded: telemetry must never affect playback.
 */
object PlaybackTelemetry {
    fun log(
        message: String,
        data: Map<String, Any?>,
        level: SentryLogLevel = SentryLogLevel.INFO,
    ) {
        runCatching {
            val present = data.filterValues { it != null }.mapValues { it.value as Any }
            Sentry.logger().log(
                level,
                SentryLogParameters.create(SentryAttributes.fromMap(present)),
                message,
            )
            Sentry.addBreadcrumb(
                Breadcrumb().apply {
                    category = "playback.decision"
                    this.message = message
                    this.level = when {
                        level.ordinal >= SentryLogLevel.ERROR.ordinal -> SentryLevel.ERROR
                        level == SentryLogLevel.WARN -> SentryLevel.WARNING
                        else -> SentryLevel.INFO
                    }
                    present.forEach { (k, v) -> setData(k, v) }
                },
            )
        }
    }
}
