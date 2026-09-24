package com.soundmesh.probe.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.soundmesh.core.DiscoveredPeer
import com.soundmesh.core.DiscoveryOutcome
import com.soundmesh.core.PeerAdvertisement
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A host that cannot be found or reached, carrying the code the report should show.
 *
 * Same reason [SourceUnusable] exists: the run's failure handler names an exception by its class,
 * so the distinction between "nothing answered", "something answered that this build cannot talk
 * to" and "two hosts answered" would otherwise all report as one exception name.
 */
class PeerUnavailable(val code: String) : IllegalStateException(code)

/**
 * Finding the other handset without being told its address.
 *
 * mDNS has no server. Every device on the link joins one multicast group, a question asked there
 * is answered by whichever device owns the name, and the answer is multicast too so everyone
 * caches it. On top of that, DNS-SD turns names into services: a PTR record lists the instances of
 * `_soundmesh._tcp`, an SRV record gives each one a host and a port, and TXT carries the extras -
 * here, the protocol version a sink checks before trusting what it found, and the name it files
 * that host under afterwards.
 *
 * This is the fast path, not the whole answer. It requires both handsets to already be on the same
 * network, so it cannot be the thing that puts them there, and it cannot say which of two answers
 * is the handset in your hand. Both belong to the scanned code.
 */
class PeerDiscovery(context: Context) {
    private val application = context.applicationContext
    private val nsd = application.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Advertises this handset as the host until the returned handle is closed.
     *
     * The port in the record is the chunk port, and the sink connects to the port it resolved
     * rather than to its own copy of the constant - so the record carries something rather than
     * being decorative. The clock and result ports stay compile-time constants shared by both
     * sides; when one of them needs to move per device, it can join the attributes then.
     *
     * The identity travels in the attributes beside the version, so a sink that found its host
     * this way files its calibration under the same name a scanned code would have given it.
     */
    fun register(serviceName: String, port: Int, hostId: String): AutoCloseable {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = PeerAdvertisement.SERVICE_TYPE
            this.port = port
            PeerAdvertisement.attributes(hostId).forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) = Unit
            override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceRegistered(service: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(service: NsdServiceInfo) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return AutoCloseable { runCatching { nsd.unregisterService(listener) } }
    }

    /**
     * Listens for hosts for the whole of [windowMillis], then resolves what answered.
     *
     * The full window is spent on purpose rather than returning at the first answer. mDNS has no
     * end - there is no message that says "that was all of them" - so stopping early would turn
     * two hosts into whichever one replied first, and that is the outcome this refuses to guess at.
     */
    fun discover(windowMillis: Int): DiscoveryOutcome {
        val found = Collections.synchronizedList(ArrayList<NsdServiceInfo>())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) { found.add(service) }
            override fun onServiceLost(service: NsdServiceInfo) {
                found.removeAll { it.serviceName == service.serviceName }
            }
        }
        // Held even though NsdManager answers out of a platform daemon that is not subject to this
        // app's multicast filter, so it may well be unnecessary. It is a normal permission, costs
        // nothing but the radio staying unfiltered for a few seconds, and whether these vendor
        // builds behave like the platform one is not something to find out from a failed run.
        val multicast = runCatching {
            (application.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock(MULTICAST_LOCK_TAG)
                .apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        try {
            nsd.discoverServices(PeerAdvertisement.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            Thread.sleep(windowMillis.toLong())
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { multicast?.release() }
        }
        // One resolve at a time: the platform rejects a second one while the first is outstanding,
        // and a rejected resolve is indistinguishable here from a host that is not there.
        val heard = found.distinctBy { it.serviceName }
        val resolved = heard.mapNotNull(::resolve)
        return PeerAdvertisement.choose(resolved).copy(unresolved = heard.size - resolved.size)
    }

    @Suppress("DEPRECATION")
    private fun resolve(service: NsdServiceInfo): DiscoveredPeer? {
        val answered = ArrayBlockingQueue<Any>(1)
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) { answered.offer(errorCode) }
            override fun onServiceResolved(service: NsdServiceInfo) { answered.offer(service) }
        }
        val clients = resolveClients(application)
        val nsd = clients.take()
        runCatching { nsd.resolveService(service, listener) }.onFailure { return null }
        val answer = answered.poll(RESOLVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        if (resolveWasStuck(answer)) clients.giveUp(nsd)
        val info = answer as? NsdServiceInfo ?: return null
        val address = info.host?.hostAddress ?: return null
        return DiscoveredPeer(
            name = info.serviceName,
            hostAddress = address,
            port = info.port,
            attributes = info.attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8) }
        )
    }

    private companion object {
        const val MULTICAST_LOCK_TAG = "soundmesh-discovery"
        const val RESOLVE_TIMEOUT_MILLIS = 5_000L

        @Volatile
        private var resolvers: ResolveClient<NsdManager>? = null

        /**
         * One for the process, since what gets stuck belongs to the process: the platform keeps a
         * client per NsdManager, and getSystemService hands every caller in it the same one.
         */
        fun resolveClients(application: Context): ResolveClient<NsdManager> =
            resolvers ?: synchronized(this) {
                resolvers ?: ResolveClient {
                    // A context of its own is what makes it a new client: NsdManager is cached per
                    // context, and the platform's client is the channel that NsdManager opened.
                    application.createConfigurationContext(application.resources.configuration)
                        .getSystemService(Context.NSD_SERVICE) as NsdManager
                }.also { resolvers = it }
            }
    }
}

/**
 * The platform client resolves go through, given up once a resolve gets stuck in it.
 *
 * Until Android 13 the platform lets each client have one resolve outstanding and refuses every
 * other with FAILURE_ALREADY_ACTIVE, and a resolve of a name that has gone - a host that stopped
 * while its record was still cached - is never answered at all. Waiting out [PeerDiscovery]'s
 * timeout does not end it on the platform's side. So one such resolve left this process unable
 * to resolve anything for as long as it lived: on 2026-09-24 X10 heard Magic6 every ten seconds,
 * resolved it never, and found it on the first look after it was swiped away and reopened.
 *
 * Nothing on those releases takes a resolve back, so the client is left where it is and the next
 * resolve goes through a new one. What that leaks is one stuck request per stuck resolve.
 */
internal class ResolveClient<C : Any>(private val open: () -> C) {
    private var client: C? = null

    @Synchronized
    fun take(): C = client ?: open().also { client = it }

    /** Only if it is still the one in use: a look that overlapped this one may have replaced it. */
    @Synchronized
    fun giveUp(stuck: C) {
        if (client === stuck) client = null
    }
}

/**
 * Whether a resolve's [answer] leaves its client unable to resolve again: no answer at all, or a
 * refusal because an earlier one is still outstanding. A failure the platform did answer with is
 * over, and the client is free.
 */
internal fun resolveWasStuck(answer: Any?): Boolean =
    answer == null || answer == NsdManager.FAILURE_ALREADY_ACTIVE
