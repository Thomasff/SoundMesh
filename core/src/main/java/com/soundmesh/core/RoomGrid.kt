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
     * The first grid instant at or after [notBefore].
     *
     * Floor division rather than the remainder operator: `System.nanoTime()` is allowed to return
     * a negative value, and `%` rounds toward zero, which on a negative instant would hand back a
     * grid point in the past - a chirp scheduled for a moment that has already gone.
     */
    fun nextInstant(notBefore: Long): Long =
        Math.floorDiv(notBefore + GRID_NANOS - 1, GRID_NANOS) * GRID_NANOS
}
