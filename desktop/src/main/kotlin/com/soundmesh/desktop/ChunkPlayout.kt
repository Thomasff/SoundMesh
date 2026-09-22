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

    fun play(chunk: AudioChunk): Boolean {
        // hostNanos = localNanos + offset - alignment, which is the sink session's own arithmetic
        // read the other way round.
        val localNanos = chunk.playAtHostNanos - offsetNanos() + alignmentOffsetNanos
        val frame = output.frameAtLocalNanos(localNanos)
        if (!output.schedule(samplesOf(chunk.pcm), channels, frame)) {
            droppedLate++
            return false
        }
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
}
