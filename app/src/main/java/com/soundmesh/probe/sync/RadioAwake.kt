package com.soundmesh.probe.sync

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [body] with the handset exchanging with its router every [everyMillis], so its WiFi radio
 * is awake to receive whatever the other handsets send it.
 *
 * Measured on 2026-09-13, one link and one minute, round trips from a second handset to this one:
 *
 *     idle                                     58.6 ms
 *     sending to an address that never answers 44.9 ms
 *     exchanging with the router               11.1 ms
 *
 * The gate that refuses a slow link sits at 40 ms, so the idle handset was refusing every round.
 * Two things in that table are worth keeping. Sending is not enough - what wakes a station is
 * receiving, and a station with nothing to receive goes back to sleep with frames still queued at
 * the access point for it. And the exchange does not have to be with the handset that is asking:
 * the router will do, which is why this needs no protocol between handsets at all.
 *
 * A WifiLock is held over the same window ([holdingRadio]) and does not replace this - see the
 * note there for what it turned out not to buy.
 *
 * [answered] is told once, after the first poke: true if the router answered, false if it stayed
 * quiet, null if there was nobody to poke. It is not decoration. A poke that reaches nobody
 * measures exactly like no poke at all, and the first attempt at this spent a whole install cycle
 * before the sink reports showed it had changed nothing - the poking was aimed at an IPv6
 * link-local gateway it could never reach, and said nothing about it.
 *
 * The poker is a daemon and is stopped in a finally: left running it keeps the radio out of power
 * save for the life of the process, which is a battery fault nothing in a run would show.
 */
internal fun <T> keepingAwake(
    poke: (() -> Boolean)?,
    everyMillis: Long = POKE_INTERVAL_MILLIS,
    answered: (Boolean?) -> Unit = {},
    body: () -> T
): T {
    if (poke == null) {
        runCatching { answered(null) }
        return body()
    }
    val poking = AtomicBoolean(true)
    val said = AtomicBoolean(false)
    val poker = Thread({
        try {
            while (poking.get()) {
                // A router that stops answering must not end the poking: the next poke is the one
                // that might land, and the run has minutes left to go.
                val reply = runCatching { poke() }.getOrDefault(false)
                if (said.compareAndSet(false, true)) runCatching { answered(reply) }
                Thread.sleep(everyMillis)
            }
        } catch (_: InterruptedException) {
            // The interrupt is this thread's stop, and nothing else uses it.
        }
    }, "SoundMeshRadioAwake")
    poker.isDaemon = true
    poker.start()
    try {
        return body()
    } finally {
        poking.set(false)
        poker.interrupt()
    }
}

/**
 * A round trip with this handset's router, reporting whether it answered.
 *
 * isReachable rather than a socket of our own: it is one call, it goes to a party that is always
 * there and always answers, and it carries nothing - the point is the frame coming back, not what
 * is in it.
 */
internal class RouterPoke(val router: InetAddress) : () -> Boolean {
    override fun invoke(): Boolean =
        runCatching { router.isReachable(POKE_TIMEOUT_MILLIS) }.getOrDefault(false)
}

/**
 * Whatever this handset's network calls its IPv4 router, or null if it will not say.
 *
 * Four is not fussiness. A WiFi network here carries three default routes - one via 192.168.1.1
 * and two via IPv6 link-local addresses - and taking whichever came first took one of the
 * link-local ones, which isReachable cannot reach without a scope it is never given. The v4
 * gateway is also the one the 11.1 ms in [keepingAwake] was measured against.
 */
internal fun routerPokeOf(context: Context): RouterPoke? {
    val links = runCatching {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        manager.getLinkProperties(manager.activeNetwork)
    }.getOrNull() ?: return null
    val router = links.routes
        .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
        ?.gateway ?: return null
    return RouterPoke(router)
}

/** Proven at 200 ms; the sleep it is fighting reappears somewhere above that and was not bracketed. */
private const val POKE_INTERVAL_MILLIS = 200L

/** Bounded so a router that has stopped answering cannot stretch the cadence indefinitely. */
private const val POKE_TIMEOUT_MILLIS = 200
