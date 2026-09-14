package com.soundmesh.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns one chunk of stereo PCM into what one handset plays of it.
 *
 * Three steps, and they answer different questions. The fold and the spectrum decide which part of
 * the mix this handset carries - all of it, what the two channels share or disagree about, or what
 * lies below or above the crossover. The gain decides how loudly the room wants that part just now.
 * All three ramp across the chunk, and for the same reason: each one steps at a chunk edge when the
 * listener moves the control that drives it.
 *
 * The two ways of dividing the mix are never both in force, because a rule carries one axis; the
 * one not chosen returns its own identity and the arithmetic below runs regardless. That costs a
 * multiply per sample and buys one path through this loop instead of two.
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
     *
     * [fromFold] is that same argument for the fold, and it needs its own because the two move for
     * different reasons: dragging an icon moves the gain and leaves the fold where it was, dragging
     * the separation knob does the reverse. Zero is what a handset under no rule was heard at - the
     * fold being how much of the other channel it was folding in, which was none of it.
     *
     * [fromSpectrum] is the third of them, and its no-rule value is the whole mix and none of the
     * filter. It is separate again because the axis itself can change under a handset, and swapping
     * axes moves both this and the fold at once while the gain stays where it is.
     *
     * [crossover] is where the filter keeps what it has heard, so it belongs to the stream rather
     * than to this call: one per playing handset, handed in every chunk. Required whenever the rule
     * asks for any of the low half and refused when it is missing, because a filter that is not
     * there and a knob at zero sound exactly alike.
     *
     * [halves] is the same arrangement for the third axis, and carries far more: see [Separation].
     * Required whenever the rule divides the held from the struck, and refused when missing for the
     * same reason. It is the one thing here that gives back a **different** sample rather than a
     * part of the one it was handed, and the sound it gives back is [Separation.held] samples
     * older - so a room on this axis plays one window behind a room on either of the others.
     */
    fun shape(
        pcm: ByteArray,
        field: SpatialField,
        peerId: String,
        startHostNanos: Long,
        sampleRate: Int,
        from: StereoGain? = null,
        fromFold: Double? = null,
        fromSpectrum: SpectrumMix? = null,
        crossover: Crossover? = null,
        halves: Separation? = null
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

        val endFold = field.foldFor(peerId)
        val beginFold = fromFold ?: endFold

        val endSpectrum = field.spectrumFor(peerId)
        val beginSpectrum = fromSpectrum ?: endSpectrum
        require(crossover != null || (endSpectrum.low == 0.0 && beginSpectrum.low == 0.0)) {
            "a low/high split needs somewhere to keep what the filter has heard"
        }
        val separating = field.splitAxis == SplitAxis.HELD_STRUCK
        require(halves != null || !separating) {
            "a held/struck split needs somewhere to keep what the separation has heard"
        }
        // Set once for the chunk and not ramped, for the same reason as the crossover coefficient:
        // a share here belongs to a whole frame rather than to a sample. It does not step either -
        // frames overlap by three quarters and are added through the synthesis window, so a share
        // changed between two of them is heard as a crossfade across one window.
        if (separating) halves!!.keep(field.halvesFor(peerId))
        // Read once per chunk rather than per frame: a coefficient is two transcendentals and the
        // rule cannot change inside a chunk. It is not ramped, because moving where a filter divides
        // leaves the signal already inside it alone - the output stays continuous through a drag.
        val coefficient = crossover?.let { Crossover.coefficientFor(field.crossoverHz, sampleRate) } ?: 0.0

        val out = ByteArray(pcm.size)
        for (frame in 0 until frames) {
            // frame / frames, not frame / (frames - 1): the last frame stops just short of `end`,
            // which is exactly where the next chunk's first frame starts. Reaching `end` here
            // would render that instant twice and leave a one-frame flat spot at every edge.
            val across = frame.toDouble() / frames
            val left = begin.left + (end.left - begin.left) * across
            val right = begin.right + (end.right - begin.right) * across
            val at = frame * BYTES_PER_FRAME
            val fold = beginFold + (endFold - beginFold) * across
            val own = 1.0 - abs(fold)
            val whole = beginSpectrum.whole + (endSpectrum.whole - beginSpectrum.whole) * across
            val lowShare = beginSpectrum.low + (endSpectrum.low - beginSpectrum.low) * across
            val sentLeft = sampleAt(pcm, at)
            val sentRight = sampleAt(pcm, at + BYTES_PER_SAMPLE)
            // Fed the mix as it was sent, never the folded version: the filter is a property of the
            // stream and has to hear the same thing on every handset whatever each one is playing.
            // It runs on every frame a filter exists for, so a rule arriving finds it already warm.
            val lowLeft = crossover?.lowLeft(sentLeft.toDouble(), coefficient) ?: 0.0
            val lowRight = crossover?.lowRight(sentRight.toDouble(), coefficient) ?: 0.0
            // Fed the same mix, and the one thing in this loop that hands back a different sample
            // rather than a part of the one it was given: what comes out is this handset's half and
            // it is Separation.held samples older. Unlike the filter it is not kept warm while the
            // room is not asking for it, because a window of transforms is too expensive to run
            // for nobody - which is what Separation.forget is for.
            val heardLeft = if (separating) halves!!.leftOf(sentLeft.toDouble()) else sentLeft.toDouble()
            val heardRight = if (separating) halves!!.rightOf(sentRight.toDouble()) else sentRight.toDouble()
            // Both channels of the fold read both channels of the source, so the source samples are
            // read out before either is written. Writing into `out` rather than `pcm` already keeps
            // them apart, and this keeps it that way if that ever changes.
            writeSample(out, at, (whole * (own * heardLeft + fold * heardRight) + lowShare * lowLeft) * left)
            writeSample(
                out, at + BYTES_PER_SAMPLE,
                (whole * (fold * heardLeft + own * heardRight) + lowShare * lowRight) * right
            )
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
