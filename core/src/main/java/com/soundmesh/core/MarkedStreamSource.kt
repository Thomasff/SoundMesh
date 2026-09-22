package com.soundmesh.core

/**
 * Streamed content with the calibration sweep substituted in on a fixed stride.
 *
 * Exists to answer one question the chirp path cannot: **where does a sweep land when it travels
 * as ordinary streamed audio?** The two paths place the same sweep differently on purpose - a
 * chirp chunk is released exactly (no trim deadband, no splice fade), while a streamed chunk
 * carries `TRIM_DEADBAND_FRAMES` and the drift controller goes on correcting underneath it. The
 * pair constant the product stores is measured on the first path and spent on the second.
 *
 * So the sweep is the one thing held fixed. A marker is byte for byte what
 * [ChirpGenerator.generateStereoChunks] hands the chirp path, laid on the same chunk grid; only
 * the sequence range it travels on differs. Anything else here - a different amplitude, a
 * different window, a mix with the tone underneath - would make the reading incomparable with
 * every chirp reading in the archive, which is the only reference it has.
 *
 * @param strideChunks chunks from one marker's first chunk to the next. Must leave silence
 *   between markers: two sweeps with no gap give a correlator two peaks of equal height and no
 *   way to say which repeat it found.
 * @param firstMarkerChunk the first marker's first chunk. Held back past the run's acquisition
 *   transient, which is where every jump in the archive sat.
 */
class MarkedStreamSource(
    private val strideChunks: Int,
    private val firstMarkerChunk: Int,
    private val framesPerChunk: Int,
    private val tone: TonePcmSource = TonePcmSource()
) {
    /** Which marker a sequence carries, and which chunk of that marker it is. */
    data class Marker(val index: Int, val within: Int)

    private val sweep: List<ByteArray> = ChirpGenerator.generateStereoChunks(framesPerChunk)

    /** Chunks one marker occupies. */
    val markerChunkCount: Int get() = sweep.size

    init {
        require(framesPerChunk > 0) { "framesPerChunk must be positive" }
        require(firstMarkerChunk >= 0) { "firstMarkerChunk must not be negative" }
        require(strideChunks > sweep.size) {
            "strideChunks $strideChunks would run markers together: the sweep is ${sweep.size} chunks"
        }
    }

    /**
     * The marker this sequence carries, or null when it is ordinary streamed audio.
     *
     * Derived from the sequence alone. The caller already tracks a frame index beside it, and two
     * inputs that have to agree is how a reading gets attributed to the wrong instant.
     */
    fun markerAt(sequence: Int): Marker? {
        if (sequence < firstMarkerChunk) return null
        val since = sequence - firstMarkerChunk
        val within = since % strideChunks
        if (within >= sweep.size) return null
        return Marker(since / strideChunks, within)
    }

    /**
     * The marker index when [sequence] is a marker's first chunk, and null for every other chunk.
     *
     * What a reading gets attributed to is the instant the marker was scheduled for, and that is
     * the instant of its first chunk; the rest of the sweep is up to five chunks later. Asked of
     * every chunk by the host loop, which otherwise has to write down where markers are a second
     * time - and a second copy is a copy that can disagree.
     */
    fun markerStartIndex(sequence: Int): Int? = markerAt(sequence)?.takeIf { it.within == 0 }?.index

    /**
     * The sweep's PCM for this sequence, or null when this sequence carries no marker.
     *
     * For a side that has no source of its own. A sink plays chunks the host sends it, so the
     * only way it can put a sweep on the streamed release path - the one the pair constant is
     * spent on - is to write one over a chunk it received. It stamps the same sweep on the same
     * stride at a different phase, and the two emissions are then far enough apart in one
     * recording to be told from each other.
     */
    fun markerPcm(sequence: Int): ByteArray? = markerAt(sequence)?.let { sweep[it.within] }

    /** The PCM for this sequence: the sweep on a marker's chunks, the tone everywhere else. */
    fun chunkAt(sequence: Int): ByteArray {
        val marker = markerAt(sequence)
        if (marker != null) return sweep[marker.within]
        return tone.fill(sequence.toLong() * framesPerChunk, framesPerChunk)
    }

    /** The first frame of marker [index], counted from the stream's first frame. */
    fun markerFirstFrame(index: Int): Long =
        (firstMarkerChunk.toLong() + index.toLong() * strideChunks) * framesPerChunk

    companion object {
        /**
         * The phase the facing handset stamps on, given this one's.
         *
         * Half a stride, so the two sweeps sit as far apart as the grid allows and the analysis
         * can say which arrival is whose. Both sides derive it from this one rule rather than one
         * telling the other: a phase that travelled would be a second copy of the decision, and a
         * side that missed the message would stamp over its peer's sweep - two arrivals at one
         * instant, the one shape a correlator cannot read.
         */
        fun facingPhase(firstMarkerChunk: Int, strideChunks: Int): Int =
            firstMarkerChunk + strideChunks / 2
    }
}
