package com.soundmesh.product

import com.soundmesh.core.RoomExcuse

/**
 * One sentence a round says about itself, with the numbers that go in it.
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

    // What a host's round says. Here beside the sink's since 09-24, when the host's round moved to
    // core as well: the same table of keys, so a desktop host says what a handset host says.

    /** `pair_calibrate_waiting`: waiting for a handset to ask. */
    object Waiting : RoundLine()

    /** `pair_calibrate_waiting_aimed`: the named handset has been told and is on its way. */
    object WaitingAimed : RoundLine()

    /** `pair_calibrate_aimed_gone`: the named handset was there a moment ago and is not now. */
    object AimedGone : RoundLine()

    /** `pair_calibrate_room_told_nobody`: nobody was standing by to be told. */
    object RoomToldNobody : RoundLine()

    /** `pair_calibrate_room_waiting`: how many were told, and the most this waits for them. */
    data class RoomWaiting(val told: Int, val seconds: Int) : RoundLine()

    /** `pair_calibrate_room_joined`: how many of those told have asked so far. */
    data class RoomJoined(val joined: Int, val told: Int) : RoundLine()

    /** `pair_calibrate_room_called_off_here`: this host called the round off. */
    object RoomCalledOffHere : RoundLine()

    /** `pair_calibrate_stopping`: this host stopped a pair round. */
    object Stopping : RoundLine()

    /** `pair_calibrate_room_done`: handsets in the room, pairs measured, pairs in all. */
    data class RoomDone(val handsets: Int, val measured: Int, val pairs: Int) : RoundLine()

    /** `pair_calibrate_host_done`: a pair's constant as the host combined it, and the distance. */
    data class HostDone(val ms: Double, val metres: Double) : RoundLine()

    /** `pair_calibrate_distance_done`: the distance arm's only answer. */
    data class DistanceDone(val metres: Double) : RoundLine()

    /** `pair_calibrate_wrong_sink`: a delivery signed by [peerId], not the handset this round is with. */
    data class WrongSink(val peerId: String) : RoundLine()

    /** One `excuse_*` key: why [excuse] kept a handset out of the round. */
    data class Excused(val excuse: RoomExcuse) : RoundLine()
}
