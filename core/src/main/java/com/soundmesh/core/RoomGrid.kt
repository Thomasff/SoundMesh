package com.soundmesh.core

/**
 * How two machines agree on when to put a sound out, without either telling the other.
 *
 * The arrangement this exists for is a handset lending itself to a PC as a microphone: the PC
 * plays a chirp at a stated instant, the handset records the room, and the measurement comes out
 * of the recording afterwards. Both have to put a chirp into the same short recording, far enough
 * apart to be told apart, and the PC's schedule is settled before the handset's run ends - so the
 * handset cannot simply report the instant it used.
 *
 * They do not have to talk. Both hang their chirp on the same grid of the host clock: the handset
 * emits on a grid instant, and the PC - which holds the offset from the clock exchange, and so
 * knows the handset's clock - emits [PEER_SLOT_NANOS] after the same one. A shared divisor is the
 * whole protocol.
 *
 * In core rather than on either side, and for the reason [com.soundmesh.probe.sync.ClockPacket]'s
 * port is: a second copy of a number two machines have to agree on fails in the one way that costs
 * a session to find, with both ends working perfectly and neither meeting the other.
 *
 * `System.nanoTime()` counts from an arbitrary origin, so a grid instant is not a round number of
 * anything a person would recognise, and it does not need to be - it only needs both sides to
 * land on the same one.
 */
object RoomGrid {
    /** The spacing of the grid. */
    const val GRID_NANOS = 10_000_000_000L

    /**
     * How long after the handset's chirp the PC's is expected.
     *
     * Wide enough that the PC's offset error - a couple of milliseconds over WiFi - cannot bring
     * the two arrivals close enough to be mistaken for one another, and short enough that both
     * sit inside one short recording with the room unchanged between them.
     */
    const val PEER_SLOT_NANOS = 1_500_000_000L

    /**
     * How many times the pair of chirps is repeated inside one window.
     *
     * One was not enough, and the way it failed is worth keeping. Measured 2026-09-21: five rounds
     * at one placement with nothing touched between them answered 0.932, 2.373, 2.444, 1.206 and
     * 2.369 ms - not a scatter but two levels about 1.3 ms apart. The clock was ruled out (the
     * five offsets lie on a line to 0.095 ms) and so was this machine's own loop (24 frames, and
     * moving the other way), which leaves the handset's emission, whose two-level step this
     * project has measured before at 56 frames.
     *
     * [com.soundmesh.core.AlignmentAnalysis.combineFacing] says where that lands: emission jitter
     * enters both recordings with the same sign, so it lives in the half sum - and the half sum is
     * the pair constant, the one quantity this arrangement exists to produce. The half difference,
     * the separation, cannot carry it and did not: the same five rounds held to 4.6 cm.
     *
     * So the answer has to be a cluster rather than a reading, which is what the product's own
     * pair flow has always done with its five.
     *
     * Eight rather than five for the shape of what is being clustered. The level is drawn per
     * chirp, not once per stream - measured 2026-09-10, forty chirps read one at a time, runs
     * test z=0.94 - so a window of repeats does sample it, and a median survives as long as the
     * high level stays a minority of the window. It need not: the archived occupancy is about
     * 72/28 but has been seen at 35% and, across whole rounds, at three in five. Eight leaves
     * room for two or three high draws where five does not. The first folded window, measured
     * 2026-09-21, ran six low and two high.
     *
     * An earlier version of this comment claimed the eight were here to settle whether the draw
     * is per stream or per chirp. That was already settled, in this project, eleven days before
     * it was written.
     */
    const val REPEATS = 8

    /**
     * How far apart the repeats sit.
     *
     * Twice [PEER_SLOT_NANOS], so the far handset's chirp of one repeat is a whole slot clear of
     * the near handset's next one - the bound [CalibrationSchedule.of] states and refuses on.
     */
    const val REPEAT_NANOS = 2 * PEER_SLOT_NANOS

    /**
     * The schedule both machines lay out from the one instant they share.
     *
     * A function rather than a paragraph telling each side how to compute its own instants. The
     * two ends run different code on different operating systems, and the failure mode of two
     * copies of one formula is the one that costs a session: both sides working perfectly and
     * neither meeting the other. The handset holds slot 0 and the PC slot 1, which is the order
     * the recordings have always been read in.
     *
     * [CalibrationPlan.hostId] is not a pairing here - nothing stores a constant under it - so it
     * carries the arrangement's name rather than a handset's.
     */
    fun planFor(caseId: String, gridHostNanos: Long): CalibrationPlan = CalibrationPlan(
        caseId = caseId,
        hostId = PC_SIDE,
        firstChirpAtHostNanos = gridHostNanos,
        staggerNanos = PEER_SLOT_NANOS,
        repeats = REPEATS,
        intervalNanos = REPEAT_NANOS
    )

    /** Stands in for a handset id in [planFor], where the far side is not a handset. */
    const val PC_SIDE = "pc"

    /**
     * The first grid instant at or after [notBefore].
     *
     * Floor division rather than the remainder operator: `System.nanoTime()` is allowed to return
     * a negative value, and `%` rounds toward zero, which on a negative instant would hand back a
     * grid point in the past - a chirp scheduled for a moment that has already gone.
     */
    fun nextInstant(notBefore: Long): Long =
        Math.floorDiv(notBefore + GRID_NANOS - 1, GRID_NANOS) * GRID_NANOS
}
