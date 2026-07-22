package org.siloserver.silo.common.diagnostics

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CrashRuntimeSnapshot(
    val binding: PendingReportBinding? = null,
    val captureSessionId: String? = null,
    val runToken: String? = null,
    val foreground: Boolean? = null,
    val playbackSessionIds: List<String> = emptyList(),
    val deviceSnapshotJson: String? = null,
    val logLines: List<String> = emptyList(),
    val logDroppedCount: Long = 0,
    val logTornCount: Long = 0,
    val logGeneration: Long = 0,
    val redactionTokens: List<String> = emptyList(),
) {
    companion object {
        fun empty() = CrashRuntimeSnapshot()
    }
}

fun interface CrashMarkerSink {
    fun write(thread: Thread, throwable: Throwable, runtime: CrashRuntimeSnapshot)
}

class CrashExceptionHandler(
    private val markerSink: CrashMarkerSink,
    private val runtimeSnapshot: () -> CrashRuntimeSnapshot,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            markerSink.write(thread, throwable, runtimeSnapshot())
        } catch (_: Throwable) {
            // The platform/default handler remains authoritative even if evidence capture fails.
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }
}

class CrashMarkerRenderer {
    fun render(
        thread: Thread,
        throwable: Throwable,
        runtime: CrashRuntimeSnapshot,
        occurredAtEpochMs: Long,
    ): ByteArray {
        val redactor = DiagnosticsRedactor(sensitiveValues = runtime.redactionTokens.toSet())
        val marker = CrashMarker(
            occurredAtEpochMs = occurredAtEpochMs.coerceAtLeast(0),
            threadName = redactor.sanitize(thread.name).truncateUtf8(MAX_FIELD_BYTES),
            threadId = thread.stableId(),
            throwableType = throwable.javaClass.name.truncateUtf8(MAX_FIELD_BYTES),
            stack = renderThrowable(throwable, redactor, MAX_STACK_BYTES),
            binding = runtime.binding?.bounded(),
            captureSessionId = runtime.captureSessionId?.let(redactor::sanitize)?.truncateUtf8(MAX_FIELD_BYTES),
            runToken = runtime.runToken?.let(redactor::sanitize)?.truncateUtf8(MAX_FIELD_BYTES),
            foreground = runtime.foreground,
            playbackSessionIds = runtime.playbackSessionIds.take(MAX_PLAYBACK_IDS).map {
                redactor.sanitize(it).truncateUtf8(MAX_FIELD_BYTES)
            },
            deviceSnapshotJson = runtime.deviceSnapshotJson?.truncateUtf8(MAX_DEVICE_BYTES),
            logLines = boundedNewestLogs(runtime.logLines, runtime.redactionTokens, MAX_LOG_BYTES),
            logDroppedCount = runtime.logDroppedCount.coerceAtLeast(0),
            logTornCount = runtime.logTornCount.coerceAtLeast(0),
            logGeneration = runtime.logGeneration.coerceAtLeast(0),
            truncated = false,
        )
        encode(marker).takeIf { it.size <= MAX_MARKER_BYTES }?.let { return it }

        val reduced = marker.copy(
            stack = marker.stack.truncateUtf8(REDUCED_STACK_BYTES),
            deviceSnapshotJson = marker.deviceSnapshotJson?.truncateUtf8(REDUCED_DEVICE_BYTES),
            logLines = boundedNewestLogs(marker.logLines, runtime.redactionTokens, REDUCED_LOG_BYTES),
            truncated = true,
        )
        encode(reduced).takeIf { it.size <= MAX_MARKER_BYTES }?.let { return it }

        val minimal = reduced.copy(
            binding = null,
            captureSessionId = null,
            runToken = null,
            playbackSessionIds = emptyList(),
            deviceSnapshotJson = null,
            logLines = emptyList(),
            stack = reduced.stack.truncateUtf8(MINIMAL_STACK_BYTES),
        )
        return encode(minimal).takeIf { it.size <= MAX_MARKER_BYTES } ?: MINIMAL_MARKER
    }

    private fun renderThrowable(
        throwable: Throwable,
        redactor: DiagnosticsRedactor,
        maxBytes: Int,
    ): String {
        val output = BoundedUtf8Builder(maxBytes)
        val seen = HashSet<Throwable>()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
            val prefix = if (depth == 0) "" else "caused by "
            val message = current.message
                ?.take(MAX_RAW_MESSAGE_CHARS)
                ?.let(redactor::sanitize)
                ?.truncateUtf8(MAX_MESSAGE_BYTES)
            if (!output.append("$prefix${current.javaClass.name}${message?.let { ": $it" }.orEmpty()}\n")) break
            for (frame in current.stackTrace) {
                val rendered = "    at ${frame.className}.${frame.methodName}(${frame.fileName ?: "Unknown Source"}:${frame.lineNumber})\n"
                if (!output.append(rendered)) return output.toString()
            }
            current = current.cause
            depth += 1
        }
        return output.toString()
    }

    private fun boundedNewestLogs(
        lines: List<String>,
        redactionTokens: List<String>,
        maxBytes: Int,
    ): List<String> {
        val newestFirst = ArrayList<String>()
        val tokens = redactionTokens.filter(String::isNotEmpty)
        var usedBytes = 0
        for (index in lines.lastIndex downTo 0) {
            var line = lines[index]
            tokens.forEach { token -> line = line.replace(token, REDACTED_VALUE) }
            line = line.truncateUtf8(MAX_LOG_LINE_BYTES)
            val bytes = line.encodeToByteArray().size
            if (usedBytes + bytes > maxBytes) break
            newestFirst += line
            usedBytes += bytes
        }
        newestFirst.reverse()
        return newestFirst
    }

    private fun encode(marker: CrashMarker): ByteArray = JSON.encodeToString(marker).encodeToByteArray()

    @Serializable
    private data class CrashMarker(
        @SerialName("schema_version") val schemaVersion: Int = 1,
        @SerialName("occurred_at_epoch_ms") val occurredAtEpochMs: Long,
        @SerialName("thread_name") val threadName: String,
        @SerialName("thread_id") val threadId: Long,
        @SerialName("throwable_type") val throwableType: String,
        val stack: String,
        val binding: PendingReportBinding? = null,
        @SerialName("capture_session_id") val captureSessionId: String? = null,
        @SerialName("run_token") val runToken: String? = null,
        val foreground: Boolean? = null,
        @SerialName("playback_session_ids") val playbackSessionIds: List<String>,
        @SerialName("device_snapshot_json") val deviceSnapshotJson: String? = null,
        @SerialName("log_lines") val logLines: List<String>,
        @SerialName("log_dropped_count") val logDroppedCount: Long,
        @SerialName("log_torn_count") val logTornCount: Long,
        @SerialName("log_generation") val logGeneration: Long,
        val truncated: Boolean,
    )

    companion object {
        const val MAX_MARKER_BYTES = 512 * 1_024
        private const val REDACTED_VALUE = "[REDACTED]"
        private const val MAX_STACK_BYTES = 96 * 1_024
        private const val MAX_LOG_BYTES = 256 * 1_024
        private const val MAX_DEVICE_BYTES = 64 * 1_024
        private const val REDUCED_STACK_BYTES = 64 * 1_024
        private const val REDUCED_LOG_BYTES = 128 * 1_024
        private const val REDUCED_DEVICE_BYTES = 32 * 1_024
        private const val MINIMAL_STACK_BYTES = 4 * 1_024
        private const val MAX_LOG_LINE_BYTES = 8 * 1_024
        private const val MAX_MESSAGE_BYTES = 8 * 1_024
        private const val MAX_RAW_MESSAGE_CHARS = 16 * 1_024
        private const val MAX_FIELD_BYTES = 512
        private const val MAX_PLAYBACK_IDS = 20
        private const val MAX_CAUSE_DEPTH = 8
        private val JSON = Json { encodeDefaults = true; explicitNulls = false }
        private val MINIMAL_MARKER = "{\"schema_version\":1,\"truncated\":true}".encodeToByteArray()
    }
}

class FileCrashMarkerWriter(
    noBackupFilesDir: File,
    private val renderer: CrashMarkerRenderer = CrashMarkerRenderer(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val elapsedBudgetNanos: Long = DEFAULT_ELAPSED_BUDGET_NANOS,
) : CrashMarkerSink {
    private val directory = noBackupFilesDir.resolve("client-diagnostics/crash-markers")

    init {
        require(elapsedBudgetNanos > 0)
    }

    override fun write(thread: Thread, throwable: Throwable, runtime: CrashRuntimeSnapshot) {
        val startedAt = nanoTime()
        val occurredAt = nowMs()
        val bytes = renderer.render(thread, throwable, runtime, occurredAt)
        if (elapsed(startedAt)) return
        if (!(directory.mkdirs() || directory.isDirectory)) return
        if (elapsed(startedAt)) return

        val stem = "jvm-${occurredAt.coerceAtLeast(0)}-${thread.stableId().coerceAtLeast(0)}"
        val temporary = directory.resolve(".$stem.tmp")
        val published = directory.resolve("$stem.json")
        try {
            FileOutputStream(temporary, false).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            if (elapsed(startedAt)) return
            atomicRename(temporary, published)
            syncDirectory(directory)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun elapsed(startedAt: Long): Boolean = nanoTime() - startedAt > elapsedBudgetNanos

    private fun atomicRename(source: File, target: File) {
        val renamedByOs = runCatching {
            Os.rename(source.absolutePath, target.absolutePath)
            !source.exists() && target.exists()
        }.getOrDefault(false)
        if (!renamedByOs) {
            if (target.exists() && !target.delete()) return
            source.renameTo(target)
        }
    }

    private fun syncDirectory(directory: File) {
        var descriptor: FileDescriptor? = null
        runCatching {
            descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            Os.fsync(checkNotNull(descriptor))
        }
        runCatching { descriptor?.let(Os::close) }
    }

    private companion object {
        const val DEFAULT_ELAPSED_BUDGET_NANOS = 150_000_000L
    }
}

object CrashCapture {
    private val installed = AtomicBoolean(false)
    private val runtime = AtomicReference(CrashRuntimeSnapshot.empty())

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = CrashExceptionHandler(
            markerSink = FileCrashMarkerWriter(context.noBackupFilesDir),
            runtimeSnapshot = runtime::get,
            previous = previous,
        )
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    fun updateSnapshot(snapshot: CrashRuntimeSnapshot) {
        runtime.set(
            snapshot.copy(
                playbackSessionIds = snapshot.playbackSessionIds.toList(),
                logLines = snapshot.logLines.toList(),
                redactionTokens = snapshot.redactionTokens.filter(String::isNotEmpty).toList(),
            ),
        )
    }
}

private fun PendingReportBinding.bounded(): PendingReportBinding = copy(
    serverInstanceId = serverInstanceId.truncateUtf8(128),
    accountUserId = accountUserId.truncateUtf8(128),
    profileId = profileId?.truncateUtf8(128),
    ownershipGeneration = ownershipGeneration.coerceAtLeast(0),
)

private class BoundedUtf8Builder(private val maxBytes: Int) {
    private val builder = StringBuilder()
    private var usedBytes = 0

    fun append(value: String): Boolean {
        val remaining = maxBytes - usedBytes
        if (remaining <= 0) return false
        val bounded = value.truncateUtf8(remaining)
        builder.append(bounded)
        usedBytes += bounded.encodeToByteArray().size
        return bounded.length == value.length
    }

    override fun toString(): String = builder.toString()
}

@Suppress("DEPRECATION")
private fun Thread.stableId(): Long = id

private fun String.truncateUtf8(maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (encodeToByteArray().size <= maxBytes) return this
    val result = StringBuilder(length.coerceAtMost(maxBytes))
    var index = 0
    var usedBytes = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val value = String(Character.toChars(codePoint))
        val bytes = value.encodeToByteArray().size
        if (usedBytes + bytes > maxBytes) break
        result.append(value)
        usedBytes += bytes
        index += Character.charCount(codePoint)
    }
    return result.toString()
}
