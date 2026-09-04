package com.soundmesh.probe.sync

import android.content.Context
import android.media.projection.MediaProjection
import com.soundmesh.probe.AndroidPlaybackReader

/**
 * Turns the validated playback-capture reader into the chunk-sized reads the sync path streams.
 *
 * The reader hands back whatever the recorder has ready, which is not a chunk boundary, so this
 * fills a whole chunk before returning one. That blocking is deliberate: it is the only pace the
 * capture path has, and the host loop leans on it in place of the generator's sleep.
 */
class CaptureChunkSource private constructor(private val reader: AndroidPlaybackReader) {
    private val buffer = ByteArray(SyncRenderer.FRAMES_PER_CHUNK * SyncRenderer.CHANNELS * BYTES_PER_SAMPLE)

    /** One full chunk, or null once the recorder stops - which is what a revoked projection looks like. */
    fun readChunk(): ByteArray? {
        var filled = 0
        while (filled < buffer.size) {
            val slice = ByteArray(buffer.size - filled)
            val read = reader.read(slice)
            if (read <= 0) return null
            slice.copyInto(buffer, filled, 0, read)
            filled += read
        }
        return buffer.copyOf()
    }

    fun close() {
        runCatching { reader.stop() }
        runCatching { reader.close() }
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2

        /**
         * Opens capture, or throws with a code the report can carry.
         *
         * A mono fallback is refused rather than upmixed. The renderer consumes interleaved stereo,
         * so mono bytes would be read as stereo and play at double speed - audible, but as a wrong
         * song rather than as a failure, and every timing number in the run would still look sane.
         */
        fun open(context: Context, projection: MediaProjection, expectedPackage: String?, onProjectionStopped: () -> Unit): CaptureChunkSource {
            val reader = AndroidPlaybackReader(context, projection, expectedPackage, onProjectionStopped)
            reader.start()
            if (reader.channelCount != SyncRenderer.CHANNELS || reader.sampleRate != SyncRenderer.SAMPLE_RATE) {
                runCatching { reader.stop() }
                runCatching { reader.close() }
                throw SourceUnusable("CAPTURE_FORMAT_UNUSABLE")
            }
            return CaptureChunkSource(reader)
        }
    }
}
