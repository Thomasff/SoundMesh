package com.soundmesh.core

/**
 * Which side of the pair a handset is on for one calibration.
 *
 * It comes from the pairing rather than from anything measured: the handset that showed the code
 * hosts and the one that scanned it follows. That matters because the correction a calibration
 * produces is directional - stored on the sink, filed under the host's id - so the role is part of
 * the answer rather than a detail of how it was reached.
 */
enum class CalibrationRole { HOST, SINK }

/** Every instant one handset acts on during a calibration, laid out before anything plays. */
data class CalibrationTiming(
    val warmUpFromHostNanos: Long,
    val warmUpUntilHostNanos: Long,
    val ownChirpAtHostNanos: List<Long>,
    val recordFromHostNanos: Long,
    val recordUntilHostNanos: Long
)

/**
 * Turns a [CalibrationPlan] into the instants one handset acts on.
 *
 * Both handsets compute this from the same plan and differ only in which chirps are theirs, which
 * is the property that lets their two recordings be read as halves of one measurement. Everything
 * else - when the warm-up runs, when the recording opens and closes - is deliberately identical on
 * both sides, so a pair disagreeing about any of it is a fault in the plan rather than in a role.
 */
object CalibrationSchedule {
    /**
     * How long ordinary audio plays before the first chirp, so the renderer's output depth
     * compensation is settled rather than still acquiring.
     *
     * Four seconds, matching `OutputLeadRunner.WARMUP_NANOS`, and the archive says that is ample:
     * O71's renderer reported an `acquisitionDurationNanos` of 0.52 s. The margin is taken rather
     * than trimmed because it costs four seconds once and what it buys is a whole run.
     */
    const val WARM_UP_NANOS = 4_000_000_000L

    /**
     * Silence between the warm-up and the first chirp, so the tone is not in the correlation.
     *
     * Matches `OutputLeadRunner.CALIBRATION_GAP_NANOS`, and is three times the half-second radius
     * the correlation searches either side of where a chirp is expected.
     */
    const val GAP_NANOS = 1_500_000_000L

    /**
     * How long the recording stays open past the last chirp of the pair.
     *
     * Matches `SyncActivity.RECORD_TAIL_NANOS`: long enough for the sound to have left the output
     * buffer and crossed the room to the far microphone.
     */
    const val RECORD_TAIL_NANOS = 2_000_000_000L

    fun of(plan: CalibrationPlan, role: CalibrationRole, chirpNanos: Long): CalibrationTiming {
        require(plan.repeats >= 1) { "a calibration with no chirps has nothing to correlate" }
        require(plan.intervalNanos > 0) { "repeats sharing an instant cannot be told apart" }
        require(plan.staggerNanos > 0) { "the two chirps of a pair are told apart by the stagger" }
        require(chirpNanos > 0) { "a chirp of no length is not a chirp" }

        val own = if (role == CalibrationRole.HOST) plan.staggerNanos else 0L
        val warmUpUntil = plan.firstChirpAtHostNanos - GAP_NANOS
        val warmUpFrom = warmUpUntil - WARM_UP_NANOS
        // The later chirp of the last pair, whichever side this is: each handset hears both, and
        // the measurement is made of both. A recording closed at this handset's own last chirp
        // would drop the partner of that pair, which reads afterwards as a quiet room.
        val lastSound = plan.firstChirpAtHostNanos +
            (plan.repeats - 1) * plan.intervalNanos + plan.staggerNanos + chirpNanos
        return CalibrationTiming(
            warmUpFromHostNanos = warmUpFrom,
            warmUpUntilHostNanos = warmUpUntil,
            ownChirpAtHostNanos = (0 until plan.repeats).map {
                plan.firstChirpAtHostNanos + it * plan.intervalNanos + own
            },
            // Opened before the first sound rather than before the first chirp: a microphone that
            // will not open has to stop the run before it plays anything, and trying is the only
            // way to find out. The warm-up sits far outside every correlation window, so the extra
            // seconds of recording cost storage and nothing else.
            recordFromHostNanos = warmUpFrom,
            recordUntilHostNanos = lastSound + RECORD_TAIL_NANOS
        )
    }
}
