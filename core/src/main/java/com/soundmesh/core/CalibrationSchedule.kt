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

    /**
     * The pair's schedule, named by role rather than by slot.
     *
     * The host takes the last slot and the sink the first, which for the pair this has always
     * described are slots one and zero - the convention every archived run was made under and the
     * one [AlignmentAnalysis.combineFacing] reads its signs from.
     */
    fun of(plan: CalibrationPlan, role: CalibrationRole, chirpNanos: Long): CalibrationTiming =
        of(plan, if (role == CalibrationRole.HOST) slotsIn(plan) - 1 else 0, chirpNanos)

    /**
     * The schedule for the handset holding [ownSlot] of however many the plan has.
     *
     * One window, one chirp per handset, everybody recording all of it. Which is the pair schedule
     * with two slots, deliberately and not by coincidence: the arithmetic below is the arithmetic
     * that was here before slots existed, and a plan naming nobody still goes through it unchanged.
     */
    fun of(plan: CalibrationPlan, ownSlot: Int, chirpNanos: Long): CalibrationTiming {
        require(plan.repeats >= 1) { "a calibration with no chirps has nothing to correlate" }
        require(plan.intervalNanos > 0) { "repeats sharing an instant cannot be told apart" }
        require(plan.staggerNanos > 0) { "handsets sharing an instant cannot be told apart" }
        require(chirpNanos > 0) { "a chirp of no length is not a chirp" }
        // A repeat has to be over before the next one begins. The window grows with the room
        // while the interval does not, so past a certain size the last handset of one repeat
        // lands on the first handset of the next - and both then sit inside one search window,
        // where they are told apart only by which correlates louder. That is the ceiling on how
        // many handsets one schedule holds, and it is arithmetic rather than an opinion. A
        // single repeat has no next one to land on, and is left alone.
        require(plan.repeats == 1 || (slotsIn(plan) - 1) * plan.staggerNanos + chirpNanos < plan.intervalNanos) {
            "a room of ${slotsIn(plan)} spaced ${plan.staggerNanos} ns apart does not fit in ${plan.intervalNanos} ns"
        }
        // A handset named twice would chirp twice in one window under one name, and every reading
        // of it would be of whichever of the two the correlation happened to like.
        require(plan.slotIds.size == plan.slotIds.distinct().size) {
            "a room names each handset once: ${plan.slotIds}"
        }
        require(ownSlot in 0 until slotsIn(plan)) {
            "slot $ownSlot is not one of the ${slotsIn(plan)} this plan has"
        }

        val own = ownSlot * plan.staggerNanos
        val warmUpUntil = plan.firstChirpAtHostNanos - GAP_NANOS
        val warmUpFrom = warmUpUntil - WARM_UP_NANOS
        // The last handset's last chirp, whichever slot this one holds: every handset hears every
        // chirp and the measurement is made of all of them. A recording closed at this handset's
        // own last chirp would drop everybody scheduled after it, which reads afterwards as a room
        // that went quiet rather than as a window that closed early.
        val lastSound = plan.firstChirpAtHostNanos +
            (plan.repeats - 1) * plan.intervalNanos + (slotsIn(plan) - 1) * plan.staggerNanos + chirpNanos
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

    /**
     * How many handsets this plan schedules. Two when it names nobody, which is what a plan from
     * before rooms existed is: the pair it always described, not an empty room.
     *
     * Public because the reading side asks the same question of the same plan, and two places
     * each deciding what an unnamed plan means is exactly the drift nothing would notice.
     */
    fun slotsIn(plan: CalibrationPlan): Int = maxOf(plan.slotIds.size, 2)
}
