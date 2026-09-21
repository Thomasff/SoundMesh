package com.soundmesh.probe.sync

import android.content.Context
import com.soundmesh.core.DiscoveryFailure
import com.soundmesh.core.DiscoveryOutcome
import com.soundmesh.core.HostRepoint
import com.soundmesh.core.PairingCode
import com.soundmesh.core.PeerAdvertisement
import com.soundmesh.core.Repointing
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
        val mine = HostIdentity(directory).current()
        // Own record out of the whole list before anything is counted, which is what [lookAgain]
        // has always done and this did not: it asked afterwards, of the single host choose() had
        // already picked, so its own record standing beside a real one was two hosts and both were
        // dropped. That is not rare - it is every handset that has just handed the role over, for
        // as long as the platform goes on answering for it.
        val others = outcome.hosts.filter { PeerAdvertisement.hostIdOf(it) != mine }
        val peer = others.singleOrNull() ?: run {
            // Nothing listened for, so ask. One network answers no search at all from inside it -
            // a handset serving its own hotspot is invisible to the handsets on it, measured
            // 2026-09-22 - and on that one the host is not merely findable by other means, it is
            // the gateway. See [HostAtTheGateway].
            atTheGateway(context, mine)?.let {
                if (paired.read() != null) return Found(null, ALREADY_PAIRED)
                paired.write(it)
                return Found(it, "${wordFor(outcome, mine)}, but ${it.address} is hosting at the gateway; following it")
            }
            return Found(
                null,
                if (others.isEmpty() && outcome.hosts.isNotEmpty()) ONLY_ITS_OWN_RECORD
                else wordFor(outcome, mine)
            )
        }
        // Why its own record is there to be filtered at all: giving the role back is a request to
        // a platform daemon rather than an act. The record goes on being answered for seconds
        // after somebody picks 当从机, and other devices' caches hold it longer still. Without
        // the filter the handset stores itself as its own host and dials a port nothing is
        // serving - for ever, because a stored host is never replaced by a search.
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
     * Looks on behalf of a handset that has a host and cannot reach it.
     *
     * The counterpart to [lookOnce] and the opposite rule: that one fills an empty slot and never
     * argues with a full one, this one re-reads a full slot that has stopped working. Both are
     * needed because a stored pairing carries an identity and two facts that expire with it - the
     * address and the port are where that handset was standing when somebody scanned it, and a
     * host that joins another network, switches its hotspot on or takes a new lease has moved
     * without having changed. Until this existed the sink dialled the old address for ever, and
     * the only way out was somebody finding 忘记主机 by hand.
     *
     * The decision itself is [HostRepoint], pure and tested away from any network. What is here
     * is the part that needs one: spend a window, then write the answer down unless a scan landed
     * inside it. The re-read is the same guard [lookOnce] carries, for the same reason - a person
     * holding a phone up to a screen is the one thing that must not be overwritten.
     */
    fun lookAgain(context: Context, directory: File, windowMillis: Int): Found {
        val paired = PairedHost(directory)
        val stored = paired.read() ?: return Found(null, NOT_POINTED_AT_ANYBODY)
        val outcome = PeerDiscovery(context).discover(windowMillis)
        // Its own record taken out for [lookOnce]'s reason, and here it would do the other kind of
        // damage: a handset counts as "one other host" while its own stale record is still being
        // answered, which is the one shape that moves a sink onto a different handset.
        val mine = HostIdentity(directory).current()
        // ofUnreachable rather than of: this is only ever asked once the line to the stored
        // address has been down long enough to come looking, and a record answering from an
        // address this handset has already failed at is not a host. What decides it is the port
        // rather than the clock - see RoomCommands.stillServing - so a host that is serving is
        // left alone however long this handset's own radio has been unable to reach it.
        val what = HostRepoint.ofUnreachable(
            stored,
            outcome.hosts.filter { PeerAdvertisement.hostIdOf(it) != mine }
        ) { RoomCommands.stillServing(it.address) }
        // Only where the records came to nothing at all. Anything else means this handset can see
        // the hosts on its network, and a gateway asked on top of that would be a second opinion
        // about a question already answered - including [Repointing.TOO_MANY], where two hosts
        // answered and guessing is precisely what must not happen.
        if (what.verdict == Repointing.NOBODY) {
            HostRepoint.ofTheGateway(stored, atTheGateway(context, mine)) {
                RoomCommands.stillServing(it.address)
            }?.let {
                if (paired.read() != stored) return Found(null, SCANNED_MEANWHILE)
                paired.write(it)
                return Found(it, "nothing answered, but ${it.address} is hosting at the gateway; following it")
            }
        }
        val host = what.host ?: return Found(null, wordFor(what.verdict, stored))
        if (paired.read() != stored) return Found(null, SCANNED_MEANWHILE)
        paired.write(host)
        return Found(host, wordFor(what.verdict, host))
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
     * The same question put to the handset this one is reached through, for when nothing answered.
     *
     * [anotherHost] can report only what the records report, and on a hotspot they never report
     * the handset serving it: measured 2026-09-22, eleven searches over two minutes from a client
     * of a hosting hotspot answered nothing, while a client that took the role was found on the
     * first. So on the one configuration where two rooms are easiest to make by accident, the
     * one-host rule was unenforceable - and the second host was never told, which is the half of
     * that fault nobody can see from a screen.
     *
     * Asked after the records rather than instead of them. On 2026-09-21 the same configuration
     * found the same handset from its records on the first look, so the blindness is not a
     * property of that handset and neither way is reliable. The cheap one goes first.
     *
     * Null is "no host this way", the same as [anotherHost], and for one reason more than it: on
     * every other network the gateway is a router, and a router answers nothing on that port.
     */
    fun anotherHostAtTheGateway(context: Context, myId: String): String? =
        atTheGateway(context, myId)?.address

    /** The handset this one is reached through, if it is hosting and is not this handset. */
    private fun atTheGateway(context: Context, mine: String): PairingCode? {
        val gateway = HostAtTheGateway.gatewayOf(context) ?: return null
        return notThisHandset(HostAtTheGateway.ask(gateway), mine)
    }

    /**
     * [code], unless it is this handset's own answer - which on a hotspot it can be.
     *
     * A handset serving an access point is the default gateway seen from inside the network it is
     * providing, so asking who is hosting there can reach its own server and hand it its own
     * pairing code. The two callers would then do two different wrong things with it, both
     * permanent: a sink would store itself and dial a port nothing on it serves for ever, because
     * a stored host is never replaced by a search; and a handset picking 当主机 would read it as
     * somebody else's room and step out of its own, on the one configuration this whole path
     * exists for - a role that cannot be taken, with nothing on the screen saying why.
     *
     * Its own function because everything around it needs a Context, a route table and a socket,
     * and this is the part that can be asked a question. It was one line inside [atTheGateway]
     * until 2026-09-21, and deleting that line left the whole app suite green.
     */
    internal fun notThisHandset(code: PairingCode?, mine: String): PairingCode? =
        if (code == null || code.hostId == mine) null else code

    /**
     * What one look came to: the host to use, and one line saying why when there is none.
     *
     * The sentence is for the timeline rather than for a screen. Nothing found and nothing
     * compatible send somebody to two different places - a network that carries no multicast
     * against a handset on an older build - and from the screen both are simply "没找到".
     */
    data class Found(val host: PairingCode?, val why: String)

    /**
     * Why one look came to nothing, in enough detail to act on.
     *
     * Two answers used to be one sentence with nothing in it, and on 09-21 that sentence stood
     * for seventy seconds at a time while a handset that had just handed the role over sat there
     * not joining anybody. Two quite different things produce it - this handset's own record still
     * being answered after it stopped being the host, or one host advertising under two service
     * names because the platform renamed a registration that collided with its own stale one -
     * and they are repaired in different places. So the line names what answered: the service
     * name, where it was, and whose it is.
     */
    private fun wordFor(outcome: DiscoveryOutcome, mine: String): String = when (outcome.failure) {
        DiscoveryFailure.NOTHING_FOUND -> "nothing answered"
        DiscoveryFailure.NO_COMPATIBLE_VERSION -> "something answered on an older build"
        DiscoveryFailure.AMBIGUOUS ->
            "more than one host answered, so none was joined: " +
                outcome.hosts.joinToString("; ") { peer ->
                    val id = PeerAdvertisement.hostIdOf(peer)
                    "${peer.name} at ${peer.hostAddress}:${peer.port} is ${id.take(6)}" +
                        if (id == mine) " (this handset's own)" else ""
                }
        null -> "a host answered but could not be read"
    }

    /** What one re-look came to, in the words the timeline needs rather than a verdict's name. */
    private fun wordFor(verdict: Repointing, host: PairingCode): String = when (verdict) {
        Repointing.STILL_THERE ->
            "the host is still at ${host.address}, so whatever is wrong is not the address"
        Repointing.MOVED -> "the same host has moved to ${host.address}, following it"
        Repointing.REPLACED ->
            "the stored host is not on this network and one other is, following ${host.address}"
        Repointing.REPLACED_A_GHOST ->
            "the stored host is still being answered but cannot be reached, so its record was " +
                "ignored; following ${host.address}"
        Repointing.NOBODY -> "nothing answered, so this handset stays pointed at ${host.address}"
        Repointing.TOO_MANY ->
            "more than one other host answered, so this handset stays pointed at ${host.address}"
    }

    private const val ALREADY_PAIRED = "already pointed at a host"

    private const val ONLY_ITS_OWN_RECORD =
        "the only host that answered was this handset's own stale record, so nothing was stored"

    private const val NOT_POINTED_AT_ANYBODY = "not pointed at anybody, so there was nothing to re-check"

    private const val SCANNED_MEANWHILE = "a code was scanned while this was looking, so nothing was written"

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

    /**
     * How long the line to a stored host has to have been down before [lookAgain] is worth running.
     *
     * Twenty seconds until 09-21, for a reason that was right when the only way to have a host
     * was to scan one: an ordinary drop is fixed by the command client's own reconnecting, which
     * costs nothing, while this costs a multicast window. Handing the role from one handset to
     * another was rare then. It is ordinary now, and on that path the wait is the whole of what
     * anybody sees - somebody deliberately gives the role away and the room takes half a minute
     * to notice. Five seconds by the user's decision, not by measurement.
     *
     * What it does not cost is a search every five seconds: [GAP_MILLIS] still spaces the looks,
     * so all this changes is how soon the first one starts.
     *
     * The wait itself is the crude part. The host knows it is standing down and could say so on
     * the way out, the way the power line is said - a word added, not a version bumped - and then
     * nobody would be timing anything. Parked in now.md rather than done here.
     */
    const val STALE_AFTER_MILLIS = 5_000L
}
