package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.DriftController
import com.soundmesh.core.TonePcmSource
import kotlin.math.roundToInt

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

    /**
     * Forgets everything scheduled that has not started to play. What a host jumping - a new
     * song, a seek, a pause - asks of every sink: the next second and a half already queued is
     * the old place, and the handsets throw theirs away too (PlaybackScheduler.clear).
     */
    fun dropScheduled() {}
}

/** The three things a seam is made of - see [ChunkPlayout.seamShareFramesAtQuantile]. */
enum class SeamShare { HOST, OFFSET, DEVICE }

/**
 * Puts a chunk stream on a local output at the instants the host asked for.
 *
 * This is the whole of "play along" on this side. A chunk carries an instant on the host's clock,
 * the clock estimate says what that instant is called here, and the output says which frame it is
 * consuming then.
 *
 * **Every chunk is measured, and written end to end.** Each chunk's frame is read against a fresh
 * reading of the device's own clock, so nothing in the estimate can accumulate. But that reading
 * wobbles by a frame, and obeying it put a hole or two summed samples at about half of all joins
 * - some 25 edits a second, which a listener heard as crackle on 09-23, the same number the
 * handset renderer's own notes give for its "clicks". So the reading only steers: a chunk is
 * butted against its neighbour, and once the median of the last few readings says the timeline
 * sits more than [NUDGE_DEADBAND_FRAMES] off, one chunk is drawn a frame longer or shorter. That
 * is the handset's drift controller, with a stretch in place of its dropped or doubled frame.
 * Beyond [REPLACE_BEYOND_FRAMES] the chunk is simply placed where the clock says.
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

    /** Times the host started its sequence again, and what was queued was thrown away. */
    var restarts: Int = 0
        private set

    /**
     * Consecutive chunks the clock reading did not put end to end.
     *
     * This is what the reading said, not what was written: the chunks themselves are butted
     * together and only [nudges] and [replaced] edit the sound. It is kept because the reading is
     * what a bad run shows up in, and its shares below say which part of it moved.
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
    fun seamFramesAtQuantile(quantile: Double): Int = synchronized(stats) { quantileOf(seamWidths, quantile) }

    /**
     * How wide one share of the joins is, as a band in the same sense as [seamFramesAtQuantile].
     *
     * A seam is three things added: how far the host's two instants sat from a chunk apart, how
     * far the clock offset moved between the two readings, and what is left once those two are
     * taken out - the device's clock, read once per chunk. The seam alone cannot say which of the
     * three moved, and on 09-23 one bad run in six could not be put on any of them because nothing
     * was keeping this. Each share is rounded on its own, so a join made of fractions of a frame
     * can leave a frame of rounding in the device's share.
     */
    fun seamShareFramesAtQuantile(share: SeamShare, quantile: Double): Int =
        synchronized(stats) { quantileOf(shareWidths.getValue(share), quantile) }

    /** The shares as one line, beside [seamBand]; p90 and p99 because p50 is zero on a good run. */
    fun seamShares(): String = synchronized(stats) {
        if (joins == 0) return@synchronized "no joins yet"
        SeamShare.entries.joinToString(", ") { share ->
            "${share.name.lowercase()} p90 ${quantileOf(shareWidths.getValue(share), 0.9)} / " +
                "p99 ${quantileOf(shareWidths.getValue(share), 0.99)} / max ${quantileOf(shareWidths.getValue(share), 1.0)}"
        }
    }

    // Not synchronized itself: quantileOf only ever runs from callers that already hold stats
    // (seamFramesAtQuantile, seamShareFramesAtQuantile, seamShares, seamBand).
    private fun quantileOf(widths: Map<Int, Int>, quantile: Double): Int {
        require(quantile > 0.0 && quantile <= 1.0) { "quantile must be in (0, 1], not $quantile" }
        if (joins == 0) return NO_JOINS_YET
        val rank = kotlin.math.ceil(quantile * joins).toInt()
        var seen = 0
        for (width in widths.keys.sorted()) {
            seen += widths.getValue(width)
            if (seen >= rank) return width
        }
        error("$rank of $joins joins is past the end of the histogram")
    }

    /**
     * The band as one line, so the two places that report it cannot drift into saying it
     * differently - a host's own playout and a sink's are the same measurement and have to be
     * comparable across a run without anybody lining up two formats by hand.
     */
    fun seamBand(): String = synchronized(stats) {
        if (joins == 0) return@synchronized "no joins yet"
        val worst = if (worstSeamFrames > 0) "+$worstSeamFrames" else worstSeamFrames.toString()
        "p50 ${quantileOf(seamWidths, 0.5)} / p90 ${quantileOf(seamWidths, 0.9)} / " +
            "p99 ${quantileOf(seamWidths, 0.99)} / worst $worst frames of $joins joins; " +
            "heard: $nudges nudged, $replaced re-placed"
    }

    /** Chunks drawn a frame longer or shorter to follow the clock - see the class note. */
    var nudges: Int = 0
        private set

    /** Chunks placed where the clock said rather than against their neighbour, which leaves a join. */
    var replaced: Int = 0
        private set

    // Widths seen and how often, unsigned. A histogram rather than the joins themselves because
    // a run is minutes long and the widths are small integers, so this stays a handful of entries
    // however long the run goes on - and unlike a reservoir it answers exactly.
    private val seamWidths = HashMap<Int, Int>()
    private val shareWidths = SeamShare.entries.associateWith { HashMap<Int, Int>() }

    // Guards joins, seams, worstSeamFrames, seamWidths and shareWidths: play() writes them on the
    // chunk thread, while a status reader - the window's poll every 500 ms, the command-line
    // sink's report loop - reads them from another thread. A read landing between joins++ and the
    // histogram merge used to walk quantileOf off the end of the histogram.
    private val stats = Any()

    // Where the chunk before this one ran out, and which one it was. Only consecutive
    // sequences are compared: a chunk the host or the network lost leaves a hole a whole chunk
    // wide, which the sequence already shows, and counting it here would bury the one-frame
    // kind these two exist to find.
    private var endOfLastChunk: Long = 0
    private var lastSequence: Int = NO_CHUNK_YET

    // Where what was actually written ran out, which the next consecutive chunk is butted against,
    // and the filter that decides when that timeline has wandered far enough from the readings to
    // nudge. A fresh filter whenever the timeline is re-placed: readings taken against the old one
    // say nothing about the new.
    private var endOfLastWrite: Long = 0
    private var steer = DriftController(NUDGE_DEADBAND_FRAMES)

    // What the chunk before this one was placed with, so a seam can be split into shares.
    private var lastPlayAtHostNanos: Long = 0
    private var lastOffsetNanos: Long = 0
    private var lastChunkFrames: Int = 0

    fun play(chunk: AudioChunk): Boolean {
        // The host starts its sequence again whenever it jumps, and only then: what is queued
        // from before is the place it jumped away from.
        if (lastSequence != NO_CHUNK_YET && chunk.sequence < lastSequence) {
            output.dropScheduled()
            restarts++
        }
        // Read once, so the share below is the offset this chunk was actually placed with.
        val offset = offsetNanos()
        // hostNanos = localNanos + offset - alignment, which is the sink session's own arithmetic
        // read the other way round.
        val localNanos = chunk.playAtHostNanos - offset + alignmentOffsetNanos
        val frame = output.frameAtLocalNanos(localNanos)
        val measuredSamples = samplesOf(chunk.pcm)
        // Only a neighbour can be butted against. A chunk after a hole, and the first of all, go
        // where the reading says.
        val consecutive = chunk.sequence == lastSequence + 1
        var at = frame
        var samples = measuredSamples
        var nudge = 0
        var replace = false
        if (consecutive) {
            // Median filtered inside, so one stray reading neither nudges nor re-places.
            val decision = steer.observe((frame - endOfLastWrite).toInt())
            if (kotlin.math.abs(decision.filteredErrorFrames) > REPLACE_BEYOND_FRAMES) {
                replace = true
            } else {
                at = endOfLastWrite
                nudge = decision.adjustFrames
                if (nudge != 0) samples = stretched(measuredSamples, measuredSamples.size / channels + nudge)
            }
        }
        if (!output.schedule(samples, channels, at)) {
            droppedLate++
            return false
        }
        if (!consecutive || replace) steer = DriftController(NUDGE_DEADBAND_FRAMES)
        endOfLastWrite = at + samples.size / channels
        if (consecutive) {
            synchronized(stats) {
                if (nudge != 0) nudges++
                if (replace) replaced++
                // Reading against reading, as before the chunks were butted together, so the band
                // and its shares stay comparable with every run already written down.
                val seam = (frame - endOfLastChunk).toInt()
                joins++
                seamWidths.merge(kotlin.math.abs(seam), 1, Int::plus)
                if (seam != 0) {
                    seams++
                    if (kotlin.math.abs(seam) > kotlin.math.abs(worstSeamFrames)) worstSeamFrames = seam
                }
                val host = framesOf(chunk.playAtHostNanos - lastPlayAtHostNanos) - lastChunkFrames
                // The offset is subtracted from the host instant, so an offset that grew moves the
                // chunk earlier.
                val offsetShare = -framesOf(offset - lastOffsetNanos)
                countShare(SeamShare.HOST, host)
                countShare(SeamShare.OFFSET, offsetShare)
                countShare(SeamShare.DEVICE, seam - host - offsetShare)
            }
        }
        endOfLastChunk = frame + measuredSamples.size / channels
        lastSequence = chunk.sequence
        lastPlayAtHostNanos = chunk.playAtHostNanos
        lastOffsetNanos = offset
        lastChunkFrames = measuredSamples.size / channels
        played++
        return true
    }

    /**
     * [samples] drawn over [frames] frames by straight-line interpolation, first and last frames
     * kept where they were so both joins stay as smooth as the music. One frame in 960 is a pitch
     * change of a thousandth for 20 ms.
     */
    private fun stretched(samples: ShortArray, frames: Int): ShortArray {
        val from = samples.size / channels
        val out = ShortArray(frames * channels)
        for (i in 0 until frames) {
            val position = i.toDouble() * (from - 1) / (frames - 1)
            val k = position.toInt().coerceAtMost(from - 2)
            val w = position - k
            for (c in 0 until channels) {
                val a = samples[k * channels + c]
                val b = samples[(k + 1) * channels + c]
                out[i * channels + c] = (a + (b - a) * w).roundToInt().toShort()
            }
        }
        return out
    }

    // At the stream's rate, which WasapiOutput refuses to run at anything other than.
    private fun framesOf(nanos: Long): Double = nanos * TonePcmSource.SAMPLE_RATE / 1e9

    private fun countShare(share: SeamShare, frames: Double) {
        shareWidths.getValue(share).merge(kotlin.math.abs(frames).roundToInt(), 1, Int::plus)
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

        /**
         * How far the median reading may sit off the written timeline before a chunk is nudged:
         * 0.17 ms. The raw reading's own wobble was p99 2-3 frames on 09-23 and the median of
         * five cuts that further, so this is about three times the noise - a guess at the margin,
         * not a measurement of it. Smaller only moves the steady error; the nudge rate is the two
         * clocks' drift either way.
         */
        const val NUDGE_DEADBAND_FRAMES = 8

        /** The handset's product trim band (PRODUCT_TRIM_FRAMES), 5 ms, beyond which a reading is a move. */
        const val REPLACE_BEYOND_FRAMES = 240
    }
}
