package org.siloserver.silo.common.telemetry

import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryAttributes
import io.sentry.SentryLogLevel
import io.sentry.logger.SentryLogParameters

/**
 * Ships playback decisions (plan adoption, track/quality selection, recovery
 * steps) to the errors server as structured Sentry Logs, so "why did it pick
 * that route" is answerable without a crash. Every call also drops a
 * breadcrumb so the same decision shows up inline on any later error event.
 * All sends are guarded: telemetry must never affect playback.
 */
object PlaybackTelemetry {
    fun log(message: String, data: Map<String, Any?>) {
        runCatching {
            val present = data.filterValues { it != null }.mapValues { it.value as Any }
            Sentry.logger().log(
                SentryLogLevel.INFO,
                SentryLogParameters.create(SentryAttributes.fromMap(present)),
                message,
            )
            Sentry.addBreadcrumb(
                Breadcrumb().apply {
                    category = "playback.decision"
                    this.message = message
                    present.forEach { (k, v) -> setData(k, v) }
                },
            )
        }
    }
}
