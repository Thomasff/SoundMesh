package com.soundmesh.core

import kotlin.math.roundToInt

/**
 * Multiplies one chunk of stereo PCM by the gain a spatial rule gives this handset.
 *
 * The rule is a function of the host instant and nothing else, so this needs no state and no
 * messages: every handset evaluates the same function at the same instants and the room agrees
 * without anybody coordinating. [startHostNanos] is when frame zero of this chunk is heard, which
 * is the chunk's own release instant - a trim shortens what is written but does not move the
 * instant any surviving frame lands on, so the mapping here stays right on a trimmed chunk.
 *
 * The gain ramps across the chunk rather than being held. A held gain steps at every chunk edge,
 * and at 20 ms a chunk that is a discontinuity 50 times a second.
 *
 * How loud that step is was argued when this was written and measured afterwards, and the
 * argument was wrong. Over a whole circuit the largest edge is 1.64% of full scale, -36 dB,
 * 200 ms in; a listener told to listen for it, on two handset speakers, against music, at the
 * default six second period, could not hear it. So the ramp is not buying an audible tick back.
 * What it is buying is that the step is proportional to how fast the source travels: a one
 * second circuit makes the same edge 10%, -20 dB, which is a different question. Two evaluations
 * and a linear interpolation cost nothing next to the per-frame multiply that has to happen
 * anyway, so the insurance is kept at a price of about nothing - and the ramp is a straight line
 * through an arc of about a degree, so the error against evaluating per frame is far below one
 * sample count.
 *
 * The chunk handed in is not written into. On a run with no pending frame adjustment the renderer
 * passes the AudioChunk's own array straight through, and that array may still be wanted by
 * whoever else holds the chunk.
 */
object SpatialShaper {
    private const val CHANNELS = 2
    private const val BYTES_PER_SAMPLE = 2
    private const val BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE

    /**
     * [from] is the gain the previous chunk was heard ending at, when that is not the gain this
     * rule gives this instant - a rule arriving, a rule being replaced, an icon being dragged. The
     * law is still a function of the host instant; what this argument says is where the room was
     * coming from, which the law cannot know because the previous chunk was under a different law
     * or under none. Null means the two agree, which is every chunk of ordinary playback.
     */
    fun shape(
        pcm: ByteArray,
        field: SpatialField,
        peerId: String,
        startHostNanos: Long,
        sampleRate: Int,
        from: StereoGain? = null
    ): ByteArray {
        require(sampleRate > 0) { "frames need a rate to become instants: $sampleRate" }
        require(pcm.size % BYTES_PER_FRAME == 0) {
            "not whole stereo frames: ${pcm.size} bytes"
        }
        val frames = pcm.size / BYTES_PER_FRAME
        if (frames == 0) return ByteArray(0)
        // Throws for a handset the drawing does not name. The renderer decides what to do about
        // that; silently returning silence here would empty a handset for a reason nobody can see.
        val begin = from ?: field.gainAt(peerId, startHostNanos)
        val spanNanos = frames.toLong() * 1_000_000_000L / sampleRate
        val end = field.gainAt(peerId, startHostNanos + spanNanos)

        val out = ByteArray(pcm.size)
        for (frame in 0 until frames) {
            // frame / frames, not frame / (frames - 1): the last frame stops just short of `end`,
            // which is exactly where the next chunk's first frame starts. Reaching `end` here
            // would render that instant twice and leave a one-frame flat spot at every edge.
            val across = frame.toDouble() / frames
            val left = begin.left + (end.left - begin.left) * across
            val right = begin.right + (end.right - begin.right) * across
            val at = frame * BYTES_PER_FRAME
            writeSample(out, at, sampleAt(pcm, at) * left)
            writeSample(out, at + BYTES_PER_SAMPLE, sampleAt(pcm, at + BYTES_PER_SAMPLE) * right)
        }
        return out
    }

    /** One little-endian 16-bit sample, sign extended. */
    private fun sampleAt(pcm: ByteArray, at: Int): Int =
        ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()

    /**
     * Clamped, not wrapped. Whole-room power normalisation asks for more than unity whenever one
     * handset carries a side by itself, and a 16-bit sample that overflows changes sign rather
     * than getting louder - the loudest defect the format has.
     */
    private fun writeSample(pcm: ByteArray, at: Int, value: Double) {
        val clamped = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        pcm[at] = (clamped and 0xFF).toByte()
        pcm[at + 1] = (clamped shr 8).toByte()
    }
}
