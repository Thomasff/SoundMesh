package com.soundmesh.product

/**
 * One sentence a sink's round says about itself, with the numbers that go in it.
 *
 * The round is one copy in core and its words are not: a handset says them out of its string
 * resources, a desktop out of the same keys read at build time. So the round names which sentence
 * and the platform spells it. Each one here is one `pair_calibrate_*` key, with its arguments in
 * the key's own order and units.
 */
sealed class RoundLine {
    /** `pair_calibrate_failed`: the round stopped for [code], a name rather than a sentence. */
    data class Failed(val code: String) : RoundLine()

    /** `pair_calibrate_no_pairing`: nobody to measure against. */
    object NoPairing : RoundLine()

    /** `pair_calibrate_clock`: filling the clock before anything is scheduled. */
    object Clock : RoundLine()

    /** `pair_calibrate_slow_link`: the link's median round trip against the most a round accepts. */
    data class SlowLink(val medianMs: Double, val maxMs: Double) : RoundLine()

    /** `pair_calibrate_room_called_off`: the host called the round off. */
    object CalledOff : RoundLine()

    /** `pair_calibrate_running`: chirping and recording. */
    object Running : RoundLine()

    /** `pair_calibrate_room_sink_done`: a room answered, and how much of it involved this one. */
    data class RoomSinkDone(val handsets: Int, val readable: Int) : RoundLine()

    /** `pair_calibrate_room_sink_approximate`: the same, and the approximate constant kept. */
    data class RoomSinkApproximate(val handsets: Int, val readable: Int, val keptMs: Double) : RoundLine()

    /** `pair_calibrate_measured_only`: the experiment arm's measurement, never folded. */
    data class MeasuredOnly(val ms: Double) : RoundLine()

    /** `pair_calibrate_kept`: nothing moved, for [reason]. */
    data class Kept(val reason: String) : RoundLine()

    /** `pair_calibrate_verified`: what is left after the stored constant. */
    data class Verified(val ms: Double) : RoundLine()

    /** `pair_calibrate_not_folded`: a run too far from the stored constant to be the same thing. */
    data class NotFolded(val ms: Double) : RoundLine()

    /** `pair_calibrate_done`: the constant now carried, and how many runs it is folded from. */
    data class Done(val ms: Double, val observations: Int) : RoundLine()
}
