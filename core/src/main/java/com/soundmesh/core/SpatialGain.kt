package com.soundmesh.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** What a handset multiplies its two channels by. Both channels are scaled, never delayed. */
data class StereoGain(val left: Double, val right: Double)

/**
 * The three things a room full of handsets can be made to do.
 *
 * All three place a source by loudness, and for a long time this paragraph said that was the only
 * kind of rule the system could carry: a listener locates a sound mainly by the time difference
 * between their ears, whose whole range is +-690 us, while the alignment between two handsets is
 * good to about 1 ms - steering with an error as large as the whole control range.
 *
 * That argument is about a different delay. The +-690 us is the gap between one listener's two
 * ears, and nothing here can put a sound between somebody's ears. The gap between two handsets
 * standing metres apart is a coarser quantity with its own name: from about one millisecond to
 * about thirty, whichever handset plays first takes the image however loud the others are. A
 * millisecond of alignment error is small against thirty milliseconds, not larger than it.
 *
 * Whether that coarser cue is worth using is [SpatialField.travelDelayNanos]'s question and it is
 * still open - the knob that asks it ships at zero. But it is a question, not something the
 * clocks rule out.
 */
enum class SpatialMode {
    /** A source that circles the listener on its own, once per period. */
    ROTATE,

    /** A source the listener drags along the arc in front of them. */
    PAN,

    /** No moving source: each handset carries the side of the stereo image it stands on. */
    SPLIT
}

/**
 * The two ways a room can be told to split the song up between its handsets.
 *
 * One at a time, and the same knob and the same list of handsets drive whichever is chosen. Two
 * separations running at once would need four parts named on a screen that has room for two, and
 * would hand a handset the half of an axis its listener never touched.
 */
enum class SplitAxis {
    /** What the two channels share against what they disagree about: the middle of the image against its edges. */
    MIDDLE_SIDES,

    /** What is below the crossover against what is above it. */
    LOW_HIGH
}

/**
 * What one handset takes of the mix it was sent and of the low half of that mix.
 *
 * Two numbers rather than one because the low and high halves are not each other with a sign
 * turned round, the way the middle and the sides are: the high half is defined by subtraction, so
 * the handset carrying it keeps the whole mix and takes the low half away, while the one carrying
 * the low half does the opposite and lets go of the mix.
 */
data class SpectrumMix(val whole: Double, val low: Double)

/**
 * One rule for turning a host instant into every handset's pair of gains.
 *
 * Every chunk already carries the host instant it is to be played at, so a gain expressed as a
 * function of that instant is the same on every handset without anything being sent between them.
 * That is what the alignment work buys here: the sweep stays in step across the room because the
 * handsets agree about the clock, not because a message arrives on time.
 *
 * [ROTATE][SpatialMode.ROTATE] and [PAN][SpatialMode.PAN] are the same law driven two ways - a
 * clock or a slider - because a pan control that did not agree with the rotation about where
 * "right" is would put the same sound in two places depending on which one last touched it.
 */
data class SpatialField(
    val mode: SpatialMode,
    val layout: SpatialLayout,
    /** How long one circuit takes. Only read by [SpatialMode.ROTATE]. */
    val periodNanos: Long = DEFAULT_PERIOD_NANOS,
    /** Where the listener has dragged the source: -1 hard left, +1 hard right. Only read by [SpatialMode.PAN]. */
    val pan: Double = 0.0,
    /** The instant the circuit is measured from, so every handset starts the sweep at the same angle. */
    val epochHostNanos: Long = 0L,
    /**
     * How far apart the room pulls the mix, from 0 (every handset plays all of it) to 1.
     *
     * A knob rather than a switch because it is also the way this degrades. Separating the mix asks
     * much more of the alignment than placing it does: the handsets are no longer playing the same
     * waveform, so the time between them stops being a colouration and becomes the thing that decides
     * where the listener hears the sound. Past about a millisecond the earlier handset takes the
     * image outright and the separation is not merely spoiled but gone. Winding this down lands the
     * room back on the mix it was already playing, which is a worse effect rather than a broken one.
     */
    val separation: Double = 0.0,
    /** Which way the mix is pulled apart. The knob and [otherHalfIds] mean whatever this says they mean. */
    val splitAxis: SplitAxis = SplitAxis.MIDDLE_SIDES,
    /**
     * Where the low half stops, in hertz. Only read by [SplitAxis.LOW_HIGH].
     *
     * Not ramped when it moves, and it does not need to be: changing where a filter divides does
     * not move the signal already inside it, so the output stays continuous through a drag.
     */
    val crossoverHz: Double = DEFAULT_CROSSOVER_HZ,
    /**
     * Which handsets carry the far half of whichever split [splitAxis] names - the sides, or the
     * high. Every handset the drawing names and this does not carries the near half.
     */
    val otherHalfIds: Set<String> = emptySet(),
    /**
     * The host instant from which this rule applies, or zero for as soon as it is seen.
     *
     * [gainAt] has never needed one: it is a function of the instant, so two handsets evaluating
     * it on the same chunk agree whatever moment they were told the rule. [foldFor] and
     * [spectrumFor] are not functions of the instant - they change when a message lands, and two
     * handsets are told a few milliseconds apart. While they disagree the room is playing two
     * halves that were taken out of the mix under different rules, so they no longer add back up
     * to it, which is the property the whole split rests on.
     *
     * How much that costs was measured 09-11 rather than assumed, because the fix is not free in
     * control lag. Over the small steps a finger makes while dragging a slider it is -39 dB under
     * the music, which is where a listener could not hear a chunk edge on 09-09. Over one large
     * change - the first rule of a session, a knob thrown across its range, a handset catching up
     * after a stall - it is -10 to -14 dB, which is not a subtlety. So this is here for the large
     * ones.
     *
     * Zero, or an instant already past, means apply at once. That is what a handset told too late
     * does, and it is exactly what every rule did before this field existed - so the failure this
     * degrades to is the behaviour it replaced, never worse than it.
     */
    val effectiveAtHostNanos: Long = 0L,
    /**
     * How many metres one unit of the drawing is, or zero when nothing knows.
     *
     * The one number in this system that carries a unit, and it exists for exactly one reader:
     * [arrivalDelayNanosFor]. Every gain is a ratio of two radii and does not change when the
     * same room is drawn larger, which is why [SpatialLayout] has no scale and has never needed
     * one. Time is not a ratio. Two metres is 5.8 ms whatever the drawing looks like.
     *
     * Zero means the room has never been measured **with the listener in it**, and that is the
     * ordinary state rather than a fault. A room that measured only handset to handset knows how
     * large it is and still cannot say how far anybody is sitting from anything - the listener is
     * assumed to be in the middle of the handsets, which is where nobody sits. A delay computed
     * from an assumed listener holds the wrong handset back, and holding the wrong handset back
     * is worse than holding none: the error it adds is the error it was meant to remove, doubled.
     */
    val metresPerUnit: Double = 0.0,
    /**
     * How much of itself every handset keeps however far the source is from it: 0 to 1.
     *
     * Zero is the law as it was written, and it has one property a listener objected to on
     * 09-11: the raised cosine is exactly zero at the direction opposite a handset, so once per
     * revolution each handset goes completely silent. What that sounds like is not a source
     * travelling round a room - it is one phone playing, then another one, which is what they
     * said: "听起来是拿着一部手机在转".
     *
     * This lifts the whole pattern onto a floor: a handset facing the source still plays at one,
     * a handset the source has turned its back on plays at this. The null does not move - it is
     * still exactly opposite, which is where they said it belonged - it simply stops being
     * silence. At 0.25 the far side sits 12 dB under the near one, which is a direction anybody
     * can hear and not a handset switching off.
     *
     * Only [ROTATE][SpatialMode.ROTATE] and [PAN][SpatialMode.PAN] read it. The split has no
     * source to be facing away from.
     */
    val envelopment: Double = 0.0,
    /**
     * How hard every handset is pushed into playing a different waveform from all the others: 0 to 1.
     *
     * Every other knob here decides **which part** of the mix a handset carries and **how loudly**.
     * This one decides nothing about either. It exists because with all of them playing one mix the
     * ear does not hear a room full of sound - it hears one source, at whichever handset reached it
     * first, and no arrangement of levels argues with that. What argues with it is the copies no
     * longer being copies, which is a phase transform and not a level. See [Decorrelator].
     *
     * Read in every mode, unlike [envelopment]. A room with the separation knob at zero is every
     * handset playing the identical mix, which is both the ordinary setting and the one where the
     * sound collapses onto the nearest handset hardest.
     *
     * A fraction rather than a count of filter sections, so the ladder underneath can be re-cut
     * without a wire version. Zero means the filter does not run at all.
     */
    val diffusion: Double = 0.0,
    /**
     * How far the side of the room the source has turned its back on is held back, in nanoseconds.
     *
     * Zero, and zero is the setting this project has shipped with since it began. Every rule here
     * is otherwise an amplitude rule, and the comment at the top of this file says why: a listener
     * places a sound by the time difference between their own two ears, which spans +-690 us, and
     * we cannot steer inside that. This is the other time cue - the precedence effect, out in the
     * room, spanning one to thirty milliseconds - and it is a genuinely available handle that this
     * system has never used.
     *
     * It is off by default because being available is not the same as being good, and the argument
     * against it is already written down: docs/feasibility-results/delay-as-a-spatial-cue.md,
     * 09-14. Past about a millisecond the earlier handset takes the image outright however the
     * levels are set, so winding this up does not move a source between handsets - it hands the
     * source to one handset and then to the next, which is an amplified version of exactly what a
     * listener complained about on 09-11 ("听起来是拿着一部手机在转") and which [envelopment] was
     * added to cure. And two handsets playing the same waveform a few milliseconds apart comb
     * filter at the listener, which is the thing [arrivalDelayNanosFor] exists to remove.
     *
     * So it is here as a knob somebody can turn and listen to, not as a setting anything picks for
     * them. That is the honest state of it: the reasoning says it will sound worse, the reasoning
     * has not been tested on a phone, and the experiment costs one slider.
     */
    val travelDelayNanos: Long = 0L,
    /**
     * How far each handset's own output wanders back and forth, in nanoseconds.
     *
     * The other feature [TravellingDelay] was built for, and the one with no argument against it.
     * It steers nothing: every handset wanders on its own slow cycle, spread so that no two are
     * ever doing the same thing, and the room has no direction it is trying to say. What it adds
     * is the one property [diffusion] cannot have - **change**. An allpass gives every handset a
     * different waveform, which a listener hears once and then stops hearing, because a fixed
     * difference is what a room is. Something that moves goes on being heard.
     *
     * A few milliseconds of independent wander per speaker is what a chorus is, and the reason to
     * expect it to work here rather than merely to work is that a chorus is normally squeezed
     * through two speakers in front of somebody, where the parts have nowhere to go but into each
     * other. Here the parts are already several metres apart.
     */
    val shimmerDelayNanos: Long = 0L,
    /** How long one handset's wander takes at the slowest slot. See [shimmerDelayNanos]. */
    val shimmerPeriodNanos: Long = DEFAULT_SHIMMER_PERIOD_NANOS,
    /**
     * A head start for [skewPeerId] against every other handset, in nanoseconds, set by hand.
     *
     * Instrumentation rather than an effect, and it exists because every other time cue in this
     * file is one nobody can hold still. The travel part is a function of where the source is
     * pointing and the wander is a function of the clock, so a listener trying to find out what a
     * few milliseconds do to a room is asked to hear an amount that is never the same twice. On
     * 09-16 a listener spent an evening unable to say whether what they could just about hear was
     * the feature or the wish. What was missing was not depth: it was a delay a person can set to
     * a number, leave there, and walk around.
     *
     * A difference and not a delay, which is why it is signed. Nothing in this project can play
     * early - a delay line cannot hand out a frame that has not arrived - so a negative value
     * holds every **other** handset back by its size instead. For the two handsets a listener
     * puts to their left and right that is the same room seen from the other side, and being the
     * gap between two handsets is the whole of what this claims to be.
     *
     * Off at zero, and nothing keeps it: it is not written to the saved drawing, so it is back at
     * zero the next time the app starts. That is deliberate for a control that has no name a
     * listener would recognise on a screen they were not expecting it on.
     */
    val skewNanos: Long = 0L,
    /**
     * Which handset the sign of [skewNanos] is about, or null when nothing is skewed.
     *
     * Named rather than taken as the first handset in the drawing. The room's own roster does put
     * the host first, deliberately, but that is a convention held two files away from here - and
     * a rule whose meaning silently reverses when a drawing is reordered is a rule that gets read
     * backwards with nothing on any screen to say so.
     */
    val skewPeerId: String? = null,
    /**
     * Whether the handset [skewNanos] makes late is also made quieter, by the distance that delay
     * stands for.
     *
     * A delay is not an effect. Sound covers 343 metres a second, so holding a handset back by ten
     * milliseconds is, to an ear, that handset standing three and a half metres further away - and
     * a handset three and a half metres further away is quieter. Until this existed the room said
     * both things at once: the delay said one handset had moved back, the levels said nothing had
     * moved, and a listener got two accounts of the same room that could not both be true. That is
     * the likeliest reason a gap wound up steadily reads as barely moving - see the 09-16 note in
     * docs/feasibility-results.
     *
     * The physical picture this puts the room in is one source and one reflection: the early
     * handset is the sound, the late one is the same sound off a wall that much further round. That
     * is the arrangement the precedence effect exists to decode, which is why the direction stays
     * with the early handset however far down this takes the late one.
     *
     * Only the handset that waits, and only downwards. The room's overall level falls too, by up to
     * three decibels and no further, because the limit of taking one of two handsets away is being
     * left with one - which is what the arithmetic here does on its own, provided the attenuation
     * lands after the room is normalised rather than inside the placement.
     *
     * Off at zero, kept nowhere, gone at the next start - the same terms as [skewNanos], whose
     * experiment it is half of.
     */
    val skewRecedes: Boolean = false,
    /**
     * How far the whole room is pulled back, from 0 at where it stands to 1 at the furthest this
     * allows. Every handset, together, by the same amount.
     *
     * How far away the source itself is, which is a different fact from where it is or what the
     * room between it and a listener does. Nothing drives this today: it is the mechanism waiting
     * for the control that asks "how far off is the sound", and it is deliberately not what
     * [skewRecedes] uses.
     *
     * The distinction is the one that took a conversation to get right. Winding the gap up lengthens
     * the path the late copy takes; it does not move the source. Pushing the far wall of a room back
     * does not make the record player quieter. So a gap that turns the whole room down is describing
     * a room nobody is in, and the whole-room fall a gap really does produce is the small one that
     * falls out of losing part of one copy - at most three decibels - rather than anything this
     * number would be set to.
     *
     * Applied after the room is normalised rather than inside the placement, which is not a
     * detail: [gainAt] divides by the room's own power so that the room is equally loud whatever
     * the rule asks for, and an attenuation applied before that division is divided straight back
     * out. A quieter room has to be asked for on the far side of the arithmetic that exists to
     * stop rooms being quieter.
     *
     * Off at zero, kept nowhere, gone at the next start - on the same terms and for the same
     * reasons as [skewNanos].
     */
    val retreat: Double = 0.0,
    /**
     * How much of what a handset plays is the room rather than the source, from none to all of it.
     *
     * The reverberation is the one cue for distance that is not ambiguous. A level on its own never
     * is - a whisper close by and a shout across the room can reach an ear at the same loudness, and
     * that is why [retreat] on its own reads as the volume going down. What a listener actually uses
     * is the ratio between what came straight from the source and what came off the walls: the
     * direct sound falls with distance and the reverberation very nearly does not, so the ratio
     * says how far off the source is and nothing else can be mistaken for it.
     *
     * Which is why this is a setting of its own rather than something [retreat] drives. How far the
     * source is and how live the room is are two different facts about a room, and one slider
     * cannot carry both - the same argument that put the source on the drawing as a point instead
     * of on a slider, one dimension up.
     *
     * Zero by default, and zero is exactly nothing rather than nearly nothing: no reverberation is
     * built, no filter runs, and the handset plays what it played before this existed, sample for
     * sample. See [RoomReverb] for what is built when it is not zero, and why every handset builds
     * a different one.
     */
    val reverb: Double = 0.0
) {
    init {
        require(periodNanos > 0L) { "a circuit takes time: $periodNanos" }
        require(metresPerUnit >= 0.0 && metresPerUnit.isFinite()) {
            "a room cannot be a negative number of metres across: $metresPerUnit"
        }
        // One would be every handset playing everything equally, which is a room with no
        // direction in it at all - and the modes that read this exist to put a source somewhere.
        require(envelopment in 0.0..MAX_ENVELOPMENT) {
            "how much a handset keeps runs from 0 to $MAX_ENVELOPMENT: $envelopment"
        }
        require(pan in -1.0..1.0) { "pan runs from -1 to +1: $pan" }
        require(diffusion in 0.0..1.0) { "how far apart the waveforms are pushed runs from 0 to 1: $diffusion" }
        require(separation in 0.0..1.0) { "separation runs from 0 to 1: $separation" }
        require(travelDelayNanos in 0L..MAX_TRAVEL_DELAY_NANOS) {
            "a handset cannot be held back by a negative time, nor past $MAX_TRAVEL_DELAY_NANOS: $travelDelayNanos"
        }
        require(shimmerDelayNanos in 0L..MAX_SHIMMER_DELAY_NANOS) {
            "the wander runs from 0 to $MAX_SHIMMER_DELAY_NANOS nanoseconds: $shimmerDelayNanos"
        }
        // Floored rather than merely positive, and the floor is load bearing: together with
        // [MAX_SHIMMER_DELAY_NANOS] and [SHIMMER_RATE_SLOTS] it is what keeps the steepest wander
        // any legal rule can ask for underneath [TravellingDelay.MAX_SLEW_SAMPLES], so the slew
        // limiter never has to clip this feature's own shape. SpatialDelayTest asserts the sum.
        require(shimmerPeriodNanos in SHORTEST_SHIMMER_PERIOD_NANOS..LONGEST_SHIMMER_PERIOD_NANOS) {
            "a wander runs from $SHORTEST_SHIMMER_PERIOD_NANOS to " +
                "$LONGEST_SHIMMER_PERIOD_NANOS ns: $shimmerPeriodNanos"
        }
        require(crossoverHz in LOWEST_CROSSOVER_HZ..HIGHEST_CROSSOVER_HZ) {
            "a crossover has to be somewhere a person can hear: $crossoverHz"
        }
        // Refused rather than ignored: a name that is in neither the drawing nor an error message is
        // a handset the listener assigned and cannot see the assignment of.
        require(otherHalfIds.all { layout.contains(it) }) {
            "these handsets carry the sides but are not in the drawing: ${otherHalfIds.filterNot { layout.contains(it) }}"
        }
        require(retreat in 0.0..1.0) { "a room retreats from 0 to 1: $retreat" }
        require(reverb in 0.0..1.0) { "how much room there is runs from 0 to 1: $reverb" }
        require(skewNanos in -MAX_SKEW_NANOS..MAX_SKEW_NANOS) {
            "a hand set head start runs either way to $MAX_SKEW_NANOS ns: $skewNanos"
        }
        // Same terms as otherHalfIds above: a name that is in neither the drawing nor an error
        // message is a handset somebody is holding a delay against and cannot see.
        require(skewPeerId == null || layout.contains(skewPeerId)) {
            "the handset the head start is about is not in the drawing: $skewPeerId"
        }
    }

    /**
     * How long [peerId] holds its output back, so that what it plays arrives with the rest.
     *
     * The other half of the fix [SpatialLayout.distanceGainOf] describes itself as half of. That
     * one corrects the level a handset is heard at and says outright that it leaves the time
     * alone; this is the time. Sound covers 34 cm in a millisecond, so two handsets a metre apart
     * in depth are heard 2.9 ms apart, and past a millisecond or so the earlier one takes the
     * image outright however the levels are set - the near handset becomes where the music is.
     *
     * Every handset waits for the furthest one, because that is the only direction this can go.
     * The far handset is already as early as it can be; nothing here can play in the past. So the
     * whole room gains the furthest handset's head start of buffer, which at five metres is under
     * fifteen milliseconds and sits inside the scheduler's three seconds without being noticed.
     *
     * Zero for every handset when [metresPerUnit] is zero, which is every room that has not been
     * measured with the listener in it, and every build of the far end that predates this.
     */
    fun arrivalDelayNanosFor(peerId: String): Long {
        require(layout.contains(peerId)) { "no handset named $peerId in this layout" }
        if (metresPerUnit <= 0.0) return 0L
        val behindMetres = (layout.furthestReach - layout.reachOf(peerId)) * metresPerUnit
        val nanos = behindMetres / AlignmentAnalysis.SPEED_OF_SOUND_M_S * 1_000_000_000.0
        return nanos.roundToLong().coerceIn(0L, MAX_ARRIVAL_DELAY_NANOS)
    }

    /**
     * Whether this rule asks any handset to hold its audio back by an amount that moves.
     *
     * Asked of the rule rather than of a moment, which is the whole point of it: the travel part
     * is zero at whichever handset the source is facing, so "is the delay zero just now" is true
     * at some instants and false at others for one unchanged rule. A renderer deciding whether to
     * carry a delay line on that would build and drop one several times a second.
     *
     * [skewNanos] is here despite not moving at all. What this question is really asked for is
     * whether the renderer needs somewhere to hold frames, and a constant delay needs that just as
     * much as a moving one does - the name is about the commonest reason, not the only one.
     */
    val movesInTime: Boolean get() =
        travelDelayNanos > 0L || shimmerDelayNanos > 0L || skewNanos != 0L

    /**
     * How long [peerId] holds this instant's audio back, on top of [arrivalDelayNanosFor].
     *
     * The two are separate because they are different kinds of number. That one is a constant for
     * as long as the room keeps its shape, so the renderer applies it to the clock and never
     * touches the audio. This one moves while the music plays, which means it has to be applied
     * inside the audio a frame at a time - see [TravellingDelay], which is the only thing that
     * reads this.
     *
     * A function of the instant, like [gainAt] and unlike [foldFor], so every handset works out
     * its own without anything being sent and the room stays in step by agreeing about the clock.
     *
     * Never negative, in either part. A delay line cannot read a frame that has not happened yet,
     * so the wander is a raised sine that runs from nothing to its depth rather than a sine about
     * a centre - which costs half the depth in average latency and buys never having to explain
     * why one handset is early.
     */
    fun playbackDelayNanosFor(peerId: String, hostNanos: Long): Long {
        require(layout.contains(peerId)) { "no handset named $peerId in this layout" }
        return travelPartNanos(peerId, hostNanos) + shimmerPartNanos(peerId, hostNanos) +
            skewPartNanos(peerId)
    }

    /**
     * The part that says where the source is: nothing at the handset it is facing, all of
     * [travelDelayNanos] at the one it has turned its back on.
     *
     * The same raised cosine [rawGain] places by, turned upside down, so the two agree about where
     * the source is by construction rather than by two expressions that have to be kept in step.
     *
     * Nothing in [SpatialMode.SPLIT]: there is no source to be facing away from, which is the same
     * reason [envelopment] is not read there.
     */
    private fun travelPartNanos(peerId: String, hostNanos: Long): Long {
        if (travelDelayNanos <= 0L || mode == SpatialMode.SPLIT) return 0L
        val facing = (1.0 + cos(sourceAzimuthAt(hostNanos) - layout.azimuthOf(peerId))) / 2.0
        return ((1.0 - facing) * travelDepthNanos()).roundToLong()
    }

    /**
     * How much of the placement this rule is doing with time rather than with loudness, 0..1.
     *
     * The knob was an addition when it was written on 09-15: a delay laid on top of a gain law
     * that went on placing the source by itself. As an addition it could not be heard, for a
     * reason that is arithmetic rather than taste. [travelPartNanos] is the raised cosine
     * [rawGain] places by, turned upside down - so the handset the source faces away from is the
     * quietest one *and* the latest one. The two cues never disagree, and a cue that can only
     * confirm what a louder cue has already said changes nothing when it is wound up. A listener
     * on 09-16 reported exactly that, at every depth up to thirty milliseconds.
     *
     * So it is a crossfade instead. Winding it up hands the placement over: the angular part of
     * the gain flattens towards every handset equally loud at the same rate the delay deepens,
     * and at the top the room is placing the source with nothing but who plays it first. That is
     * also the only setting at which the question this knob exists to ask - whether a few
     * milliseconds can put a sound somewhere - has an answer a person can hear, because it is the
     * only setting at which loudness is not answering it for them.
     *
     * Only the angular part flattens. How far a handset stands from the listener is not a
     * placement, it is what that handset has to play to be heard at all, so [rawGain] keeps its
     * distance correction at every setting of this.
     */
    private fun travelShare(): Double {
        if (travelDelayNanos <= 0L || mode == SpatialMode.SPLIT) return 0.0
        return (travelDelayNanos.toDouble() / MAX_TRAVEL_DELAY_NANOS).coerceIn(0.0, 1.0)
    }

    /**
     * The travel depth this rule's own speed leaves room for, which is all of it at any ordinary
     * setting and less than all of it for a circuit fast enough to outrun the delay line.
     *
     * Capped in the law rather than left to [TravellingDelay]'s limiter, which would also stop the
     * delay going too fast and would do it differently on every handset: the limiter clips a
     * trajectory, and two handsets are at different points of the same trajectory, so what they
     * render after being clipped is not the same shape scaled down - it is two different shapes.
     * A depth every handset computes from the rule is a shape they all still agree on.
     *
     * (D/2)(2 pi / T) is how fast the deepest point of the sweep moves, so D = slew * T / pi is the
     * deepest sweep a period of T can carry. At the default six second circuit that allows about
     * sixty milliseconds, which is twice what the knob can ask for - so this does nothing at all
     * unless somebody winds the circuit down to a couple of seconds, where it turns "the effect
     * quietly renders wrong" into "the effect is shallower, because you asked for it faster".
     *
     * [SPEED_MARGIN] is there because this returns whole nanoseconds. Rounding each instant to the
     * nearest nanosecond is worth a few hundredths of a frame per frame at 48 kHz, which put the
     * exact bound 0.15% over the limit - small enough to be worth nothing and large enough that
     * the assertion pinning it could not be written as the strict inequality it ought to be.
     *
     * Only the rotation has a speed. A pan moves when a finger moves, which is a discontinuity
     * rather than a rate, and discontinuities are exactly what the limiter is for.
     */
    private fun travelDepthNanos(): Long {
        if (mode != SpatialMode.ROTATE) return travelDelayNanos
        val allowed =
            (TravellingDelay.MAX_SLEW_SAMPLES * periodNanos / PI * SPEED_MARGIN).toLong()
        return minOf(travelDelayNanos, allowed)
    }

    /**
     * The part that says nothing at all: each handset's own slow wander, spread so that no two of
     * them are ever at the same place in it.
     *
     * Spread by the handset's slot in the drawing rather than by a hash of its name. A hash gives
     * independent draws, and independent draws collide: two handsets landing on nearly the same
     * phase would wander together, which is the one outcome that makes this feature do nothing,
     * and it would happen for some rooms and not others with nothing on screen to say which.
     * Slots divide the cycle evenly, so N handsets are as far apart as N handsets can be, always.
     *
     * The rates differ too, in a few fixed steps, so a room does not settle into a fixed pattern
     * that has simply been rotated - handsets sharing a rate step still start a full slot apart
     * and stay there, which is a difference that does not decay. The steps are bounded rather than
     * growing with the room because the steepest rate is what has to stay under the slew limit,
     * and a room of twenty handsets must not be the room that quietly exceeds it.
     */
    private fun shimmerPartNanos(peerId: String, hostNanos: Long): Long {
        if (shimmerDelayNanos <= 0L) return 0L
        val slot = layout.peerIds.indexOf(peerId)
        val rate = 1.0 + (slot % SHIMMER_RATE_SLOTS) * SHIMMER_RATE_SPREAD
        val turns = (hostNanos - epochHostNanos).toDouble() / shimmerPeriodNanos
        val phase = 2.0 * PI * (turns * rate + slot.toDouble() / layout.peerIds.size)
        return ((1.0 + sin(phase)) / 2.0 * shimmerDelayNanos).roundToLong()
    }

    /**
     * The part somebody set by hand, as a wait rather than as the head start it is written as.
     *
     * Whichever end of the gap is not the early one does the waiting. A positive [skewNanos] is
     * that handset waiting and everybody else going first; a negative one is everybody else
     * waiting. Both are the same gap, and neither asks anything to play in the past.
     *
     * Not a function of the instant, unlike the two above it, and that is the point of it: it is
     * the same number in a minute's time. See [skewNanos].
     */
    private fun skewPartNanos(peerId: String): Long {
        val named = skewPeerId ?: return 0L
        return if (peerId == named) maxOf(0L, skewNanos) else maxOf(0L, -skewNanos)
    }

    /** Where the source is at [hostNanos], as an azimuth. Meaningless for [SpatialMode.SPLIT]. */
    fun sourceAzimuthAt(hostNanos: Long): Double = when (mode) {
        SpatialMode.ROTATE -> {
            // floorMod rather than %, so the angle a reader sees while debugging runs forwards
            // from zero for an instant before the epoch. It cannot change a gain: the two differ
            // by exactly one period, which is exactly one turn.
            val elapsed = Math.floorMod(hostNanos - epochHostNanos, periodNanos)
            2.0 * PI * elapsed / periodNanos
        }
        // The slider covers the frontal half circle only. Behind the listener is reachable by
        // rotation but not by dragging: a control whose two ends meet has no ends.
        SpatialMode.PAN -> pan * PI / 2.0
        SpatialMode.SPLIT -> 0.0
    }

    /**
     * How much of the other channel [peerId] folds into each of its own, before any placement gain.
     *
     * A mix is two channels because someone placed each instrument by how loudly it appears in each.
     * Anything they placed in the middle appears in both identically, so adding the channels keeps it
     * and subtracting them cancels it exactly; anything they placed to a side survives the subtraction
     * and is thinned by the addition. That is the whole mechanism, and it is one addition per sample.
     *
     * Both halves are the same shape with one sign changed, so a single signed number says which part
     * a handset carries and how much of it: the handset keeps 1 - abs(fold) of its own channel and
     * folds [fold] of the other one in. At +1/2 that is exactly the middle on both channels, at -1/2
     * exactly the sides, and at 0 the mix untouched. Nothing else in this class has to know.
     *
     * Two things this cannot do, both of which have to reach the listener as words rather than as a
     * surprise. It divides by **where a sound was placed**, never by what instrument it is - the kick
     * and the bass sit in the middle with the voice and leave with it. And material the two channels
     * agree on has no sides at all, so a handset given the sides of a mono recording is silent.
     *
     * The two parts are also not equally loud - in most music the middle carries far more energy than
     * the sides - and nothing here corrects for that. Whether it should is a question about what a
     * room sounds like, so it waits for a listener rather than for an argument.
     */
    fun foldFor(peerId: String): Double {
        require(layout.contains(peerId)) { "no handset named $peerId in this layout" }
        if (splitAxis != SplitAxis.MIDDLE_SIDES) return 0.0
        return if (peerId in otherHalfIds) -separation / 2.0 else separation / 2.0
    }

    /**
     * How much of the mix and of its low half [peerId] plays, before any placement gain.
     *
     * The other axis, and a different kind of division from the fold. The middle and the sides are
     * arithmetic on the sample in hand; low and high are what a sound has been doing for the last
     * few milliseconds, so this half of the feature needs a filter that remembers - see [Crossover],
     * which is where the remembering lives. Nothing here holds any state.
     *
     * Written as a crossfade from the whole mix toward this handset own half, so the knob lands in
     * the same place on both axes: at zero every handset plays what it was sent, at one it plays
     * only its half, and in between it is the two mixed in that proportion. The high half is the mix
     * with the low half taken out of it rather than a filter of its own, which is what makes the two
     * halves add back up to exactly what was sent no matter what the filter does to the low one.
     *
     * The same two warnings as the fold apply in their own shape. This divides by **frequency**, not
     * by instrument: a voice and a guitar both live on both sides of any crossover and will be heard
     * from both handsets. And what a handset speaker can actually produce is not the same as what
     * this hands it - the low half of a mix on a speaker that cannot go low is quieter than the
     * arithmetic says, which is a thing to measure rather than to argue about.
     */
    fun spectrumFor(peerId: String): SpectrumMix {
        require(layout.contains(peerId)) { "no handset named $peerId in this layout" }
        if (splitAxis != SplitAxis.LOW_HIGH) return SpectrumMix(1.0, 0.0)
        // The low half lets go of the mix as it takes the filter on; the high half keeps the mix
        // and subtracts. Summed over the pair that is one mix and no filter left over.
        val trim = highTrim()
        return if (peerId in otherHalfIds) SpectrumMix(trim, -separation * trim)
        else SpectrumMix(1.0 - separation, separation)
    }

    /**
     * How far down the high half plays, so that dragging the split low tilts the room toward it.
     *
     * Dragging the split down takes the low handset's content away twice over: the band it keeps
     * narrows, and what is left of it sits further below the frequency its own speaker stops
     * being able to make a sound at. On 2026-09-10, with the skirt newly steepened, a listener
     * dragged the split to 100 Hz and the low handset went to very nearly nothing. That is the
     * split working and the feature vanishing at the same time: somebody who drags it down there
     * is asking to hear the low part, not to switch a handset off.
     *
     * Six decibels an octave below the default, which is exactly "halve the split, double the
     * difference" - a ratio of frequencies, no logarithm needed. Neutral at and above the default,
     * so every test that asserts the two halves add back up goes on holding where it is written.
     *
     * A trim rather than a lift on the low half, which is what this was first built as. That
     * crackled within minutes of reaching a listener: at a split of 500 Hz the lift was 1.6x,
     * four decibels, and a modern master has nowhere to put four decibels. No cap would have
     * saved it, because the smallest lift the slider can ask for already clipped - and the gains
     * here multiply, so distance compensation and the room power normalisation were on top of it.
     * Trimming cannot do that to anybody: every sample it produces is smaller than the one this
     * same arrangement produced before it existed, and that arrangement had been listened to.
     * What it costs is a room that gets quieter as the split goes down, which a volume key fixes
     * and clipping does not.
     *
     * Riding on [separation] rather than on the crossover alone: with the knob at nothing there
     * is no split, and a handset sitting twelve decibels down because of a slider that is doing
     * nothing would have no control on screen saying so.
     *
     * Still capped, now for taste rather than for damage: past a quarter the room is being turned
     * down rather than tilted, and the far side of the split is a band the speaker cannot carry.
     */
    private fun highTrim(): Double {
        val tilt = (DEFAULT_CROSSOVER_HZ / crossoverHz).coerceIn(1.0, MAX_LOW_TILT)
        return 1.0 / (1.0 + (tilt - 1.0) * separation)
    }

    /**
     * What [peerId] plays its two channels at, at [hostNanos].
     *
     * Normalised so the whole room emits the same power whatever the rule is doing: without it a
     * source crossing the gap between two handsets would sound like the volume dipping rather than
     * like the source moving, and switching modes would change how loud the music is.
     */
    fun gainAt(peerId: String, hostNanos: Long): StereoGain = gainAt(peerId, hostNanos, true)

    /**
     * What [peerId] plays its share of the **room** at, at [hostNanos] - the reverberation, not the
     * source. The same placement as [gainAt] with the distance taken out of it.
     *
     * This is the whole of how a reverberation reads as distance, and it is one line. A source that
     * moves away loses its direct sound and keeps its reverberation: the walls are where they were,
     * they are being hit by the same music, and what comes off them does not care that the source
     * is further from the listener than it was. So the wet skips the two gains that mean distance -
     * [retreatGain], which is the source moving away, and [recedeGain], which is a handset being
     * made consistent with a head start that says it is standing further off - and keeps everything
     * else, so a handset the rule has turned down is turned down in its reverberation too.
     *
     * Scale the wet by the same gain as the dry and the ratio between them never changes, which is
     * exactly the failure the plain [retreat] has: the reverberation comes down with the source and
     * the whole thing reads as somebody turning the volume knob. This is the 09-16 note about
     * software gain multiplying the reverberation away, written as arithmetic instead of as a
     * fitted exponent.
     */
    fun roomGainAt(peerId: String, hostNanos: Long): StereoGain = gainAt(peerId, hostNanos, false)

    private fun gainAt(peerId: String, hostNanos: Long, distance: Boolean): StereoGain {
        require(layout.contains(peerId)) { "no handset named $peerId in this layout" }
        val away = if (distance) retreatGain() * recedeGain(peerId) else 1.0
        val raw = layout.peerIds.associateWith { rawGain(it, hostNanos) }
        val power = raw.values.sumOf { (it.left * it.left + it.right * it.right) / 2.0 }
        // A source diametrically opposite every handset in the room is a direction this layout
        // cannot render. Sharing the sound out evenly is a wrong position; silence is a dropout,
        // which is worse and which a listener would blame on the network.
        if (power <= 0.0) {
            // Evenly at the listener rather than evenly at the handsets, which for a room whose
            // handsets are all the same distance off is the same arithmetic this used to do.
            val reaches = layout.peerIds.associateWith { layout.distanceGainOf(it) }
            val evenScale = sqrt(reaches.values.sumOf { it * it })
            val even = reaches.getValue(peerId) / evenScale * away
            return StereoGain(even, even)
        }
        // Both of these are outside the normalisation on purpose and for the same reason, which is
        // written out at [recedeGain]: what is divided by the room's own power cannot make the room
        // quieter.
        val scale = 1.0 / sqrt(power) * away
        val mine = raw.getValue(peerId)
        return StereoGain(mine.left * scale, mine.right * scale)
    }

    /**
     * What [retreat] multiplies the finished room by: one at zero, and down from there.
     *
     * Counted in decibels rather than in the fraction itself, because loudness is what a listener
     * is being asked about and a linear fraction spends most of its travel in the part of the
     * range where a halving is barely a step. Even in decibels is even to an ear.
     *
     * Only ever less than one. This project has been bitten by gains that multiply - each with a
     * bound of its own, the product with none - so the room moving away is written as the only
     * arithmetic that cannot overflow anything downstream of it.
     */
    private fun retreatGain(): Double =
        if (retreat <= 0.0) 1.0 else 10.0.pow(-RETREAT_DECIBELS * retreat / 20.0)

    /**
     * What [skewRecedes] multiplies a waiting handset by: the plain inverse distance law.
     *
     * A handset standing r away and held back by t is heard from r + ct, and sound pressure falls
     * as one over distance, so it arrives at r / (r + ct) of the level it had - raised to
     * [RECEDE_ROLLOFF], because a room is not open air. The exponent is the one fitted number here;
     * everything else the delay already implied.
     *
     * **Why the exponent is needed at all, and it is not a fudge.** One over r is the direct sound.
     * A real source that moves away loses its direct sound at that rate and keeps very nearly all
     * of the reverberation, so the level a listener in a room actually loses is well under six
     * decibels a doubling - three to four is the usual figure. What a gain here multiplies is the
     * handset's direct sound **and its reverberation together**, because both of them are already in
     * what the handset emits. Turning it down removes the floor that a real room would have left
     * standing, and the move sounds bigger than the same move made by somebody walking. On 09-16 a
     * listener said exactly that: ten milliseconds read as more than a person stepping three and a
     * half metres back. This is that report, priced in - and priced out again by [rolloff] once the
     * room has a reverberation of its own, because then the floor is really there and the gain does
     * not have to pretend it is.
     *
     * **Only the handset that waits.** [skewPartNanos] is the wait rather than the head start for
     * exactly this reason: whichever end of the gap is not the early one is the one that has been
     * moved back, and the early one is standing where it always was.
     *
     * Read at the call site in [gainAt], on the far side of the power normalisation, and that
     * placement is the whole of whether this is audible: normalising divides by the room's own
     * power, so an attenuation applied before it is the numerator and the denominator at once and
     * cancels exactly. Applied after, the room genuinely loses what the far copy stopped
     * contributing - which tops out at three decibels, one of two handsets being the most there is
     * to lose.
     *
     * No absorption term, although a real reflection loses a few decibels at the wall as well. It
     * would have to be a step at the first millisecond - there is no wall at a gap of zero - and
     * the first millisecond either side of zero is the one place a listener reported reading
     * precisely, so a step is put exactly where it would do the most damage. It can be added as a
     * fitted constant once the plain law has been heard.
     */
    private fun recedeGain(peerId: String): Double {
        if (!skewRecedes) return 1.0
        val waitNanos = skewPartNanos(peerId)
        if (waitNanos <= 0L) return 1.0
        val standing = metresAway(peerId)
        val further = waitNanos / 1_000_000_000.0 * AlignmentAnalysis.SPEED_OF_SOUND_M_S
        return (standing / (standing + further)).pow(rolloff())
    }

    /**
     * How much of the open-air distance law this room gets, which is as much of it as the room's
     * own reverberation can pay for.
     *
     * [RECEDE_ROLLOFF] is a floor with a reason: it exists because a real room leaves a
     * reverberant floor standing that a plain gain would take away with everything else. When
     * [reverb] builds that floor for real, the gain no longer has to fake it, and the law
     * underneath can be the plain one - so the exponent walks from the fitted number to one as the
     * room is turned up.
     *
     * Deliberately not a flat one. Flipping it would have been right only in a room whose
     * reverberation is switched on, and this one ships with it at zero: a build that applied the
     * free-field law with nothing filling it back in would put back exactly the complaint the
     * fitted number was written for on 09-16, where ten milliseconds read as more than a person
     * stepping back three and a half metres. The correction has to arrive with the thing that
     * justifies it, and it does, sample for sample: at a reverberation of nothing this returns
     * [RECEDE_ROLLOFF] and every gain in the room is the one it was.
     */
    private fun rolloff(): Double = RECEDE_ROLLOFF + (1.0 - RECEDE_ROLLOFF) * reverb.coerceIn(0.0, 1.0)

    /**
     * How far [peerId] is from the listener in metres, guessed if the room has never been measured.
     *
     * [recedeGain] is the one rule here that cannot work in the drawing's own units: every other
     * gain is a ratio of two radii and so never needs to know how large the room is, but a ratio
     * against a distance in metres does. A room with no scale would therefore silently do nothing,
     * which is the worst of the three possible behaviours - a control that moves and is inaudible
     * is indistinguishable from one that is broken.
     *
     * So an unmeasured room is given a size instead of being given up on, and the size is stated
     * here rather than buried: [ASSUMED_FURTHEST_METRES] to the furthest handset. Measure the room
     * and the real number takes over, at which point the same gap does less in a large room than in
     * a small one - which is true, and worth hearing.
     */
    private fun metresAway(peerId: String): Double {
        val perUnit =
            if (metresPerUnit > 0.0) metresPerUnit
            else ASSUMED_FURTHEST_METRES / layout.furthestReach
        return layout.reachOf(peerId) * perUnit
    }

    /**
     * What the rule asks of [peerId] before the room is normalised, distance included.
     *
     * The distance correction multiplies whatever the mode decided rather than being a mode of its
     * own, because it answers a different question: the mode says how loud this handset should be
     * heard, and this says what it has to play to be heard that loudly from where it is standing.
     * Every mode wants it, so it sits outside the branch.
     */
    private fun rawGain(peerId: String, hostNanos: Long): StereoGain {
        val azimuth = layout.azimuthOf(peerId)
        val reach = layout.distanceGainOf(peerId)
        val placed = when (mode) {
            SpatialMode.ROTATE, SpatialMode.PAN -> {
                // Raised cosine of the angular gap: full facing the source, nothing facing away.
                // For the two-handset case this is exactly constant-power panning; for more it is
                // softer than picking the two handsets that bracket the source and panning between
                // them, which is the sharper rule to reach for if the image turns out mushy. Chosen
                // first because it is defined for every direction, including ones no pair brackets.
                //
                // Lifted onto [envelopment] rather than reaching zero, so that "facing away" is a
                // handset that is quiet rather than one that is off. At zero this is exactly the
                // expression it has always been.
                val facing = (1.0 + cos(sourceAzimuthAt(hostNanos) - azimuth)) / 2.0
                val angular = envelopment + (1.0 - envelopment) * facing
                // Flattened towards every handset equally loud by however much of the placement
                // the travel knob has taken over - see [travelShare]. At zero this line is the
                // identity and the expression above it is what it has always been.
                val weight = angular + (1.0 - angular) * travelShare()
                StereoGain(weight, weight)
            }
            // How far to the side a handset stands is how much of that side it carries. A room
            // where every handset is straight ahead splits weakly, which is the truth about that
            // room rather than a defect: a stereo image cannot be wider than the handsets are.
            SpatialMode.SPLIT -> {
                val sideways = sin(azimuth)
                StereoGain(sqrt((1.0 - sideways) / 2.0), sqrt((1.0 + sideways) / 2.0))
            }
        }
        return StereoGain(placed.left * reach, placed.right * reach)
    }

    companion object {
        /** Slow enough to hear as travel rather than as tremolo, fast enough to notice. */
        const val DEFAULT_PERIOD_NANOS = 6_000_000_000L

        /**
         * How far [travelDelayNanos] may be wound: forty milliseconds.
         *
         * The whole precedence window and then past the end of it. Deliberately past: the question
         * this knob exists to answer is where along that range the image stops travelling between
         * handsets and starts jumping between them, and then where it stops being one sound at
         * all, and a slider stopping short of either could not answer them. Forty is far enough
         * into echo territory that a listener can hear the failure and bound the useful range from
         * above, rather than being told where it is in a comment like this one.
         *
         * Fifteen at first; thirty on 09-15 when a listener could not hear single milliseconds at
         * all; forty on 09-16 when they could not hear thirty either. That second miss turned out
         * not to be about depth at all - see [travelShare] - but the ceiling went up alongside the
         * fix, because the setting at which the cue is finally audible on its own is also the
         * first setting at which its range means anything.
         */
        const val MAX_TRAVEL_DELAY_NANOS = 40_000_000L

        /**
         * How far [skewNanos] runs either way: seventy milliseconds.
         *
         * Wider than anywhere anybody expects to settle, and wide for that reason. Everything
         * above about where the useful range ends - one millisecond, thirty, the edge where a
         * delayed copy stops being the same sound - is a number read off other people's papers,
         * and the one thing this control is for is letting somebody find those edges in their own
         * room with their own music. A slider that stopped at the answer would be a slider that
         * assumed it.
         *
         * [TravellingDelay.LONGEST_NANOS] holds this plus both of the other two at once, and
         * SpatialDelayTest asserts the sum so the two files cannot drift apart.
         */
        const val MAX_SKEW_NANOS = 70_000_000L

        /**
         * How far down [retreat] takes the room at its far end: twelve decibels.
         *
         * Two doublings of distance, which is as far as a source can go before it stops being the
         * thing the room is playing and becomes something audible in the next street. Far enough
         * that the end of the slider is unmistakably a different place, near enough that every
         * setting along the way is still music somebody would listen to.
         */
        const val RETREAT_DECIBELS = 12.0

        /**
         * How far off the furthest handset is taken to be when nobody has measured: two and a half
         * metres.
         *
         * Only [recedeGain] reads it, and only in a room with no scale. A living room with the
         * handsets across it and somebody sitting between them is the arrangement every listening
         * test here has used, and at that size a ten millisecond gap comes out around ten decibels
         * down - which matches what a listener reported hearing on 09-16 as the gap where the sound
         * stuck to one handset.
         *
         * A guess, and labelled one. It is better than the alternative: the ratio this feeds needs
         * a length, and a room with no length would leave the control moving and silent.
         */
        const val ASSUMED_FURTHEST_METRES = 2.5

        /**
         * How much of the open-air distance law a room with no reverberation of its own gets:
         * three fifths of it.
         *
         * Free field is one, and gives six decibels a doubling. Real rooms give three to four,
         * because past a metre or two the reflections carry most of what is heard and the level
         * stops falling the way the direct sound does. Three fifths puts a doubling at about 3.6
         * decibels, which is the middle of what rooms measure.
         *
         * **A floor rather than the law**, since [RoomReverb] was built. This number was a single
         * exponent standing in for a reverberation model - the comment here used to say so and
         * call it a deliberate stop - and a stand-in is only needed while the thing it stands in
         * for is missing. [rolloff] walks it to one as the reverberation is turned up, so the room
         * is never told twice that its reflections are holding the level up. What is left at this
         * value is the case it was fitted for: a room playing dry, where the reflections are in the
         * listener's actual living room and not in the signal.
         *
         * Fitted by ear, and the only number here that is. If a receding handset playing dry still
         * reads as bigger than somebody walking the same distance, this is the number to lower.
         */
        const val RECEDE_ROLLOFF = 0.6

        /**
         * How far [shimmerDelayNanos] may be wound: twenty milliseconds.
         *
         * Six at first, which was chosen as a chorus depth and turned out to be a depth a listener
         * could barely hear on 09-15. Twenty is past chorus and into what a studio would call
         * doubling, which is the point: the handsets are metres apart rather than centimetres, so
         * the delays that do anything here are not the delays that do something between two
         * speakers on a desk.
         *
         * **Depth is not free, and what it costs is pitch.** A wander of depth D and period T moves
         * at up to (D/2)(2 pi / T) times the fastest rate slot, and a moving delay is a resampling,
         * so that number **is** the transposition. Two things follow. It has to stay under
         * [TravellingDelay.MAX_SLEW_SAMPLES] or the limiter clips the shape of the feature itself
         * and two handsets asked for the same wander render different ones - SpatialDelayTest is
         * where that sum is written down as an assertion rather than as this paragraph. And well
         * before that bound it is simply audible: deep and fast together is a tape wobble. The
         * period is therefore on screen beside the depth rather than fixed behind it, because the
         * two are one control in two halves and a listener who finds the wobble has to be able to
         * trade it back.
         */
        const val MAX_SHIMMER_DELAY_NANOS = 20_000_000L

        /** How long one wander takes by default: eight seconds, which is a drift rather than a wobble. */
        const val DEFAULT_SHIMMER_PERIOD_NANOS = 8_000_000_000L

        /** The fastest wander allowed, for the reason in [MAX_SHIMMER_DELAY_NANOS]. */
        const val SHORTEST_SHIMMER_PERIOD_NANOS = 5_000_000_000L

        /** The slowest, which is a room that breathes about three times a minute. */
        const val LONGEST_SHIMMER_PERIOD_NANOS = 20_000_000_000L

        /** How much faster each rate step is than the one below it. */
        const val SHIMMER_RATE_SPREAD = 0.11

        /** How many rate steps there are before they repeat. See [shimmerPartNanos]. */
        const val SHIMMER_RATE_SLOTS = 4

        /** What [travelDepthNanos] leaves for the rounding to whole nanoseconds: one percent. */
        private const val SPEED_MARGIN = 0.99

        /**
         * Where the split starts out, chosen for handset speakers rather than for music theory.
         *
         * A crossover down where a subwoofer would sit hands one handset a part its speaker cannot
         * make a sound with, so the room separates on paper and plays as one handset and one silent
         * one. This sits above that, where both sides of the split are things a phone can emit.
         *
         * How far above is a guess until somebody measures a handset: the roll-off is somewhere
         * around a few hundred hertz by reputation, and reputation is not a measurement.
         */
        const val DEFAULT_CROSSOVER_HZ = 800.0

        /** Wide enough to be worth dragging, narrow enough that both ends are still a split. */
        const val LOWEST_CROSSOVER_HZ = 100.0
        const val HIGHEST_CROSSOVER_HZ = 5_000.0

        /** Twelve decibels. See [highTrim]: past this the room is being turned down, not tilted. */
        const val MAX_LOW_TILT = 4.0

        /**
         * How far [envelopment] may be wound up: half, which is 6 dB between facing and facing away.
         *
         * Not one. At one every handset plays everything at the same level whatever the source is
         * doing, and the two modes that read it are modes for moving a source about - a control
         * whose far end switches the feature off is a control with a trap at the end of it.
         */
        const val MAX_ENVELOPMENT = 0.5

        /**
         * The longest wait [arrivalDelayNanosFor] will ask any handset for: fifty milliseconds.
         *
         * Seventeen metres of depth, which is not a room anybody carries phones into. It is not a
         * tuning knob but a guard on one input: the scale comes from a fit, a fit can be wrong, and
         * a scale wrong by a factor of a hundred asks a handset to go quiet for a second and a half
         * while everything on screen still reads healthy. Clipped rather than refused, because the
         * room the clip lands on is the furthest handset's, which is where an uncorrected room
         * already was.
         */
        const val MAX_ARRIVAL_DELAY_NANOS = 50_000_000L
    }
}
