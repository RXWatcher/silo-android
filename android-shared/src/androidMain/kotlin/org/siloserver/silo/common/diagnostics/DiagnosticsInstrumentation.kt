package org.siloserver.silo.common.diagnostics

import java.util.concurrent.atomic.AtomicBoolean
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.network.NetworkDiagnosticsObserver

object DiagnosticsCaptureDetailState {
    private val enabled = AtomicBoolean(false)

    fun isEnabled(): Boolean = enabled.get()

    internal fun setEnabled(value: Boolean) {
        enabled.set(value)
    }
}

class DiagnosticsStatsCadence(
    private val intervalMs: Long = 5_000,
) {
    private var lastEmissionMs: Long? = null

    init {
        require(intervalMs > 0)
    }

    @Synchronized
    fun shouldEmit(detailedCapture: Boolean, nowMs: Long): Boolean {
        if (!detailedCapture) return false
        val previous = lastEmissionMs
        if (previous != null && nowMs - previous < intervalMs) return false
        lastEmissionMs = nowMs
        return true
    }
}

object DiagnosticsNetworkLogger : NetworkDiagnosticsObserver {
    override fun completed(method: String, rawPath: String, status: Int, durationMs: Long) {
        SiloLog.i(
            category = DiagnosticsLogCategory.NETWORK,
            tag = "HttpClient",
            message = "request completed",
            attributes = mapOf(
                "method" to SiloLogAttribute.Text(method.uppercase().take(12)),
                "path" to SiloLogAttribute.Text(safeDiagnosticsNetworkPath(rawPath)),
                "status" to SiloLogAttribute.Integer(status.toLong().coerceIn(0, 999)),
                "duration_ms" to SiloLogAttribute.Integer(durationMs.coerceAtLeast(0)),
            ),
        )
    }

    override fun authRefresh(state: String) {
        val message = when (state) {
            "required" -> "token refresh required"
            "started" -> "token refresh started"
            "succeeded" -> "token refresh succeeded"
            "failed" -> "token refresh failed"
            else -> return
        }
        SiloLog.i(DiagnosticsLogCategory.NETWORK, "Auth", message)
    }
}

internal fun safeDiagnosticsNetworkPath(rawPath: String): String {
    val path = rawPath.substringBefore('?').substringBefore('#')
    val segments = path.split('/').filter(String::isNotBlank)
    if (segments.size < 3 || segments[0] != "api" || !segments[1].matches(API_VERSION)) {
        return "/other"
    }
    val resource = segments[2].lowercase().takeIf(KNOWN_API_RESOURCES::contains) ?: "other"
    return if (segments.size > 3) "/api/${segments[1]}/$resource/{id}" else "/api/${segments[1]}/$resource"
}

object DiagnosticsPlaybackLogger {
    fun sessionEvent(message: String) = playbackInfo(message)

    fun videoDecoderInitialized(decoderName: String) = playbackInfo(
        "video decoder initialized",
        mapOf("decoder" to SiloLogAttribute.Text(decoderName)),
    )

    fun audioDecoderInitialized(decoderName: String) = playbackInfo(
        "audio decoder initialized",
        mapOf("decoder" to SiloLogAttribute.Text(decoderName)),
    )

    fun videoFormatChanged(format: String?, width: Int, height: Int, hdrMode: String?) = playbackInfo(
        "video format changed",
        buildMap {
            format?.let { put("fmt", SiloLogAttribute.Text(it)) }
            width.takeIf { it > 0 }?.let { put("width", SiloLogAttribute.Integer(it.toLong())) }
            height.takeIf { it > 0 }?.let { put("height", SiloLogAttribute.Integer(it.toLong())) }
            hdrMode?.let { put("hdr_mode", SiloLogAttribute.Text(it)) }
        },
    )

    fun audioFormatChanged(format: String?) = playbackInfo(
        "audio format changed",
        format?.let { mapOf("fmt" to SiloLogAttribute.Text(it)) }.orEmpty(),
    )

    fun tracksChanged() = playbackInfo("tracks changed")

    fun droppedFrames(count: Int) = SiloLog.w(
        DiagnosticsLogCategory.PLAYBACK,
        "Media3Analytics",
        "video frames dropped",
        mapOf("dropped_frames" to SiloLogAttribute.Integer(count.coerceAtLeast(0).toLong())),
    )

    fun audioUnderrun() = SiloLog.w(DiagnosticsLogCategory.PLAYBACK, "Media3Analytics", "audio underrun")

    fun playerError() = SiloLog.e(DiagnosticsLogCategory.PLAYBACK, "Media3Analytics", "player error")

    fun loadError() = SiloLog.w(DiagnosticsLogCategory.PLAYBACK, "Media3Analytics", "media load error")

    fun statsSnapshot(snapshot: PlayerStatsSnapshot) = playbackInfo(
        "player stats snapshot",
        buildMap {
            snapshot.videoDecoderName?.let { put("decoder", SiloLogAttribute.Text(it)) }
            snapshot.videoMimeType?.let { put("fmt", SiloLogAttribute.Text(it)) }
            snapshot.videoWidth?.let { put("width", SiloLogAttribute.Integer(it.toLong())) }
            snapshot.videoHeight?.let { put("height", SiloLogAttribute.Integer(it.toLong())) }
            snapshot.hdrMode?.let { put("hdr_mode", SiloLogAttribute.Text(it)) }
            snapshot.bitrateBps?.let { put("bitrate_kbps", SiloLogAttribute.Integer((it / 1_000).coerceAtLeast(0))) }
            put("dropped_frames", SiloLogAttribute.Integer(snapshot.droppedFrames.coerceAtLeast(0).toLong()))
            put("audio_underruns", SiloLogAttribute.Integer(snapshot.audioUnderruns.coerceAtLeast(0).toLong()))
        },
    )

    private fun playbackInfo(
        message: String,
        attributes: Map<String, SiloLogAttribute> = emptyMap(),
    ) = SiloLog.i(DiagnosticsLogCategory.PLAYBACK, "Media3Analytics", message, attributes)
}

object DiagnosticsLifecycleLogger {
    fun state(state: String) = SiloLog.i(
        DiagnosticsLogCategory.LIFECYCLE,
        "AppLifecycle",
        "app lifecycle changed",
        mapOf("state" to SiloLogAttribute.Text(state)),
    )

    fun route(rawRoute: String?) {
        val identifier = rawRoute
            ?.substringBefore('/')
            ?.substringBefore('?')
            ?.lowercase()
            ?.takeIf(KNOWN_ROUTE_ROOTS::contains)
            ?: "unknown"
        state("route:$identifier")
    }
}

object DiagnosticsCastLogger {
    fun event(message: String) = SiloLog.i(DiagnosticsLogCategory.CAST, "Cast", message)

    fun warning(message: String) = SiloLog.w(DiagnosticsLogCategory.CAST, "Cast", message)
}

object DiagnosticsDownloadLogger {
    fun event(message: String) = SiloLog.i(DiagnosticsLogCategory.DOWNLOAD, "DownloadWorker", message)

    fun warning(message: String) = SiloLog.w(DiagnosticsLogCategory.DOWNLOAD, "DownloadWorker", message)

    fun error(message: String) = SiloLog.e(DiagnosticsLogCategory.DOWNLOAD, "DownloadWorker", message)
}

object DiagnosticsFocusLogger {
    fun transition(target: String, action: String) = SiloLog.d(
        DiagnosticsLogCategory.FOCUS,
        "TvShellFocus",
        "focus transition",
        mapOf(
            "target" to SiloLogAttribute.Text(target),
            "action" to SiloLogAttribute.Text(action),
        ),
    )
}

private val API_VERSION = Regex("v[0-9]+")
private val KNOWN_ROUTE_ROOTS = setOf(
    "admin",
    "audiobook",
    "browse",
    "calendar",
    "collection",
    "collections",
    "diagnostics",
    "downloads",
    "favorites",
    "history",
    "home",
    "inbox",
    "item",
    "libraries",
    "library",
    "login",
    "main",
    "pair_device",
    "person",
    "personal_lists",
    "player",
    "profiles",
    "reader",
    "recommendations",
    "requests",
    "search",
    "server_first_setup",
    "server_list",
    "server_setup",
    "settings",
    "setup",
    "signup",
    "silocast",
    "watch_together",
    "watchlist",
)
private val KNOWN_API_RESOURCES = setOf(
    "admin",
    "auth",
    "calendar",
    "catalog",
    "collections",
    "device-login",
    "diagnostics",
    "downloads",
    "health",
    "history",
    "items",
    "libraries",
    "metadata-ai",
    "notifications",
    "pairing",
    "personal",
    "playback",
    "profiles",
    "push",
    "recommendations",
    "requests",
    "sections",
    "settings",
    "subtitles",
    "watch-together",
    "watchlist",
)
