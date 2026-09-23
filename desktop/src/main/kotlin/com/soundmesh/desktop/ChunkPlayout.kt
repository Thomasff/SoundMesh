package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk

/**
 * An output with a timeline in frames, which is all the playout below needs one to be.
 *
 * It exists so the arithmetic can be tested without an audio device on the machine. The one
 * implementation that matters is [WasapiOutput]; the tests use a straight line through the
 * origin, which is what makes a sign error in the clock offset a number somebody can check.
 */
interface FrameOutput {

    /** Which frame the device will be consuming at [localNanos] on this machine's clock. */
    fun frameAtLocalNanos(localNanos: Long): Long

    /**
     * Puts [samples] on the timeline at [atFrame], or answers false because that frame has gone.
     *
     * Answering rather than throwing is the difference between this and the renderer's own
     * schedule(): a chirp that starts late has ruined the measurement it exists for and should
     * take the run down, while a stream that is one chunk late has lost twenty milliseconds and
     * must keep playing. Same arithmetic, opposite right answer.
     */
    fun schedule(samples: ShortArray, channels: Int, atFrame: Long): Boolean
}

/**
 * Puts a chunk stream on a local output at the instants the host asked for.
 *
 * This is the whole of "play along" on this side. A chunk carries an instant on the host's clock,
 * the clock estimate says what that instant is called here, and the output says which frame it is
 * consuming then.
 *
 * **Nothing accumulates.** Every chunk is placed against a fresh reading of the device's own
 * clock, so there is no running write pointer to drift and no counterpart here to the handset
 * renderer's drift controller. What that trades away is continuity at the seams: two machines
 * whose sample clocks differ by a few parts per million will now and then place neighbouring
 * chunks a frame apart or a frame over, instead of sliding steadily out of time. That is the
 * better failure of the two - it cannot grow - but whether the seams are audible is an ear
 * question and nobody has asked it yet.
 *
 * [offsetNanos] is read once per chunk rather than held. It is an estimate that improves as the
 * exchanges accumulate, and a sink that took a copy when it started would play a whole session
 * against its worst reading of the clock.
 */
class ChunkPlayout(
    private val output: FrameOutput,
    /**
     * Channels in the chunks, which the wire does not carry: the whole pipeline is stereo, from
     * SpatialShaper down, and a host sending anything else would be played at the wrong speed
     * rather than refused.
     */
    private val channels: Int = 2,
    /**
     * This machine's share of the pair's alignment constant, subtracted from the host instant the
     * same way round the handset sink subtracts its own. Zero until somebody measures it, and
     * measuring it needs a microphone in the room.
     */
    private val alignmentOffsetNanos: Long = 0L,
    private val offsetNanos: () -> Long
) {
    var played: Int = 0
        private set

    /** Chunks whose instant had already gone out by the time they got here. */
    var droppedLate: Int = 0
        private set

    /**
     * Consecutive chunks that did not land end to end, which is the fault "late" cannot see.
     *
     * Every counter here and on the host can read perfectly while this one climbs: the chunks
     * all arrive, in order, and each lands ahead of the writer. What a seam costs is the join -
     * a hole where a gap is, two signals summed where an overlap is - and at a few parts per
     * million between two machines' sample clocks it is a frame at a time, now and then.
     */
    var seams: Int = 0
        private set

    /**
     * The widest of them, signed: positive is a gap, negative an overlap.
     *
     * Kept, but read it next to the band below rather than on its own. An extremum has no
     * denominator: one run of these measured two frames and the next three measured nine, eleven
     * and thirty-five, which says nothing about whether the run was coming apart or had one bad
     * join in a thousand. A number that can only grow with the length of the run is not a level.
     */
    var worstSeamFrames: Int = 0
        private set

    /** Consecutive pairs compared so far, which is what the band below is a fraction of. */
    var joins: Int = 0
        private set

    /**
     * How wide the joins are, as a level rather than a worst case.
     *
     * Answers the frame count that [quantile] of the joins came within, counting a gap and an
     * overlap of the same width as the same width - the sign is [worstSeamFrames]' job. Clean
     * joins are in the denominator, so a run whose median is zero is a run that mostly lands end
     * to end, and the interesting reading is where the band stops being zero.
     *
     * Nearest-rank, so a quantile of 1.0 is the widest join and no value is invented between two
     * that were measured.
     */
    fun seamFramesAtQuantile(quantile: Double): Int {
        require(quantile > 0.0 && quantile <= 1.0) { "quantile must be in (0, 1], not $quantile" }
        if (joins == 0) return NO_JOINS_YET
        val rank = kotlin.math.ceil(quantile * joins).toInt()
        var seen = 0
        for (width in seamWidths.keys.sorted()) {
            seen += seamWidths.getValue(width)
            if (seen >= rank) return width
        }
        error("$rank of $joins joins is past the end of the histogram")
    }

    /**
     * The band as one line, so the two places that report it cannot drift into saying it
     * differently - a host's own playout and a sink's are the same measurement and have to be
     * comparable across a run without anybody lining up two formats by hand.
     */
    fun seamBand(): String {
        if (joins == 0) return "no joins yet"
        val worst = if (worstSeamFrames > 0) "+$worstSeamFrames" else worstSeamFrames.toString()
        return "p50 ${seamFramesAtQuantile(0.5)} / p90 ${seamFramesAtQuantile(0.9)} / " +
            "p99 ${seamFramesAtQuantile(0.99)} / worst $worst frames of $joins joins"
    }

    // Widths seen and how often, unsigned. A histogram rather than the joins themselves because
    // a run is minutes long and the widths are small integers, so this stays a handful of entries
    // however long the run goes on - and unlike a reservoir it answers exactly.
    private val seamWidths = HashMap<Int, Int>()

    // Where the chunk before this one ran out, and which one it was. Only consecutive
    // sequences are compared: a chunk the host or the network lost leaves a hole a whole chunk
    // wide, which the sequence already shows, and counting it here would bury the one-frame
    // kind these two exist to find.
    private var endOfLastChunk: Long = 0
    private var lastSequence: Int = NO_CHUNK_YET

    fun play(chunk: AudioChunk): Boolean {
        // hostNanos = localNanos + offset - alignment, which is the sink session's own arithmetic
        // read the other way round.
        val localNanos = chunk.playAtHostNanos - offsetNanos() + alignmentOffsetNanos
        val frame = output.frameAtLocalNanos(localNanos)
        val samples = samplesOf(chunk.pcm)
        if (!output.schedule(samples, channels, frame)) {
            droppedLate++
            return false
        }
        if (chunk.sequence == lastSequence + 1) {
            val seam = (frame - endOfLastChunk).toInt()
            joins++
            seamWidths.merge(kotlin.math.abs(seam), 1, Int::plus)
            if (seam != 0) {
                seams++
                if (kotlin.math.abs(seam) > kotlin.math.abs(worstSeamFrames)) worstSeamFrames = seam
            }
        }
        endOfLastChunk = frame + samples.size / channels
        lastSequence = chunk.sequence
        played++
        return true
    }

    /** Little-endian pairs of bytes, which is what ChunkCodec puts on the wire, to samples. */
    private fun samplesOf(pcm: ByteArray): ShortArray {
        val samples = ShortArray(pcm.size / 2)
        for (i in samples.indices) {
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt()
            samples[i] = ((high shl 8) or low).toShort()
        }
        return samples
    }

    companion object {
        /**
         * What the band answers before there is a join to measure.
         *
         * Not zero. Zero is the answer a run with perfect joins gives, and the two readings must
         * not print the same, or a report that runs before the second chunk arrives claims a
         * result it has not got.
         */
        const val NO_JOINS_YET = -1

        // Not -1: a host is free to start its sequence anywhere, and -1 would make the first
        // chunk of a stream that starts at zero look like the neighbour of one that never came.
        private const val NO_CHUNK_YET = Int.MIN_VALUE
    }
}
