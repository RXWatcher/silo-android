package org.siloserver.silo.common.player.subtitle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueEncoder
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException

/**
 * Extractor for a raw PGS elementary stream (`.sup`), which Media3 has no
 * extractor for.
 *
 * Media3 parses PGS but only as one *display set* at a time, timestamped by
 * whatever container carried it — inside Matroska the block supplies the time
 * and the payload is bare `[type][len][payload]` segments. A `.sup` file is the
 * same segments with a 10-byte `PG`+PTS+DTS prefix on each and no container at
 * all, so handing the whole file to the parser (which is what SubtitleExtractor
 * does) yields nothing: it sees one enormous sample with no timing. That is why
 * a mounted `.sup` sidecar selected cleanly and drew nothing.
 *
 * So: frame the stream into display sets, strip each segment's prefix back to
 * the container-shaped form the parser expects, and emit one parsed cue sample
 * per set at the PTS the prefix carried.
 *
 * The parser is injected rather than constructed here so the caller's offset
 * wrapper still applies — subtitle sync and the re-anchor delta have to reach
 * these cues like any other.
 */
@UnstableApi
class PgsSupExtractor(
    private val parserFactory: SubtitleParser.Factory,
) : Extractor {

    private val cueEncoder = CueEncoder()
    private val headerScratch = ByteArray(SEGMENT_HEADER_SIZE)

    private var trackOutput: TrackOutput? = null
    private var parser: SubtitleParser? = null

    /** Segments of the display set being accumulated, already prefix-stripped. */
    private var displaySet = ByteArrayBuilder()
    private var displaySetTimeUs = C.TIME_UNSET

    override fun sniff(input: ExtractorInput): Boolean {
        val probe = ByteArray(2)
        return try {
            input.peekFully(probe, 0, probe.size)
            probe[0] == MAGIC_P && probe[1] == MAGIC_G
        } catch (_: EOFException) {
            false
        }
    }

    override fun init(output: ExtractorOutput) {
        val track = output.track(0, C.TRACK_TYPE_TEXT)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_PGS)
            .build()
        parser = parserFactory.create(
            Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).build(),
        )
        track.format(
            format.buildUpon()
                .setCueReplacementBehavior(
                    parserFactory.getCueReplacementBehavior(
                        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).build(),
                    ),
                )
                .build(),
        )
        trackOutput = track
        output.endTracks()
        // The cues are held in the sample queue once read, so backward seeks are
        // served from memory; a seek before the read completes just restarts it.
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val output = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        try {
            input.readFully(headerScratch, 0, SEGMENT_HEADER_SIZE)
        } catch (_: EOFException) {
            discardPendingDisplaySet()
            return Extractor.RESULT_END_OF_INPUT
        }
        val header = ParsableByteArray(headerScratch)
        if (header.readUnsignedByte() != MAGIC_P_INT || header.readUnsignedByte() != MAGIC_G_INT) {
            // Resynchronising mid-stream would mean guessing where the next
            // segment starts; a truncated or mislabelled artifact is better
            // reported as end-of-input than turned into fabricated cues.
            discardPendingDisplaySet()
            return Extractor.RESULT_END_OF_INPUT
        }
        val pts90kHz = header.readUnsignedInt()
        header.skipBytes(4) // DTS: unused, presentation time is what cues need.
        val segmentType = header.readUnsignedByte()
        val segmentLength = header.readUnsignedShort()

        val payload = ByteArray(segmentLength)
        if (segmentLength > 0) {
            try {
                input.readFully(payload, 0, segmentLength)
            } catch (_: EOFException) {
                discardPendingDisplaySet()
                return Extractor.RESULT_END_OF_INPUT
            }
        }

        if (segmentType == SEGMENT_TYPE_END) {
            flushDisplaySet(output)
            return Extractor.RESULT_CONTINUE
        }

        // First segment of a set carries the time the whole set is shown at.
        if (displaySet.isEmpty()) {
            displaySetTimeUs = pts90kHz * C.MICROS_PER_SECOND / PTS_CLOCK_HZ
        }
        // Back to the container-shaped form: [type][length][payload].
        displaySet.append(segmentType.toByte())
        displaySet.append((segmentLength shr 8 and 0xFF).toByte())
        displaySet.append((segmentLength and 0xFF).toByte())
        displaySet.append(payload)
        return Extractor.RESULT_CONTINUE
    }

    /**
     * A set is only complete once its END segment arrives, so a stream that
     * stops mid-set has nothing renderable — parsing what arrived would risk a
     * half-built caption from a composition with no bitmap yet.
     */
    private fun discardPendingDisplaySet() {
        displaySet = ByteArrayBuilder()
        displaySetTimeUs = C.TIME_UNSET
    }

    private fun flushDisplaySet(output: TrackOutput) {
        if (displaySet.isEmpty()) return
        val bytes = displaySet.toByteArray()
        val timeUs = displaySetTimeUs
        displaySet = ByteArrayBuilder()
        displaySetTimeUs = C.TIME_UNSET
        val activeParser = parser ?: return
        if (timeUs == C.TIME_UNSET) return

        activeParser.parse(
            bytes,
            0,
            bytes.size,
            SubtitleParser.OutputOptions.allCues(),
        ) { cues ->
            // Duration stays unset: PGS ends a caption with the next display
            // set, and the parser's REPLACE behaviour already means a new
            // sample supersedes the last one.
            val encoded = cueEncoder.encode(cues.cues, C.TIME_UNSET)
            val data = ParsableByteArray(encoded)
            output.sampleData(data, encoded.size)
            output.sampleMetadata(
                timeUs,
                C.BUFFER_FLAG_KEY_FRAME,
                encoded.size,
                0,
                null,
            )
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        displaySet = ByteArrayBuilder()
        displaySetTimeUs = C.TIME_UNSET
        parser?.reset()
    }

    override fun release() {
        parser = null
    }

    /** Grow-on-append byte buffer; a display set is a handful of small segments. */
    private class ByteArrayBuilder {
        private var buffer = ByteArray(INITIAL_CAPACITY)
        private var size = 0

        fun isEmpty(): Boolean = size == 0

        fun append(value: Byte) {
            ensure(1)
            buffer[size++] = value
        }

        fun append(values: ByteArray) {
            if (values.isEmpty()) return
            ensure(values.size)
            values.copyInto(buffer, size)
            size += values.size
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensure(extra: Int) {
            if (size + extra <= buffer.size) return
            var capacity = buffer.size
            while (capacity < size + extra) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }

        private companion object {
            const val INITIAL_CAPACITY = 4096
        }
    }

    companion object {
        /** `PG` magic, 4-byte PTS, 4-byte DTS, type, 2-byte length. */
        const val SEGMENT_HEADER_SIZE = 13
        const val SEGMENT_TYPE_END = 0x80
        private const val PTS_CLOCK_HZ = 90_000L
        private const val MAGIC_P_INT = 0x50
        private const val MAGIC_G_INT = 0x47
        private const val MAGIC_P = MAGIC_P_INT.toByte()
        private const val MAGIC_G = MAGIC_G_INT.toByte()
    }
}
