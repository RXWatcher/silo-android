package org.siloserver.silo.common.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.siloserver.silo.common.diagnostics.DiagnosticsCaptureDetailState
import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsStatsCadence

/**
 * `AnalyticsListener` that logs the handful of signals we actually triage
 * playback issues with — decoder init names, dropped-frame counts, audio
 * underruns, load errors, and bandwidth estimates — and re-emits them to an
 * in-process [SharedFlow] so the debug overlay (or a future server-side
 * telemetry POST) can subscribe without another listener registration.
 *
 * Output goes through the local, redacting [DiagnosticsPlaybackLogger]; there
 * is no network I/O. The in-process flow remains available to debug UI.
 */
@UnstableApi
class PlaybackAnalyticsListener : AnalyticsListener {

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
    private var diagnosticsStats = PlayerStatsSnapshot()
    private val diagnosticsCadence = DiagnosticsStatsCadence()

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        DiagnosticsPlaybackLogger.videoDecoderInitialized(decoderName)
        emit(Event.VideoDecoderInitialized(decoderName, initializationDurationMs))
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        DiagnosticsPlaybackLogger.audioDecoderInitialized(decoderName)
        emit(Event.AudioDecoderInitialized(decoderName))
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        DiagnosticsPlaybackLogger.videoFormatChanged(
            format = format.sampleMimeType,
            width = format.width,
            height = format.height,
            hdrMode = null,
        )
        emit(Event.VideoFormatChanged(format))
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        DiagnosticsPlaybackLogger.audioFormatChanged(format.sampleMimeType)
        emit(Event.AudioFormatChanged(format))
    }

    override fun onTracksChanged(
        eventTime: AnalyticsListener.EventTime,
        tracks: Tracks,
    ) {
        DiagnosticsPlaybackLogger.tracksChanged()
        emit(Event.TrackSnapshot(tracks.describeForLog()))
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedRealtimeMs: Long,
    ) {
        if (droppedFrames > 0) {
            DiagnosticsPlaybackLogger.droppedFrames(droppedFrames)
        }
        emit(Event.DroppedFrames(droppedFrames, elapsedRealtimeMs))
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        DiagnosticsPlaybackLogger.audioUnderrun()
        emit(Event.AudioUnderrun)
    }

    override fun onPlayerError(
        eventTime: AnalyticsListener.EventTime,
        error: PlaybackException,
    ) {
        DiagnosticsPlaybackLogger.playerError()
        emit(Event.PlayerError(error))
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: java.io.IOException,
        wasCanceled: Boolean,
    ) {
        DiagnosticsPlaybackLogger.loadError()
        emit(Event.LoadError(error))
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        emit(Event.BandwidthEstimate(bitrateEstimate))
    }

    @Synchronized
    private fun emit(event: Event) {
        _events.tryEmit(event)
        diagnosticsStats = reducePlayerStats(diagnosticsStats, event)
        if (diagnosticsCadence.shouldEmit(DiagnosticsCaptureDetailState.isEnabled(), android.os.SystemClock.elapsedRealtime())) {
            DiagnosticsPlaybackLogger.statsSnapshot(diagnosticsStats)
        }
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
