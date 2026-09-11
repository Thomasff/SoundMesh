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
    val atSearchEdge: List<Boolean?>,
    /**
     * What this reading would have said at each threshold it was asked to try, in milliseconds
     * before the distance correction, in the order the shares were asked for.
     *
     * Empty unless shares were asked for, and empty when the reading is not trustworthy: a run
     * that could not find its chirps has nothing to say about what its answer rests on. It is
     * read by [FacingPair.separationSpreadMetres], which is where the two sides meet.
     */
    val rawMsByShare: List<Double> = emptyList()
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
    val rawSinkMs: Double,
    /**
     * How far this separation moves when the leading-edge threshold moves, or null when the two
     * sides did not sweep the same shares.
     *
     * The threshold is the one fitted number in the distance measurement, and 09-11 measured it
     * fitted to more than the room: with a clear line of sight the best share is 20%, and with a
     * body between the handsets it slides to 5-10%. Repeatability cannot catch that - the blocked
     * runs were *more* self-consistent than the clear ones and still wrong by 1.3 m. This can,
     * because a clean onset puts every share on the same lag while an absent one puts each share
     * on a different reflection. Measured over eighteen pairs: 0.05-0.52 m with a clear line of
     * sight, 1.06-2.59 m with it blocked, and nothing in between.
     */
    val separationSpreadMetres: Double? = null
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
     * The thresholds a distance is read at: the first is the pick, the rest only say how much the
     * pick rests on it.
     *
     * 20% is fitted, and 09-11 measured what it is fitted to. Its lower bound is not: a 120 ms
     * chirp sweeping 7 kHz has a time-bandwidth product near 840 and sidelobes below -30 dB, so
     * anything above about 3% cannot be a sidelobe. The upper bound is the fitted half - it
     * depends on how far the reflections sit below the direct sound, which is a property of the
     * room and of whether anything stands between the handsets. With a clear line of sight 20%
     * was the best of the sweep; with a body in the way the best slid to 5-10%.
     *
     * Which is why the rest of the sweep ships too. It is not there to find a better pick - it is
     * there so a run can report how far its own answer moves across the whole range, which is the
     * only thing measured so far that tells the two cases apart. See
     * [FacingPair.separationSpreadMetres].
     */
    val DISTANCE_EDGE_SHARES = listOf(0.20, 0.30, 0.15, 0.10, 0.05)

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
        sampleRate: Int = ChirpGenerator.SAMPLE_RATE,
        edgeShares: List<Double> = emptyList()
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
            ChirpCorrelator.findArrival(
                recorded, reference, centre - searchRadiusFrames, centre + searchRadiusFrames, edgeShares
            )
        }
        // The window the winner was found in holds both chirps, so its own first arrival belongs
        // to whichever came first rather than to the chirp it found. Reading the winner again in
        // a window of its own is what keeps the later chirp from being handed the earlier edge.
        // Only when edges were asked for: the extra pass buys nothing otherwise, and a window
        // centred on the winner cannot find a different winner.
        val here = best?.let { if (edgeShares.isEmpty()) it else window(it.index) }
        val after = best?.let { window(it.index + staggerFrames) }
        val before = best?.let { window(it.index - staggerFrames) }
        val partnerIsAfter = (after?.peak ?: -1.0) >= (before?.peak ?: -1.0)
        val partner = if (partnerIsAfter) after else before
        val first = if (partnerIsAfter) here else partner
        val second = if (partnerIsAfter) partner else here
        val trustworthy = first != null && second != null &&
            first.ratio >= ChirpCorrelator.MIN_TRUSTWORTHY_RATIO &&
            second.ratio >= ChirpCorrelator.MIN_TRUSTWORTHY_RATIO &&
            !first.atSearchEdge && !second.atSearchEdge
        // Which lag each chirp is read at: the loudest, or the first that counts as an arrival.
        // Every reflection travels further than the straight line it bounced off, so the direct
        // sound is the earliest arrival by construction - but nothing makes it the loudest, and
        // 09-11 measured the loudest landing 12-21 ms late with a clear line of sight.
        val at = { arrival: ChirpArrival, share: Int ->
            if (edgeShares.isEmpty()) arrival.index else arrival.edgeIndices[share]
        }
        val measuredStaggerFrames = if (trustworthy) at(second!!, 0) - at(first!!, 0) else null
        // Added back, not subtracted: the partner's chirp arrives late through the air, which drags
        // the raw difference down, so a wider separation must push the error further positive.
        val propagationCorrectionMs = separationMetres / SPEED_OF_SOUND_M_S * 1000
        return AlignmentReading(
            firstIndex = first?.let { at(it, 0) },
            secondIndex = second?.let { at(it, 0) },
            measuredStaggerFrames = measuredStaggerFrames,
            alignmentErrorMs = measuredStaggerFrames?.let {
                (it - staggerFrames).toDouble() / sampleRate * 1000 + propagationCorrectionMs
            },
            propagationCorrectionMs = propagationCorrectionMs,
            separationMetres = separationMetres,
            confidence = if (trustworthy) AlignmentConfidence.OK else AlignmentConfidence.UNRELIABLE,
            ratios = listOf(first?.ratio, second?.ratio),
            atSearchEdge = listOf(first?.atSearchEdge, second?.atSearchEdge),
            rawMsByShare = if (!trustworthy) emptyList() else edgeShares.indices.map {
                (at(second!!, it) - at(first!!, it) - staggerFrames).toDouble() / sampleRate * 1000
            }
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
            rawSinkMs = rawSinkMs,
            separationSpreadMetres = spreadOf(hostSide.rawMsByShare, sinkSide.rawMsByShare)
        )
    }

    /**
     * The separation computed once per share, widest minus narrowest.
     *
     * Null rather than zero when there is nothing to compare - a pair that swept no shares, or two
     * sides that swept different ones, has not answered the question, while zero is the answer
     * that means the run is at its most trustworthy.
     */
    private fun spreadOf(hostByShare: List<Double>, sinkByShare: List<Double>): Double? {
        if (hostByShare.isEmpty() || hostByShare.size != sinkByShare.size) return null
        val metres = hostByShare.indices.map {
            (sinkByShare[it] - hostByShare[it]) / 2 / 1000 * SPEED_OF_SOUND_M_S
        }
        return metres.max() - metres.min()
    }
}
