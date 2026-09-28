package com.soundmesh.product

import com.soundmesh.probe.sync.Carried

/**
 * One thing put to a host on its way onto the playing page, most with a way to go and fix it.
 *
 * Asked one at a time, because each kind has its own place to go: this handset's output-lead
 * screen, or the room round. The uncalibrated handsets are one question between them, not one each:
 * the room round measures every one of them at once, so asking about each in turn was three dialogs
 * with the same answer, and the 去校准 on each went to a one-handset pair round instead.
 */
sealed interface BeforePlaying {
    /**
     * No device has joined, so there is nobody to play to. Its only way to fix is on the other
     * devices, which is why it has no 去校准 - only 取消, the one it steers towards, and 继续.
     */
    object NobodyJoined : BeforePlaying

    /** This handset has never measured how far its capturing output runs ahead of media. */
    object OwnLead : BeforePlaying

    /** The standing handsets that say they carry no correction for this host at all, in list order. */
    data class Uncalibrated(val names: List<String>) : BeforePlaying
}

/** A standing handset as the question needs it: who it is, and what it says it carries. */
data class PeerCarrying(val peerId: String, val name: String, val carrying: Carried)

/**
 * What to ask before playing, in the order it is asked: nobody having joined first, since nothing
 * else matters without somebody to play to; then the own lead; then the handsets that carry
 * nothing, all in one.
 *
 * Only [Carried.NOTHING]. A handset on a room round is about a millisecond out, which is the
 * roster's quiet grey, not a reason to stop somebody at the door; and one that says nothing is a
 * build from before the message, very likely fine - asking about it would send somebody to
 * recalibrate a handset that does not need it. See the roster's carryingWord.
 *
 * [ownLeadMissing] is false wherever the question does not exist, which is a computer: it plays
 * nothing on a second output of its own.
 */
fun beforePlaying(ownLeadMissing: Boolean, peers: List<PeerCarrying>): List<BeforePlaying> = buildList {
    if (peers.isEmpty()) add(BeforePlaying.NobodyJoined)
    if (ownLeadMissing) add(BeforePlaying.OwnLead)
    val uncalibrated = peers.filter { it.carrying == Carried.NOTHING }
    if (uncalibrated.isNotEmpty()) add(BeforePlaying.Uncalibrated(uncalibrated.map { it.name }))
}

/**
 * Whether the room round is the next thing to do on the host's board: some device carries nothing.
 *
 * The board draws one solid button at a time, the next step - this, or 进入播放. The same line
 * [beforePlaying] stops somebody at, so the board and the question never disagree about it.
 */
fun roomRoundDue(carrying: List<Carried>): Boolean = Carried.NOTHING in carrying

/**
 * Whether the room round's box says it has been measured: somebody joined and nobody is due.
 *
 * Read off the roster rather than off a record of a round having run, so a device that joins
 * uncalibrated afterwards turns the box back into the thing to do. An empty room is not done.
 */
fun roomRoundDone(carrying: List<Carried>): Boolean = carrying.isNotEmpty() && !roomRoundDue(carrying)

/**
 * Whether pressing a device's line says what fine calibration costs before opening it.
 *
 * Not for [Carried.SOMETHING]: that device has been through it, and "each phone only needs it once"
 * is said to somebody who already knows. [ticked] is 不再提示, once ticked.
 */
fun saysFineCalibrationFirst(carrying: Carried, ticked: Boolean): Boolean =
    carrying != Carried.SOMETHING && !ticked
