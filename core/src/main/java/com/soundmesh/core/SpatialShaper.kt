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

    /** Stands where the room's own placement would be in a room that has no reverberation. */
    private val SILENT = StereoGain(0.0, 0.0)

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
     * [fromRoom] is [from] for the wet, and it needs its own because the two are not the same
     * number: the room's share is the placement without the distance in it, which is the whole of
     * how a reverberation reads as a source moving away. See [SpatialField.roomGainAt].
     *
     * [fromReverb] is how much room the previous chunk was heard ending in, on the same terms as
     * the three above. Its no-rule value is none of it.
     *
     * [reverb] is where the room keeps what it has heard, on the same terms as [crossover] and
     * refused the same way. Like [diffuse] and unlike [crossover] it is this handset's own, drawn
     * from its name, and like [diffuse] it must not agree across the room - see [RoomReverb]. What
     * it returns is added to this handset's dry output, which is never delayed or filtered by it:
     * the direct sound is what the handsets have to agree about and it comes through untouched.
     *
     * [diffuse] is the second of those and is refused on the same terms. Unlike the crossover it is
     * this handset's own, drawn from its name, and it is the one thing here that must not agree
     * across the room - see [Decorrelator]. It is applied after the mix has been divided up and
     * before the gain, so that a source travelling round the room keeps its envelope crisp while
     * what travels is already diffuse.
     *
     * Neither the sections nor the headroom ramp across the chunk, and neither needs an argument
     * saying where it came from. A whole number of allpass sections is not a value being
     * interpolated towards; what a chunk edge carries when this moves is a phase pattern changing,
     * which a ramp between two of them would not smooth but smear. It moves when a finger moves a
     * slider, once.
     *
     * [travel] is the third, and the one that is refused on a different test from the other two.
     * A crossover and a decorrelator are needed exactly when the knob that drives them is up; this
     * one is needed whenever the **rule** moves in time, which is not the same as whenever the
     * delay it asks for is non-zero - see [SpatialField.movesInTime]. It is also the one that is
     * fed even when it is doing nothing: a delay line holds the last few milliseconds of the song,
     * so one that stops being stepped while a knob is at zero is holding whatever was playing when
     * the knob got there, and would play that back on the way up. Stepping it at a delay of zero
     * hands every frame straight through and costs one array write.
     */
    fun shape(
        pcm: ByteArray,
        field: SpatialField,
        peerId: String,
        startHostNanos: Long,
        sampleRate: Int,
        from: StereoGain? = null,
        fromRoom: StereoGain? = null,
        fromFold: Double? = null,
        fromSpectrum: SpectrumMix? = null,
        fromReverb: Double? = null,
        crossover: Crossover? = null,
        reverb: RoomReverb? = null,
        diffuse: Decorrelator? = null,
        travel: TravellingDelay? = null
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
        // Read once per chunk rather than per frame: a coefficient is two transcendentals and the
        // rule cannot change inside a chunk. It is not ramped, because moving where a filter divides
        // leaves the signal already inside it alone - the output stays continuous through a drag.
        val coefficient = crossover?.let { Crossover.coefficientFor(field.crossoverHz, sampleRate) } ?: 0.0
        val endWet = RoomReverb.wetFor(field.reverb)
        val beginWet = fromReverb?.let { RoomReverb.wetFor(it) } ?: endWet
        require(reverb != null || (endWet <= 0.0 && beginWet <= 0.0)) {
            "a room with a reverberation in it needs somewhere to keep what the walls have heard"
        }
        // The wet's own placement, ramped like everything else here. It is the same arithmetic as
        // the gain above with the distance left out, so the two ramp together and the ratio between
        // them - which is the cue - moves smoothly through a drag. Not worked out at all when there
        // is no reverberation, because it is a pass over every handset in the room and a knob at
        // off has to cost nothing.
        val beginRoom =
            if (reverb == null) SILENT else fromRoom ?: field.roomGainAt(peerId, startHostNanos)
        val endRoom =
            if (reverb == null) SILENT else field.roomGainAt(peerId, startHostNanos + spanNanos)
        val stages = Decorrelator.stagesFor(field.diffusion)
        // Null at zero rather than a filter asked for no sections: the arithmetic below then has no
        // per-frame call at all, instead of a call that gives the sample straight back. A knob at
        // off has to cost nothing, which is what 3e2c018 was written to fix elsewhere.
        val diffuser = if (stages > 0) {
            requireNotNull(diffuse) { "a rule that pulls the handsets apart needs this one's own filter" }
        } else null
        val headroom = Decorrelator.headroomFor(stages)
        require(travel != null || !field.movesInTime) {
            "a rule whose delay moves needs somewhere to hold the frames it is moving"
        }
        // Ramped across the chunk exactly as the gain is, and for the same reason plus one. The
        // reason it shares: the law is a function of the instant, and evaluating it twice instead
        // of per frame costs a straight line through an arc of about a degree. The one of its own:
        // a step in a delay is a step in the waveform, which the ear hears as a click rather than
        // as an error - so this is the one ramp here that is not an economy but a requirement.
        val beginDelay = delaySamplesAt(travel, field, peerId, startHostNanos, sampleRate)
        val endDelay = delaySamplesAt(travel, field, peerId, startHostNanos + spanNanos, sampleRate)

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
            val sentLeft = sampleAt(pcm, at).toDouble()
            val sentRight = sampleAt(pcm, at + BYTES_PER_SAMPLE).toDouble()
            // Fed the mix as it was sent, never the folded version: the filter is a property of the
            // stream and has to hear the same thing on every handset whatever each one is playing.
            // It runs on every frame a filter exists for, so a rule arriving finds it already warm.
            val lowLeft = crossover?.lowLeft(sentLeft, coefficient) ?: 0.0
            val lowRight = crossover?.lowRight(sentRight, coefficient) ?: 0.0
            // Both channels of the fold read both channels of the source, so the source samples are
            // read out before either is written. Writing into `out` rather than `pcm` already keeps
            // them apart, and this keeps it that way if that ever changes.
            val mixLeft = whole * (own * sentLeft + fold * sentRight) + lowShare * lowLeft
            val mixRight = whole * (fold * sentLeft + own * sentRight) + lowShare * lowRight
            // Turned down before it is placed, not after: an allpass keeps the energy and moves the
            // peak, so the headroom belongs with the filter that needs it rather than with the gain
            // law, which already reaches past unity on its own.
            val heardLeft = if (diffuser == null) mixLeft else diffuser.left(mixLeft, stages) * headroom
            val heardRight = if (diffuser == null) mixRight else diffuser.right(mixRight, stages) * headroom
            val placedLeft = heardLeft * left
            val placedRight = heardRight * right
            // The room, added to what this handset made and never in front of it. The dry above is
            // a bypass: not filtered, not delayed, not touched, so the instant a sound leaves this
            // handset is the instant every other handset agrees it leaves theirs. That is the whole
            // of why a reverberation is safe here, and the note in the queue saying every handset's
            // latency through this had to match was wrong - what has to match is the direct sound.
            //
            // Fed what the stream sent rather than what this handset plays of it, on the same terms
            // as the crossover above: a room is excited by the music, not by one handset's share of
            // it, and the share is applied to what comes back out.
            //
            // A crossfade rather than a sum. The dry comes down by exactly what the wet goes up by,
            // so turning the room up cannot make a sample bigger than the same arrangement made
            // without it - which is the failure this project has actually shipped, and the reason
            // every gain added here since has been a subtraction.
            val sentOutLeft: Double
            val sentOutRight: Double
            if (reverb == null) {
                sentOutLeft = placedLeft
                sentOutRight = placedRight
            } else {
                val wet = beginWet + (endWet - beginWet) * across
                val roomLeft = beginRoom.left + (endRoom.left - beginRoom.left) * across
                val roomRight = beginRoom.right + (endRoom.right - beginRoom.right) * across
                sentOutLeft = placedLeft * (1.0 - wet) + reverb.left(sentLeft) * roomLeft * wet
                sentOutRight = placedRight * (1.0 - wet) + reverb.right(sentRight) * roomRight * wet
            }
            // Last of all, on what this handset has finished making. Anywhere earlier would delay
            // the gain envelope along with the audio, which for a source going round the room
            // means the placement and the arrival time disagree about where it is by however far
            // this handset is held back.
            if (travel == null) {
                writeSample(out, at, sentOutLeft)
                writeSample(out, at + BYTES_PER_SAMPLE, sentOutRight)
            } else {
                travel.step(sentOutLeft, sentOutRight, beginDelay + (endDelay - beginDelay) * across)
                writeSample(out, at, travel.left)
                writeSample(out, at + BYTES_PER_SAMPLE, travel.right)
            }
        }
        return out
    }

    /** Nothing at all when nothing is holding frames, so the rule is not even asked. */
    private fun delaySamplesAt(
        travel: TravellingDelay?,
        field: SpatialField,
        peerId: String,
        hostNanos: Long,
        sampleRate: Int
    ): Double =
        if (travel == null) 0.0
        else TravellingDelay.samplesFor(field.playbackDelayNanosFor(peerId, hostNanos), sampleRate)

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
