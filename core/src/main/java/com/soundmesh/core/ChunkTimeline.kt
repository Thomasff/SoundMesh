package com.soundmesh.core

/**
 * When each chunk of a session's timeline is heard, from when its source handed it over.
 *
 * A host used to stamp a chunk with `nanoTime()` at the instant it was produced. That is right for
 * a file, which returns instantly and is paced by the host itself, and wrong for a capture, which
 * blocks until the recorder has audio and hands it over on the recorder's own cadence. The
 * arrival jitter went straight into the timeline, and both handsets then reproduced it faithfully:
 * on the Magic6, chunk instants that swung twenty milliseconds either side of where they belonged,
 * against a five millisecond trim deadband, which the renderers answered with twelve waveform
 * edits a second - the clicking a listener heard on both phones at once. The same pair playing a
 * local file, everything else unchanged, edited the waveform once every twenty seconds.
 *
 * So the timeline is a grid rather than a record of arrivals: chunk n is due one chunk-length
 * after chunk n-1, whatever the source did. A fixed grid alone would not survive a long session -
 * the recorder's clock and `nanoTime` differ by parts per million, so a grid that never moved
 * would drift away from the audio actually arriving until chunks were late or the recorder's
 * buffer overran. Hence the anchor: the grid slews toward where chunks are really arriving, by a
 * [smoothingChunks]-th of the error each time. Jitter is divided by that number; a genuine
 * difference in rate is followed whole, a few hundred microseconds behind.
 *
 * Not a clock and not thread-safe: one producer calls [accept] once per chunk, in order.
 */
class ChunkTimeline(
    private val framesPerChunk: Int,
    private val sampleRate: Int,
    /**
     * How many chunks the arrival error is averaged over before it moves the timeline.
     *
     * Two hundred chunks is four seconds, and it is where the two costs cross. Averaging longer
     * rejects more jitter and follows a rate difference later; the worst either leaves, against
     * an input harsher than the capture that produced this class - twenty milliseconds of white
     * jitter either side, where the real one kept most chunks inside the deadband already:
     *
     *     chunks     50     100     200     300     500    1000
     *     jitter   4.18    2.71    1.93    1.61    1.24    0.85 ms
     *     200 ppm  0.20    0.40    0.80    1.20    2.00    4.00 ms
     *     sum      4.38    3.11    2.73    2.81    3.24    4.85 ms
     *
     * against a trim deadband of five milliseconds, below which nothing is cut. One chunk means
     * no averaging at all, which is the defect described above.
     */
    private val smoothingChunks: Int = DEFAULT_SMOOTHING_CHUNKS
) {
    init {
        require(framesPerChunk > 0) { "framesPerChunk must be positive" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(smoothingChunks >= 1) { "smoothingChunks must be at least one" }
    }

    /** Null until the first chunk arrives: the timeline starts where the source does. */
    private var startNanos: Long? = null
    private var anchorNanos = 0L
    private var produced = 0L

    /** Takes the instant a chunk was handed over and answers when that chunk is due. */
    fun accept(arrivedAtNanos: Long): Long {
        val start = startNanos ?: arrivedAtNanos.also { startNanos = it }
        val grid = start + offsetNanos(produced)
        // Slewed, not followed: one chunk moves the timeline by a fraction of its own error, so
        // jitter is divided away and only a difference that persists gets through.
        anchorNanos += (arrivedAtNanos - grid - anchorNanos) / smoothingChunks
        produced++
        return grid + anchorNanos
    }

    /**
     * When the next chunk is due, or null before the first one has arrived.
     *
     * A source that has to be paced rather than waited on - a file, which answers instantly -
     * sleeps until this. Taking it from the same anchored grid rather than from the raw grid is
     * what keeps a host from throttling a recorder that runs fast until its buffer overruns.
     */
    fun nextDueNanos(): Long? = startNanos?.let { it + offsetNanos(produced) + anchorNanos }

    private fun offsetNanos(chunk: Long): Long =
        chunk * framesPerChunk * NANOS_PER_SECOND / sampleRate

    companion object {
        const val DEFAULT_SMOOTHING_CHUNKS = 200
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
