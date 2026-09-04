package com.soundmesh.core

enum class AlignmentConfidence { OK, UNRELIABLE }

/**
 * One handset's reading of one chirp pair: where both chirps landed in its own recording, and the
 * alignment error that follows.
 *
 * A reading is one side of a measurement, not the measurement. Its own microphone is a few
 * centimetres from one speaker and a room away from the other, so it carries a flight time term
 * that [AlignmentAnalysis.combineFacing] removes and [propagationCorrectionMs] only estimates.
 */
data class AlignmentReading(
    val firstIndex: Int?,
    val secondIndex: Int?,
    val measuredStaggerFrames: Int?,
    val alignmentErrorMs: Double?,
    val propagationCorrectionMs: Double,
    val separationMetres: Double,
    val confidence: AlignmentConfidence,
    val ratios: List<Double?>,
    val atSearchEdge: List<Boolean?>
)

/**
 * The alignment error between the handsets with the room's flight time removed by construction,
 * and the separation that falls out of the same algebra.
 */
data class FacingPair(
    val alignmentErrorMs: Double,
    val separationMetres: Double,
    val flightTimeMs: Double,
    val rawHostMs: Double,
    val rawSinkMs: Double
)

/**
 * Turns recordings of a deliberately staggered chirp pair into the alignment error between two
 * handsets.
 *
 * The stagger is known, so whatever is left over is the error being measured. This is the port of
 * the PC analysis in tools/src/calibration-analysis.mjs, and it is a port rather than a rewrite on
 * purpose: every alignment number since the first run was produced by that code, and a device that
 * answered a slightly different question would quietly end the comparability of all of them.
 */
object AlignmentAnalysis {
    /** Metres per second. Room temperature air; a degree either way is far below the 5 ms gate. */
    const val SPEED_OF_SOUND_M_S = 343.0

    /**
     * Reads one chirp pair out of [recorded].
     *
     * [separationMetres] is required rather than optional, and 0.0 is the value to pass when the
     * reading is destined for [combineFacing]. The recorder is also a chirp source: its own chirp
     * crosses a few centimetres while its partner's crosses the room, so a raw reading always
     * carries about -2.9 ms per metre. Against a 5 ms gate that hides real failures - a genuine
     * +3 ms error at one metre reads as +0.1 ms, a clean pass - so there is no safe default to
     * assume it away with.
     *
     * [searchFrom] and [searchTo] bound where the louder of the pair is looked for. The partner is
     * then searched for either side of it, so a pair straddling the window's edge still reads
     * correctly.
     */
    fun read(
        recorded: ShortArray,
        reference: ShortArray,
        staggerFrames: Int,
        searchRadiusFrames: Int,
        separationMetres: Double,
        searchFrom: Int = 0,
        searchTo: Int = Int.MAX_VALUE,
        sampleRate: Int = ChirpGenerator.SAMPLE_RATE
    ): AlignmentReading {
        require(separationMetres.isFinite() && separationMetres >= 0) {
            "separationMetres is required: the distance between the two handsets, in metres"
        }
        // Once the radius reaches the stagger each chirp sits inside the other's window, and the
        // two can be told apart only by which correlates louder - silently swapping first and
        // second, and negating the reported error.
        require(searchRadiusFrames < staggerFrames) {
            "searchRadiusFrames must be smaller than staggerFrames, or the two chirps can be mistaken for each other"
        }
        val best = ChirpCorrelator.findArrival(recorded, reference, searchFrom, searchTo)
        val window = { centre: Int ->
            ChirpCorrelator.findArrival(recorded, reference, centre - searchRadiusFrames, centre + searchRadiusFrames)
        }
        val after = best?.let { window(it.index + staggerFrames) }
        val before = best?.let { window(it.index - staggerFrames) }
        val partnerIsAfter = (after?.peak ?: -1.0) >= (before?.peak ?: -1.0)
        val partner = if (partnerIsAfter) after else before
        val first = if (partnerIsAfter) best else partner
        val second = if (partnerIsAfter) partner else best
        val trustworthy = first != null && second != null &&
            first.ratio >= ChirpCorrelator.MIN_TRUSTWORTHY_RATIO &&
            second.ratio >= ChirpCorrelator.MIN_TRUSTWORTHY_RATIO &&
            !first.atSearchEdge && !second.atSearchEdge
        val measuredStaggerFrames = if (trustworthy) second!!.index - first!!.index else null
        // Added back, not subtracted: the partner's chirp arrives late through the air, which drags
        // the raw difference down, so a wider separation must push the error further positive.
        val propagationCorrectionMs = separationMetres / SPEED_OF_SOUND_M_S * 1000
        return AlignmentReading(
            firstIndex = first?.index,
            secondIndex = second?.index,
            measuredStaggerFrames = measuredStaggerFrames,
            alignmentErrorMs = measuredStaggerFrames?.let {
                (it - staggerFrames).toDouble() / sampleRate * 1000 + propagationCorrectionMs
            },
            propagationCorrectionMs = propagationCorrectionMs,
            separationMetres = separationMetres,
            confidence = if (trustworthy) AlignmentConfidence.OK else AlignmentConfidence.UNRELIABLE,
            ratios = listOf(first?.ratio, second?.ratio),
            atSearchEdge = listOf(first?.atSearchEdge, second?.atSearchEdge)
        )
    }

    /**
     * Combines the two recordings of one chirp pair - one made by each handset - into the alignment
     * error between them, with the room's flight time removed by construction rather than measured
     * with a tape.
     *
     * Both handsets hear the same two chirps, but the air enters the two readings with opposite
     * signs. Writing E for the alignment error and D/c for the flight time, the host hears its own
     * chirp across a few centimetres and the sink's across the room, so its reading is E - D/c;
     * the sink hears the mirror image, E + D/c. The half sum is E with no D in it at all, and the
     * half difference is D/c, so the distance comes out as a measurement instead of an assumption.
     * An error in the temperature the speed of sound is taken at shifts the reported separation and
     * leaves E untouched.
     *
     * Two further properties fall out of the same algebra. Each side's input latency cancels inside
     * its own recording, so the two need not share one and neither has to be known. And a jitter in
     * when a chirp actually leaves a speaker enters both readings with the same sign while a jitter
     * on the capture side enters only one, so the half sum carries the emission jitter and the half
     * difference cannot - which separates the two without inferring either.
     *
     * Returns null unless both sides were trustworthy: half a pair says nothing on its own.
     */
    fun combineFacing(hostSide: AlignmentReading?, sinkSide: AlignmentReading?): FacingPair? {
        val hostError = hostSide?.alignmentErrorMs ?: return null
        val sinkError = sinkSide?.alignmentErrorMs ?: return null
        val rawHostMs = hostError - hostSide.propagationCorrectionMs
        val rawSinkMs = sinkError - sinkSide.propagationCorrectionMs
        val flightTimeMs = (rawSinkMs - rawHostMs) / 2
        return FacingPair(
            alignmentErrorMs = (rawHostMs + rawSinkMs) / 2,
            separationMetres = flightTimeMs / 1000 * SPEED_OF_SOUND_M_S,
            flightTimeMs = flightTimeMs,
            rawHostMs = rawHostMs,
            rawSinkMs = rawSinkMs
        )
    }
}
