package org.siloserver.silo.common.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import io.sentry.SentryLogLevel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.siloserver.silo.common.telemetry.PlaybackTelemetry

/**
 * `AnalyticsListener` that logs the handful of signals we actually triage
 * playback issues with — decoder init names, dropped-frame counts, audio
 * underruns, load errors, and bandwidth estimates — and re-emits them to an
 * in-process [SharedFlow] so the debug overlay (or a future server-side
 * telemetry POST) can subscribe without another listener registration.
 *
 * Output is `Log.i` on [TAG] only; no network I/O. Server-side telemetry
 * ingestion is deferred to a follow-up — the flow hook here is the seam.
 */
@UnstableApi
class PlaybackAnalyticsListener : AnalyticsListener {

    companion object {
        private const val TAG = "Media3Analytics"
    }

    sealed class Event {
        data class VideoDecoderInitialized(
            val decoderName: String,
            val initializationDurationMs: Long? = null,
        ) : Event()
        data class AudioDecoderInitialized(val decoderName: String) : Event()
        data class VideoFormatChanged(val format: Format) : Event()
        data class AudioFormatChanged(val format: Format) : Event()
        data class DroppedFrames(val count: Int, val elapsedMs: Long) : Event()
        object AudioUnderrun : Event()
        data class LoadError(val throwable: Throwable) : Event()
        data class PlayerError(val error: PlaybackException) : Event()
        data class BandwidthEstimate(val bitrateBps: Long) : Event()
        data class TrackSnapshot(val description: String) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        Log.i(TAG, "Video decoder: $decoderName (init ${initializationDurationMs}ms)")
        PlaybackTelemetry.log(
            "video decoder initialized",
            mapOf("decoder" to decoderName, "init_ms" to initializationDurationMs),
        )
        _events.tryEmit(Event.VideoDecoderInitialized(decoderName, initializationDurationMs))
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        Log.i(TAG, "Audio decoder: $decoderName (init ${initializationDurationMs}ms)")
        PlaybackTelemetry.log(
            "audio decoder initialized",
            mapOf("decoder" to decoderName, "init_ms" to initializationDurationMs),
        )
        _events.tryEmit(Event.AudioDecoderInitialized(decoderName))
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        val colorInfo = format.colorInfo
        Log.i(
            TAG,
            "Video format: ${format.sampleMimeType} ${format.width}x${format.height}@${format.frameRate} " +
                "codecs=${format.codecs} colorSpace=${colorInfo?.colorSpace} " +
                "colorTransfer=${colorInfo?.colorTransfer} colorRange=${colorInfo?.colorRange}",
        )
        PlaybackTelemetry.log(
            "video format changed",
            mapOf(
                "mime" to format.sampleMimeType,
                "codecs" to format.codecs,
                "width" to format.width,
                "height" to format.height,
                "frame_rate" to format.frameRate,
                "bitrate" to format.bitrate.takeIf { it != Format.NO_VALUE },
                "color_space" to colorInfo?.colorSpace,
                "color_transfer" to colorInfo?.colorTransfer,
                "color_range" to colorInfo?.colorRange,
            ),
        )
        _events.tryEmit(Event.VideoFormatChanged(format))
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        Log.i(TAG, "Audio format: ${format.sampleMimeType} ch=${format.channelCount} sr=${format.sampleRate}")
        PlaybackTelemetry.log(
            "audio format changed",
            mapOf(
                "mime" to format.sampleMimeType,
                "codecs" to format.codecs,
                "channels" to format.channelCount,
                "sample_rate" to format.sampleRate,
                "bitrate" to format.bitrate.takeIf { it != Format.NO_VALUE },
            ),
        )
        _events.tryEmit(Event.AudioFormatChanged(format))
    }

    override fun onTracksChanged(
        eventTime: AnalyticsListener.EventTime,
        tracks: Tracks,
    ) {
        val description = tracks.describeForLog()
        Log.i(TAG, "Track snapshot: $description")
        PlaybackTelemetry.log("track snapshot", mapOf("tracks" to description))
        _events.tryEmit(Event.TrackSnapshot(description))
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedRealtimeMs: Long,
    ) {
        if (droppedFrames > 0) {
            Log.w(TAG, "Dropped $droppedFrames video frame(s) in ${elapsedRealtimeMs}ms")
            PlaybackTelemetry.log(
                "dropped video frames",
                mapOf("count" to droppedFrames, "elapsed_ms" to elapsedRealtimeMs),
                SentryLogLevel.WARN,
            )
        }
        _events.tryEmit(Event.DroppedFrames(droppedFrames, elapsedRealtimeMs))
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        Log.w(TAG, "Audio underrun (buffer=${bufferSizeMs}ms, gap=${elapsedSinceLastFeedMs}ms)")
        PlaybackTelemetry.log(
            "audio underrun",
            mapOf("buffer_ms" to bufferSizeMs, "gap_ms" to elapsedSinceLastFeedMs),
            SentryLogLevel.WARN,
        )
        _events.tryEmit(Event.AudioUnderrun)
    }

    override fun onPlayerError(
        eventTime: AnalyticsListener.EventTime,
        error: PlaybackException,
    ) {
        Log.e(TAG, "Player error ${error.errorCodeName}: ${error.message}", error)
        // Log+breadcrumb only: the player-screen ViewModels capture the
        // exception event with full playback context; this seam also fires for
        // players without a ViewModel (background audio), where this is the
        // only record.
        PlaybackTelemetry.log(
            "player error",
            mapOf("error_code" to error.errorCodeName, "message" to error.message),
            SentryLogLevel.ERROR,
        )
        _events.tryEmit(Event.PlayerError(error))
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: java.io.IOException,
        wasCanceled: Boolean,
    ) {
        Log.w(TAG, "Load error (${mediaLoadData.dataType}): ${error.message}")
        PlaybackTelemetry.log(
            "load error",
            mapOf(
                "data_type" to mediaLoadData.dataType,
                "error" to error.message,
                "error_class" to error.javaClass.simpleName,
                "uri" to loadEventInfo.uri?.toString(),
                "was_canceled" to wasCanceled,
            ),
            SentryLogLevel.WARN,
        )
        _events.tryEmit(Event.LoadError(error))
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        _events.tryEmit(Event.BandwidthEstimate(bitrateEstimate))
    }
}

private fun Tracks.describeForLog(): String {
    if (groups.isEmpty()) return "[]"
    return groups.mapIndexed { groupIndex, group ->
        val tracks = (0 until group.length).joinToString(prefix = "[", postfix = "]") { trackIndex ->
            val format = group.getTrackFormat(trackIndex)
            val selected = group.isTrackSelected(trackIndex)
            val supported = group.isTrackSupported(trackIndex)
            val sampleMimeType = format.sampleMimeType ?: "?"
            val codecs = format.codecs ?: "?"
            val language = format.language ?: "?"
            val label = format.label ?: "?"
            "$trackIndex{selected=$selected supported=$supported " +
                "sampleMimeType=$sampleMimeType codecs=$codecs language=$language label=$label}"
        }
        "$groupIndex:${group.type.trackTypeName()}$tracks"
    }.joinToString(prefix = "[", postfix = "]")
}

private fun Int.trackTypeName(): String = when (this) {
    C.TRACK_TYPE_VIDEO -> "video"
    C.TRACK_TYPE_AUDIO -> "audio"
    C.TRACK_TYPE_TEXT -> "text"
    C.TRACK_TYPE_METADATA -> "metadata"
    C.TRACK_TYPE_IMAGE -> "image"
    else -> "type-$this"
}
