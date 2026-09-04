package com.soundmesh.core

/** Why two handsets' readings could not be combined into one measurement. */
enum class PairingFailure {
    /** The delivered readings belong to a different run. */
    RESULT_CASE_MISMATCH,

    /** One of the two handsets read nothing at all - most often a sink that was never recording. */
    ONE_SIDED_RUN,

    /** Both read, but not the same number of chirp pairs. */
    PAIR_COUNT_MISMATCH
}

/**
 * One run's measurement as the two handsets together see it, or the reason there isn't one.
 *
 * [pairs] and [verdict] are populated only when [failure] is null; a failure leaves them empty and
 * null rather than partly filled, because a half-combined run is the failure this type exists to
 * keep out of a report.
 */
data class PairedAlignment(
    val failure: PairingFailure?,
    val pairs: List<FacingPair?>,
    val verdict: RunVerdict?
)

/**
 * Combines the reading each handset took of the same run into the run's actual measurement.
 *
 * A single handset can only produce half of one. Its microphone sits centimetres from its own
 * speaker and a room away from its partner's, so every reading it takes carries the flight time
 * across the room - about -2.9 ms per metre, against a 5 ms gate. Only when both sides are put
 * together does that term cancel, which is what [AlignmentAnalysis.combineFacing] does.
 *
 * The guards are the point of having this as its own step. Combining two runs' readings, or six of
 * one side against four of the other, yields numbers that look exactly like a good measurement -
 * the arithmetic succeeds, nothing throws, and the report reads clean. Each is named instead.
 */
object AlignmentPairing {
    fun combine(
        caseId: String,
        hostReadings: List<AlignmentReading>,
        delivered: AlignmentResultMessage
    ): PairedAlignment {
        val failure = when {
            delivered.caseId != caseId -> PairingFailure.RESULT_CASE_MISMATCH
            hostReadings.isEmpty() || delivered.readings.isEmpty() -> PairingFailure.ONE_SIDED_RUN
            hostReadings.size != delivered.readings.size -> PairingFailure.PAIR_COUNT_MISMATCH
            else -> null
        }
        if (failure != null) return PairedAlignment(failure, emptyList(), null)

        val pairs = hostReadings.indices.map {
            AlignmentAnalysis.combineFacing(hostReadings[it], delivered.readings[it])
        }
        return PairedAlignment(null, pairs, AlignmentVerdict.judge(pairs))
    }
}
