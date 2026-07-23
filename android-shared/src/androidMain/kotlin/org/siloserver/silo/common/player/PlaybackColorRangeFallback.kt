package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackExecutionPlan

fun PlaybackExecutionPlan?.validatedColorRangeFallback(): String? {
    val plan = this ?: return null
    if (plan.delivery == PlaybackDelivery.SERVER_TRANSCODE_HLS) return null
    return plan.source.colorRange
        ?.trim()
        ?.lowercase()
        ?.takeIf { it == "tv" || it == "pc" }
}
