package com.soundmesh.probe.sync

import android.content.Context
import com.soundmesh.core.DiscoveryFailure
import com.soundmesh.core.PairingCode
import com.soundmesh.core.PeerAdvertisement
import java.io.File

/**
 * Finding the host without anybody holding a phone up to a screen.
 *
 * The scanned code is what this replaces on the ordinary path, and it is deliberately not what
 * this removes: mDNS answers on a network that carries multicast between its clients, and plenty
 * of them do not - a guest network with client isolation, a campus one, a router that filters
 * multicast to save airtime. On those this finds nothing, forever, and the code on the host's
 * screen is the whole of the answer. So the rule is that this fills an empty slot and never
 * argues with a full one.
 *
 * Two hosts is a refusal, not a race won by whoever answered first, and that falls out of
 * [PeerAdvertisement.choose] rather than being decided here. It is also the second line of the
 * one-host rule: the host end refuses to become the second host, and if it ever fails to - on a
 * network where it could not see the first one - then no sink joins either of them by itself.
 */
object HostSearch {
    /**
     * Looks for one window, and writes down what it found if it is allowed to.
     *
     * Returns the host now stored, or null when there is nothing to store. Blocking for the whole
     * window, on the thread it is called from - see [PeerDiscovery.discover] for why the window is
     * spent rather than cut short at the first answer.
     */
    fun lookOnce(context: Context, directory: File, windowMillis: Int): Found {
        val paired = PairedHost(directory)
        // Asked again here rather than trusted from the caller: the window is seconds long, and a
        // scan finishing inside it is exactly the thing that must not be overwritten.
        if (paired.read() != null) return Found(null, ALREADY_PAIRED)
        val outcome = PeerDiscovery(context).discover(windowMillis)
        val peer = outcome.peer ?: return Found(null, wordFor(outcome.failure))
        val code = PairingCode(
            hostId = PeerAdvertisement.hostIdOf(peer),
            address = peer.hostAddress,
            chunkPort = peer.port
        )
        if (paired.read() != null) return Found(null, ALREADY_PAIRED)
        paired.write(code)
        return Found(code, "found a host at ${code.address}")
    }

    /**
     * The address of a host on this network that is not this handset, or null.
     *
     * Asked by a handset that has just been told it is the host, and answered from the same
     * records a sink reads - which is what makes the rule enforceable at all: a room with two
     * hosts is two timelines, two spatial fields and two sets of volumes for one set of phones,
     * and nothing downstream of that has any way to notice.
     *
     * Null is "I did not find one", never "there is not one". It cannot be the second thing: this
     * is multicast, and a network that drops it answers exactly as an empty network does. So the
     * refusal is real when it fires and there is no warning when it does not - see the class note.
     *
     * Every answer is filtered by identity rather than the first one taken, because this handset
     * is already advertising by the time it asks. It has to be: two people pressing 主机 within
     * the same window can only see each other if both records are already out.
     */
    fun anotherHost(context: Context, myId: String, windowMillis: Int): String? =
        PeerAdvertisement
            .otherThan(PeerDiscovery(context).discover(windowMillis).hosts, myId)
            ?.hostAddress

    /**
     * What one look came to: the host to use, and one line saying why when there is none.
     *
     * The sentence is for the timeline rather than for a screen. Nothing found and nothing
     * compatible send somebody to two different places - a network that carries no multicast
     * against a handset on an older build - and from the screen both are simply "没找到".
     */
    data class Found(val host: PairingCode?, val why: String)

    private fun wordFor(failure: DiscoveryFailure?): String = when (failure) {
        DiscoveryFailure.NOTHING_FOUND -> "nothing answered"
        DiscoveryFailure.NO_COMPATIBLE_VERSION -> "something answered on an older build"
        DiscoveryFailure.AMBIGUOUS -> "more than one host answered, so none was joined"
        null -> "a host answered but could not be read"
    }

    private const val ALREADY_PAIRED = "already pointed at a host"

    /**
     * How long one look lasts, and how long the gap between two of them is.
     *
     * The window is the same five seconds the session's own re-discovery spends, for the same
     * reason: mDNS has no message that means "that was all of them", so the only way not to turn
     * two hosts into whichever answered first is to wait the window out.
     *
     * The gap is what makes this a search rather than a single attempt, and it is what the whole
     * feature costs in battery on a handset nobody has started yet: the host is very often not up
     * when the sink is, so looking once and giving up would leave the phone waiting for a scan
     * anyway.
     */
    const val WINDOW_MILLIS = 5_000

    /** @see WINDOW_MILLIS */
    const val GAP_MILLIS = 10_000L
}
