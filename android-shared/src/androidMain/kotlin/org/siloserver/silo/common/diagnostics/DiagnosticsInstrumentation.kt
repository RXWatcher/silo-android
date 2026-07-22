package org.siloserver.silo.common.diagnostics

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.common.player.video.PlaybackDiagnosticsCode
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
    val resource = segments[2].lowercase()
    if (resource !in KNOWN_API_RESOURCES) return "/api/${segments[1]}/other"
    val safeTail = segments.drop(3).map { segment ->
        segment.lowercase().takeIf(KNOWN_API_LITERAL_SEGMENTS::contains) ?: "{id}"
    }
    return (listOf("", "api", segments[1], resource) + safeTail).joinToString("/")
}

object DiagnosticsPlaybackLogger {
    fun sessionEvent(message: String) = playbackInfo(message)

    fun startFailure(code: PlaybackDiagnosticsCode?) = playbackInfo(
        "video start failed",
        code?.let { mapOf("failure_code" to SiloLogAttribute.Text(it.wireValue)) }.orEmpty(),
    )

    fun startReady(durationMs: Long) {
        if (DiagnosticsCaptureDetailState.isEnabled()) {
            playbackInfo(
                "video start ready",
                mapOf("startup_ready_ms" to SiloLogAttribute.Integer(durationMs.coerceAtLeast(0))),
            )
        }
    }

    fun firstFrame(durationMs: Long) {
        if (DiagnosticsCaptureDetailState.isEnabled()) {
            playbackInfo(
                "first video frame rendered",
                mapOf("first_frame_ms" to SiloLogAttribute.Integer(durationMs.coerceAtLeast(0))),
            )
        }
    }

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
            snapshot.startupReadyMs?.let { put("startup_ready_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            snapshot.firstFrameMs?.let { put("first_frame_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            snapshot.bufferedDurationMs?.let { put("buffered_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            put("rebuffer_count", SiloLogAttribute.Integer(snapshot.rebufferCount.coerceAtLeast(0).toLong()))
            put("rebuffer_total_ms", SiloLogAttribute.Integer(snapshot.rebufferTotalMs.coerceAtLeast(0)))
            put("rebuffer_max_ms", SiloLogAttribute.Integer(snapshot.rebufferMaxMs.coerceAtLeast(0)))
        },
    )

    fun finalStats(snapshot: PlayerStatsSnapshot) = playbackInfo(
        "player final stats",
        buildMap {
            snapshot.startupReadyMs?.let { put("startup_ready_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            snapshot.firstFrameMs?.let { put("first_frame_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            snapshot.bufferedDurationMs?.let { put("buffered_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
            snapshot.bitrateBps?.let { put("bitrate_kbps", SiloLogAttribute.Integer((it / 1_000).coerceAtLeast(0))) }
            put("rebuffer_count", SiloLogAttribute.Integer(snapshot.rebufferCount.coerceAtLeast(0).toLong()))
            put("rebuffer_total_ms", SiloLogAttribute.Integer(snapshot.rebufferTotalMs.coerceAtLeast(0)))
            put("rebuffer_max_ms", SiloLogAttribute.Integer(snapshot.rebufferMaxMs.coerceAtLeast(0)))
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
        DiagnosticsPerformanceRoute.update(identifier)
        state("route:$identifier")
    }
}

private object DiagnosticsPerformanceRoute {
    private val route = AtomicReference("unknown")

    fun update(value: String) = route.set(value.take(64))
    fun current(): String = route.get()
}

object DiagnosticsPerformanceLogger {
    fun snapshot(
        frames: PerformanceWindowSnapshot,
        resources: DiagnosticsResourceSnapshot,
        startupFirstFrameMs: Long?,
    ) = SiloLog.i(
        DiagnosticsLogCategory.LIFECYCLE,
        "AppPerformance",
        "app performance snapshot",
        buildMap {
            put("route", SiloLogAttribute.Text(DiagnosticsPerformanceRoute.current()))
            put("frame_count", SiloLogAttribute.Integer(frames.frameCount.coerceAtLeast(0)))
            put("slow_frame_count", SiloLogAttribute.Integer(frames.slowFrameCount.coerceAtLeast(0)))
            put("p95_frame_ms", SiloLogAttribute.Integer(frames.p95FrameMs.coerceAtLeast(0)))
            put("worst_frame_ms", SiloLogAttribute.Integer(frames.worstFrameMs.coerceAtLeast(0)))
            put("main_stall_count", SiloLogAttribute.Integer(frames.mainThreadStallCount.coerceAtLeast(0)))
            put("main_stall_max_ms", SiloLogAttribute.Integer(frames.longestMainThreadStallMs.coerceAtLeast(0)))
            put("java_heap_mb", SiloLogAttribute.Integer(resources.javaHeapMb.coerceAtLeast(0)))
            put("process_pss_mb", SiloLogAttribute.Integer(resources.processPssMb.coerceAtLeast(0)))
            put("low_memory", SiloLogAttribute.Flag(resources.lowMemory))
            resources.thermalStatus?.let { put("thermal_status", SiloLogAttribute.Integer(it.toLong().coerceAtLeast(0))) }
            startupFirstFrameMs?.let { put("startup_first_frame_ms", SiloLogAttribute.Integer(it.coerceAtLeast(0))) }
        },
    )
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
    "ebooks",
    "events",
    "favorites",
    "health",
    "history",
    "home",
    "items",
    "libraries",
    "library",
    "library-playback-prefs",
    "metadata-ai",
    "metadata",
    "notifications",
    "pairing",
    "personal",
    "people",
    "playback",
    "profiles",
    "progress",
    "push",
    "ratings",
    "recommendations",
    "requests",
    "sections",
    "settings",
    "stream",
    "subtitles",
    "sync",
    "user",
    "users",
    "watch",
    "watched",
    "watch-together",
    "watchlist",
)
private val KNOWN_API_LITERAL_SEGMENTS = setOf(
    "ai",
    "app",
    "approve",
    "approve-handoff",
    "audit",
    "audiobook-groups",
    "avatar.png",
    "cancel",
    "capability",
    "collections",
    "continue_watching",
    "control",
    "deny",
    "detail",
    "device",
    "discover",
    "dismissals",
    "download",
    "effective",
    "episodes",
    "file",
    "files",
    "filters",
    "groups",
    "home",
    "items",
    "join",
    "layout",
    "login",
    "logout",
    "logs",
    "me",
    "message",
    "mine",
    "next_up",
    "order",
    "overlay-config",
    "pause",
    "policy",
    "poll",
    "preferences",
    "progress",
    "push",
    "quota",
    "read",
    "read-all",
    "refresh",
    "replan",
    "reports",
    "rooms",
    "route-events",
    "scan",
    "search",
    "seasons",
    "sections",
    "selection",
    "series",
    "sessions",
    "setup",
    "signup",
    "similar",
    "start",
    "stats",
    "status",
    "stream",
    "subtitle_appearance",
    "subtitles",
    "suggestions",
    "sync",
    "taste-profile",
    "translate",
    "translate-description",
    "transcode",
    "unread-count",
    "users",
    "verify-pin",
    "versions",
    "vote",
    "ws",
    "ws-ticket",
)
