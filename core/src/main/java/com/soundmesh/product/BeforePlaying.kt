package com.soundmesh.product

import com.soundmesh.probe.sync.Carried

/**
 * One thing put to a host on its way onto the playing page, most with a way to go and fix it.
 *
 * Asked one at a time rather than listed, because each one has its own place to go: this handset's
 * output-lead screen, or the pair screen aimed at that one handset. Two handsets in one question
 * would be a question with two answers under one 去校准.
 */
sealed interface BeforePlaying {
    /**
     * No device has joined, so there is nobody to play to. Its only way to fix is on the other
     * devices, which is why it has no 去校准 - only 取消, the one it steers towards, and 继续.
     */
    object NobodyJoined : BeforePlaying

    /** This handset has never measured how far its capturing output runs ahead of media. */
    object OwnLead : BeforePlaying

    /** A standing handset that says it carries no correction for this host at all. */
    data class Uncalibrated(val peerId: String, val name: String) : BeforePlaying
}

/** A standing handset as the question needs it: who it is, and what it says it carries. */
data class PeerCarrying(val peerId: String, val name: String, val carrying: Carried)

/**
 * What to ask before playing, in the order it is asked: nobody having joined first, since nothing
 * else matters without somebody to play to; then the own lead; then each handset that carries
 * nothing, in the order they are listed.
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
    for (peer in peers) {
        if (peer.carrying == Carried.NOTHING) add(BeforePlaying.Uncalibrated(peer.peerId, peer.name))
    }
}
