package org.siloserver.silo.common.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Measures hard-coded letterbox bars by sampling frames of the mounted stream.
 *
 * Decoded frames cannot be read back from the SurfaceView the player draws
 * into, so the stream is opened a second time purely to sample a handful of
 * frames. That work is off the playback path entirely: it never touches the
 * player, and any failure simply means "no inset".
 *
 * Several frames are sampled and intersected because one frame cannot
 * distinguish a bar from a dark scene — see [LetterboxInsets.intersect].
 */
class LetterboxProbe(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retrieverFactory: () -> MediaMetadataRetriever = ::MediaMetadataRetriever,
) {
    private val cache = LinkedHashMap<String, LetterboxInsets>()

    suspend fun measure(
        streamUrl: String,
        headers: Map<String, String>,
        sampleCount: Int = DEFAULT_SAMPLE_COUNT,
    ): LetterboxInsets {
        if (streamUrl.isBlank()) return LetterboxInsets.NONE
        synchronized(cache) { cache[streamUrl] }?.let { return it }

        val measured = withContext(dispatcher) { sample(streamUrl, headers, sampleCount) }
        synchronized(cache) {
            if (cache.size >= MAX_CACHE_ENTRIES) {
                cache.keys.firstOrNull()?.let(cache::remove)
            }
            cache[streamUrl] = measured
        }
        return measured
    }

    private suspend fun sample(
        streamUrl: String,
        headers: Map<String, String>,
        sampleCount: Int,
    ): LetterboxInsets {
        val retriever = retrieverFactory()
        return try {
            retriever.setDataSource(streamUrl, headers)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: return LetterboxInsets.NONE
            if (durationMs <= 0L) return LetterboxInsets.NONE

            var combined: LetterboxInsets? = null
            for (index in 1..sampleCount.coerceAtLeast(1)) {
                currentCoroutineContext().ensureActive()
                // Spread samples across the body of the media, avoiding the
                // opening and closing frames, which are routinely black.
                val positionUs = durationMs * 1000L * index / (sampleCount + 1)
                val frame = runCatching {
                    retriever.getFrameAtTime(
                        positionUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                }.getOrNull() ?: continue

                val insets = frame.measureLetterbox()
                frame.recycle()
                combined = combined?.intersect(insets) ?: insets
                // Once a sample shows a full-height picture there is nothing to
                // inset, and no later sample can reintroduce a bar.
                if (combined?.isDetected == false) return LetterboxInsets.NONE
            }
            combined ?: LetterboxInsets.NONE
        } catch (_: Exception) {
            // Unreadable stream, unsupported container, expired token: the
            // subtitle layer simply stays aligned to the full frame.
            LetterboxInsets.NONE
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun Bitmap.measureLetterbox(): LetterboxInsets {
        val scaled = if (height > MAX_SAMPLE_HEIGHT) {
            val ratio = MAX_SAMPLE_HEIGHT.toFloat() / height.toFloat()
            Bitmap.createScaledBitmap(
                this,
                (width * ratio).toInt().coerceAtLeast(1),
                MAX_SAMPLE_HEIGHT,
                true,
            )
        } else {
            this
        }
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled !== this) scaled.recycle()
        return detectLetterboxInsets(
            pixels = pixels,
            width = scaled.width,
            height = scaled.height,
        )
    }

    private companion object {
        const val DEFAULT_SAMPLE_COUNT = 4
        const val MAX_SAMPLE_HEIGHT = 180
        const val MAX_CACHE_ENTRIES = 8
    }
}
