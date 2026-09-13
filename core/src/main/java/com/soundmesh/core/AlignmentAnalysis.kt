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
    val rawMsByShare: List<Double> = emptyList(),
    /**
     * The same stagger read at the loudest lag instead of the leading edge, in milliseconds and
     * before the distance correction.
     *
     * Null exactly when no shares were swept, because then [alignmentErrorMs] is already the
     * loudest reading and there is nothing to carry twice.
     *
     * It exists because the two answers a run gives want opposite rules. A distance must be read
     * at the earliest arrival - every reflection travels further than the straight line it
     * bounced off - while an alignment keeps the loudest, which is what the M2 gate was passed
     * with and what every archived correction was produced by. Before this, asking for the edge
     * moved both, so a run could answer one question or the other, and the product asked for the
     * alignment - which is why nothing it ran ever measured a distance.
     */
    val rawLoudestMs: Double? = null
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
    val separationSpreadMetres: Double? = null,
    /**
     * How far apart the two fired, read off the leading edge instead of the loudest lag.
     *
     * [alignmentErrorMs] is the same half sum taken at the loudest lag, which is the right
     * reading for what a listener hears and the wrong one for when a speaker actually started:
     * across a room the loudest arrival is a reflection. 09-11 measured the gap between the two
     * rules on the other half - nine readings of one unchanged two metre gap spanned 5.11 to
     * 11.86 m at the loudest and 1.97 to 2.19 at the edge - and 09-13 measured it here: six
     * pairs whose loudest-lag half sums missed closing their triangles by 1.35 to 6.45 ms
     * against a properly measured constant whose own spread is 0.14.
     *
     * Null when the two sides did not sweep the same shares, which is every arm but the room.
     */
    val firingOffsetMs: Double? = null
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
        return readingOf(first, second, staggerFrames, separationMetres, sampleRate, edgeShares)
    }

    /**
     * Where every handset's chirp landed in one recording, indexed by the slot it was given.
     *
     * [read] answers one pair per round, and two rounds are two snapshots with the clocks drifting
     * between them. One window holding a chirp from each handset answers every pair at once off the
     * same instant, and reaches the pairs between two sinks that a host-centred round never does.
     *
     * The chirps are spaced rather than overlaid because two chirps on top of each other cannot be
     * told apart until each handset has a signal of its own, so the window grows with the room.
     *
     * [ownSlot] is which chirp is this handset's, and it is told rather than worked out because it
     * is the anchor. This microphone is centimetres from its own speaker and metres from every
     * other, so the loudest arrival in the window is its own chirp by a wide margin, and every
     * other slot is then a known distance from it. Deciding instead that the loudest must be the
     * first chirp would put every handset but one a whole slot out, silently and plausibly.
     *
     * A slot nobody could hear comes back as an arrival that will not pass the trust gate rather
     * than as a gap, which is what stops one unheard handset from costing the pairs it is not in.
     */
    fun readSlots(
        recorded: ShortArray,
        reference: ShortArray,
        ownSlot: Int,
        slotCount: Int,
        slotFrames: Int,
        searchRadiusFrames: Int,
        searchFrom: Int = 0,
        searchTo: Int = Int.MAX_VALUE,
        edgeShares: List<Double> = emptyList()
    ): List<ChirpArrival?> {
        require(slotCount >= 2) { "a room of one has nothing to align against" }
        require(ownSlot in 0 until slotCount) { "ownSlot is not one of the slots: $ownSlot" }
        // The bound [read] states, one dimension wider: once the radius reaches the spacing, each
        // handset's chirp sits inside its neighbour's window and the two can be told apart only by
        // which correlates louder - which is a property of the room, not of the schedule.
        require(searchRadiusFrames < slotFrames) {
            "searchRadiusFrames must be smaller than slotFrames, or two handsets can be mistaken for each other"
        }
        val anchor = ChirpCorrelator.findArrival(recorded, reference, searchFrom, searchTo)
            ?: return List(slotCount) { null }
        val window = { centre: Int ->
            ChirpCorrelator.findArrival(
                recorded, reference, centre - searchRadiusFrames, centre + searchRadiusFrames, edgeShares
            )
        }
        return (0 until slotCount).map { slot ->
            // The anchor's own window may hold a neighbour too, so its first arrival can belong to
            // one - read it again in a window of its own. Only where edges exist to be confused.
            if (slot == ownSlot && edgeShares.isEmpty()) anchor
            else window(anchor.index + (slot - ownSlot) * slotFrames)
        }
    }

    /**
     * One pair's reading, taken out of a window that held the whole room.
     *
     * The same reading [read] returns, by the same code rather than merely the same formula. Every
     * alignment number this project has produced came from that path; a second implementation
     * answering a slightly different question would end the comparability of all of them while
     * every test still passed.
     */
    fun betweenSlots(
        slots: List<ChirpArrival?>,
        earlier: Int,
        later: Int,
        slotFrames: Int,
        separationMetres: Double,
        sampleRate: Int = ChirpGenerator.SAMPLE_RATE,
        edgeShares: List<Double> = emptyList()
    ): AlignmentReading {
        require(earlier < later) { "the earlier slot is the one that chirps first" }
        return readingOf(
            slots.getOrNull(earlier),
            slots.getOrNull(later),
            (later - earlier) * slotFrames,
            separationMetres,
            sampleRate,
            edgeShares
        )
    }

    /**
     * Two arrivals in time order and the gap expected between them, turned into a reading.
     *
     * Hoisted out of [read] rather than copied into [betweenSlots]: its two callers differ only in
     * how they decide which arrival is which, and everything after that decision is the
     * measurement itself. Written twice it would drift, and the drift would be invisible - two
     * paths producing numbers that are close rather than equal is what nobody checks.
     */
    private fun readingOf(
        first: ChirpArrival?,
        second: ChirpArrival?,
        staggerFrames: Int,
        separationMetres: Double,
        sampleRate: Int,
        edgeShares: List<Double>
    ): AlignmentReading {
        require(separationMetres.isFinite() && separationMetres >= 0) {
            "separationMetres is required: the distance between the two handsets, in metres"
        }
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
        // The loudest reading of the same pair, kept whenever the reading above is not already it.
        val loudestStaggerFrames = if (trustworthy) second!!.index - first!!.index else null
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
            },
            rawLoudestMs = if (edgeShares.isEmpty() || loudestStaggerFrames == null) null
            else (loudestStaggerFrames - staggerFrames).toDouble() / sampleRate * 1000
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
     * The two halves are read by different rules once the run swept thresholds, which is the
     * whole of why it sweeps. The half sum is the alignment and takes the loudest lag from both
     * sides ([AlignmentReading.rawLoudestMs]); the half difference is the flight time and takes
     * the leading edge from both. Mixing the rules across the two sides would be worse than
     * either, so each is taken from both sides or from neither.
     *
     * Returns null unless both sides were trustworthy: half a pair says nothing on its own.
     */
    fun combineFacing(hostSide: AlignmentReading?, sinkSide: AlignmentReading?): FacingPair? {
        val hostError = hostSide?.alignmentErrorMs ?: return null
        val sinkError = sinkSide?.alignmentErrorMs ?: return null
        val rawHostMs = hostSide.rawLoudestMs ?: (hostError - hostSide.propagationCorrectionMs)
        val rawSinkMs = sinkSide.rawLoudestMs ?: (sinkError - sinkSide.propagationCorrectionMs)
        val flightTimeMs = edgeFlightTimeMs(hostSide.rawMsByShare, sinkSide.rawMsByShare)
            ?: ((rawSinkMs - rawHostMs) / 2)
        return FacingPair(
            alignmentErrorMs = (rawHostMs + rawSinkMs) / 2,
            separationMetres = flightTimeMs / 1000 * SPEED_OF_SOUND_M_S,
            flightTimeMs = flightTimeMs,
            rawHostMs = rawHostMs,
            rawSinkMs = rawSinkMs,
            separationSpreadMetres = spreadOf(hostSide.rawMsByShare, sinkSide.rawMsByShare),
            firingOffsetMs = edgeFiringOffsetMs(hostSide.rawMsByShare, sinkSide.rawMsByShare)
        )
    }

    /**
     * Every pair in the room, out of the window each handset recorded for itself.
     *
     * [slotsByHandset] is keyed by the slot each handset was given, which is also its name in the
     * answer: a key of 0 to 2 is the pair between the handset that chirped first and the one that
     * chirped third. A pair whose two halves did not both report comes back null rather than
     * missing, because a room that quietly answered fewer pairs than it has looks like a room with
     * fewer handsets in it.
     *
     * Which side each handset goes on is the whole of what this adds, and getting it wrong is not
     * visible downstream: [combineFacing] takes the half difference, so the two swapped returns a
     * negative separation and an alignment error of the opposite sign - a working room with every
     * distance inside out. The handset in the later slot is the host side, because it hears its own
     * chirp across centimetres and its partner's across the room, which is the sign convention that
     * function's algebra is written in.
     */
    fun facingPairs(
        slotsByHandset: Map<Int, List<ChirpArrival?>>,
        slotFrames: Int,
        sampleRate: Int = ChirpGenerator.SAMPLE_RATE,
        edgeShares: List<Double> = emptyList()
    ): Map<Pair<Int, Int>, FacingPair?> {
        val slots = slotsByHandset.keys.sorted()
        val answers = LinkedHashMap<Pair<Int, Int>, FacingPair?>()
        for (earlier in slots) {
            for (later in slots) {
                if (later <= earlier) continue
                // Zero on both sides: the flight time is what the half difference is about to
                // measure, so correcting for a distance here would be assuming the answer.
                val side = { own: Int ->
                    slotsByHandset[own]?.let {
                        betweenSlots(it, earlier, later, slotFrames, 0.0, sampleRate, edgeShares)
                    }
                }
                answers[earlier to later] = combineFacing(side(later), side(earlier))
            }
        }
        return answers
    }

    /**
     * The separation computed once per share, widest minus narrowest.
     *
     * Null rather than zero when there is nothing to compare - a pair that swept no shares, or two
     * sides that swept different ones, has not answered the question, while zero is the answer
     * that means the run is at its most trustworthy.
     */
    /**
     * The flight time at the first threshold of the sweep, which is the pick, or null when the
     * two sides did not sweep the same ones.
     *
     * The same half difference [combineFacing] has always taken, off the leading edge instead of
     * the loudest lag. 09-11 measured what that is worth: nine readings of one unchanged two
     * metre gap spanned 5.11 to 11.86 m read at the loudest and 1.97 to 2.19 read at the edge.
     */
    private fun edgeFlightTimeMs(hostByShare: List<Double>, sinkByShare: List<Double>): Double? {
        if (hostByShare.isEmpty() || hostByShare.size != sinkByShare.size) return null
        return (sinkByShare[0] - hostByShare[0]) / 2
    }

    /**
     * The other half of the same two readings, at the same threshold: the half sum.
     *
     * The flight time cancels out of it exactly as the firing offset cancels out of the half
     * difference, so one round of chirps answers both and neither costs the other anything.
     */
    private fun edgeFiringOffsetMs(hostByShare: List<Double>, sinkByShare: List<Double>): Double? {
        if (hostByShare.isEmpty() || hostByShare.size != sinkByShare.size) return null
        return (sinkByShare[0] + hostByShare[0]) / 2
    }

    private fun spreadOf(hostByShare: List<Double>, sinkByShare: List<Double>): Double? {
        if (hostByShare.isEmpty() || hostByShare.size != sinkByShare.size) return null
        val metres = hostByShare.indices.map {
            (sinkByShare[it] - hostByShare[it]) / 2 / 1000 * SPEED_OF_SOUND_M_S
        }
        return metres.max() - metres.min()
    }
}
