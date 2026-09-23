package com.soundmesh.desktop

import com.soundmesh.core.DiscoveredPeer
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.charset.StandardCharsets.UTF_16LE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * DNS-SD through the responder Windows already runs, called by hand through the foreign function
 * API.
 *
 * The system's own rather than a library's, because the system's is already listening on the
 * multicast port with the firewall's blessing, and because a record it holds is withdrawn when the
 * process that asked for it goes away however that happens. The handsets taught what the other
 * kind costs: a host that had stood down went on being answered for 52 to 73 seconds, and a sink
 * dialled it.
 *
 * This is the only place in the module that knows these structs. Every offset below is the x64
 * layout of the declaration in windns.h / windnsdef.h, named beside it; they are checked by a test
 * that advertises a record and reads it back, because a wrong one reads a neighbouring field and
 * looks like a plausible answer.
 */
internal object DnsSd {

    private val LINKER: Linker = Linker.nativeLinker()
    private val LIBS: Arena = Arena.global()

    private val PTR = ValueLayout.ADDRESS
    private val I16 = ValueLayout.JAVA_SHORT
    private val I32 = ValueLayout.JAVA_INT

    private fun export(name: String, fd: FunctionDescriptor): MethodHandle =
        LINKER.downcallHandle(
            SymbolLookup.libraryLookup("dnsapi.dll", LIBS).find(name)
                .orElseThrow { IllegalStateException("dnsapi.dll has no $name") },
            fd
        )

    private val CONSTRUCT_INSTANCE = export(
        "DnsServiceConstructInstance",
        FunctionDescriptor.of(PTR, PTR, PTR, PTR, PTR, I16, I16, I16, I32, PTR, PTR)
    )
    private val FREE_INSTANCE = export("DnsServiceFreeInstance", FunctionDescriptor.ofVoid(PTR))
    private val REGISTER = export("DnsServiceRegister", FunctionDescriptor.of(I32, PTR, PTR))
    private val DEREGISTER = export("DnsServiceDeRegister", FunctionDescriptor.of(I32, PTR, PTR))
    private val BROWSE = export("DnsServiceBrowse", FunctionDescriptor.of(I32, PTR, PTR))
    private val BROWSE_CANCEL = export("DnsServiceBrowseCancel", FunctionDescriptor.of(I32, PTR))
    private val RESOLVE = export("DnsServiceResolve", FunctionDescriptor.of(I32, PTR, PTR))
    private val RESOLVE_CANCEL = export("DnsServiceResolveCancel", FunctionDescriptor.of(I32, PTR))
    private val DNS_FREE = export("DnsFree", FunctionDescriptor.ofVoid(PTR, I32))

    // ------------------------------------------------------------------------ callbacks
    //
    // All three completions have the one shape VOID f(DWORD status, PVOID context, PVOID result),
    // so there are three stubs for the life of the process rather than one per request, and the
    // context is a number that says which waiter the answer is for. Per-request stubs would have to
    // be freed, and nothing here can say when the responder has made its last call into one - a
    // browse that has been cancelled can still be mid-callback. A late answer to a number nobody
    // is waiting on is freed and dropped.

    private fun interface Completion {
        fun complete(status: Int, result: MemorySegment)
    }

    private val waiters = ConcurrentHashMap<Long, Completion>()
    private val nextContext = AtomicLong(1)

    private val CALLBACK = FunctionDescriptor.ofVoid(I32, PTR, PTR)

    private fun stub(name: String): MemorySegment = LINKER.upcallStub(
        MethodHandles.lookup().findStatic(
            DnsSd::class.java, name,
            MethodType.methodType(Void.TYPE, Int::class.javaPrimitiveType, MemorySegment::class.java, MemorySegment::class.java)
        ),
        CALLBACK,
        LIBS
    )

    private val REGISTER_COMPLETE by lazy { stub("onRegistered") }
    private val BROWSE_ANSWERED by lazy { stub("onBrowsed") }
    private val RESOLVE_COMPLETE by lazy { stub("onResolved") }

    // Nothing may be thrown out of these: an exception crossing back into the responder's thread
    // takes the whole process down. Each one also owns the memory it was handed.

    @JvmStatic
    private fun onRegistered(status: Int, context: MemorySegment, instance: MemorySegment) {
        runCatching { waiters[context.address()]?.complete(status, instance) }
        if (instance != MemorySegment.NULL) FREE_INSTANCE.invokeExact(instance)
    }

    @JvmStatic
    private fun onBrowsed(status: Int, context: MemorySegment, records: MemorySegment) {
        runCatching { waiters[context.address()]?.complete(status, records) }
        if (records != MemorySegment.NULL) DNS_FREE.invokeExact(records, DNS_FREE_RECORD_LIST)
    }

    @JvmStatic
    private fun onResolved(status: Int, context: MemorySegment, instance: MemorySegment) {
        runCatching { waiters[context.address()]?.complete(status, instance) }
        if (instance != MemorySegment.NULL) FREE_INSTANCE.invokeExact(instance)
    }

    // -------------------------------------------------------------------------- register

    /**
     * Puts [instanceName] on the network until the returned handle is closed.
     *
     * No address is given: the responder answers for [hostName] on each interface with that
     * interface's own address, which is what a sink on any of them needs. Choosing one here would
     * be the guess [lanAddresses] refuses to make, and would be wrong for a handset on the other.
     */
    fun register(instanceName: String, hostName: String, port: Int, attributes: Map<String, String>): AutoCloseable {
        val arena = Arena.ofShared()
        val keys = arena.allocate(PTR, attributes.size.toLong().coerceAtLeast(1))
        val values = arena.allocate(PTR, attributes.size.toLong().coerceAtLeast(1))
        attributes.entries.forEachIndexed { index, (key, value) ->
            keys.setAtIndex(PTR, index.toLong(), arena.allocateFrom(key, UTF_16LE))
            values.setAtIndex(PTR, index.toLong(), arena.allocateFrom(value, UTF_16LE))
        }
        val instance = CONSTRUCT_INSTANCE.invokeExact(
            arena.allocateFrom(instanceName, UTF_16LE), arena.allocateFrom(hostName, UTF_16LE),
            MemorySegment.NULL, MemorySegment.NULL,
            port.toShort(), 0.toShort(), 0.toShort(),
            attributes.size, keys, values
        ) as MemorySegment
        check(instance != MemorySegment.NULL) { "DnsServiceConstructInstance refused $instanceName" }

        // DNS_SERVICE_REGISTER_REQUEST: Version @0, InterfaceIndex @4, pServiceInstance @8,
        // pRegisterCompletionCallback @16, pQueryContext @24, hCredentials @32, unicastEnabled @40.
        // Kept until deregistration: the responder is handed this same request to undo.
        val request = arena.allocate(REGISTER_REQUEST_BYTES)
        request.set(I32, 0, DNS_QUERY_REQUEST_VERSION1)
        request.set(PTR, 8, instance)
        request.set(PTR, 16, REGISTER_COMPLETE)

        fun await(call: MethodHandle, what: String) {
            val context = nextContext.getAndIncrement()
            val done = CountDownLatch(1)
            var answer = -1
            waiters[context] = Completion { status, _ -> answer = status; done.countDown() }
            try {
                request.set(PTR, 24, MemorySegment.ofAddress(context))
                val status = call.invokeExact(request, MemorySegment.NULL) as Int
                check(status == DNS_REQUEST_PENDING) { "$what $instanceName: status $status" }
                check(done.await(COMPLETION_SECONDS, TimeUnit.SECONDS)) {
                    "$what $instanceName: no answer in $COMPLETION_SECONDS s"
                }
                check(answer == 0) { "$what $instanceName: completed with $answer" }
            } finally {
                waiters.remove(context)
            }
        }

        try {
            await(REGISTER, "register")
        } catch (e: RuntimeException) {
            FREE_INSTANCE.invokeExact(instance)
            arena.close()
            throw e
        }
        return AutoCloseable {
            try {
                await(DEREGISTER, "deregister")
            } finally {
                FREE_INSTANCE.invokeExact(instance)
                arena.close()
            }
        }
    }

    // ---------------------------------------------------------------------------- browse

    /**
     * The full instance names that answered for [serviceType] within [windowMillis].
     *
     * The whole window is spent, for the reason the handset's discovery spends it: mDNS has no
     * message that says "that was all of them". A record withdrawn during the window - one that
     * arrives again with a lifetime of zero - is taken back out.
     */
    fun browse(serviceType: String, windowMillis: Int): Set<String> {
        val names = ConcurrentHashMap.newKeySet<String>()
        val context = nextContext.getAndIncrement()
        waiters[context] = Completion { status, records ->
            if (status == 0) forEachPointer(records) { name, ttl -> if (ttl == 0) names.remove(name) else names.add(name) }
        }
        Arena.ofShared().use { arena ->
            // DNS_SERVICE_BROWSE_REQUEST: Version @0, InterfaceIndex @4, QueryName @8,
            // pBrowseCallback @16, pQueryContext @24.
            val request = arena.allocate(QUERY_REQUEST_BYTES)
            request.set(I32, 0, DNS_QUERY_REQUEST_VERSION1)
            request.set(PTR, 8, arena.allocateFrom(serviceType, UTF_16LE))
            request.set(PTR, 16, BROWSE_ANSWERED)
            request.set(PTR, 24, MemorySegment.ofAddress(context))
            val cancel = arena.allocate(PTR)
            try {
                val status = BROWSE.invokeExact(request, cancel) as Int
                check(status == DNS_REQUEST_PENDING) { "browse $serviceType: status $status" }
                Thread.sleep(windowMillis.toLong())
                BROWSE_CANCEL.invokeExact(cancel) as Int
            } finally {
                waiters.remove(context)
            }
        }
        return names.toSet()
    }

    /** Each PTR record in a DNS_RECORDW list: the name it points at, and its lifetime. */
    private fun forEachPointer(first: MemorySegment, each: (String, Int) -> Unit) {
        // DNS_RECORDW: pNext @0, pName @8, wType @16, wDataLength @18, Flags @20, dwTtl @24,
        // dwReserved @28, Data @32 - which for a PTR is DNS_PTR_DATAW, one PWSTR.
        var record = first
        while (record != MemorySegment.NULL) {
            val view = record.reinterpret(DNS_RECORD_PTR_BYTES)
            if (view.get(I16, 16).toInt() == DNS_TYPE_PTR) each(wide(view.get(PTR, 32)), view.get(I32, 24))
            record = view.get(PTR, 0)
        }
    }

    // --------------------------------------------------------------------------- resolve

    /**
     * What the record named [fullName] says, or null if it does not answer in time or answers
     * without an IPv4 address.
     *
     * [DiscoveredPeer.name] is the instance's own label, without the service type after it, which
     * is the form the handset's discovery reports.
     */
    fun resolve(fullName: String, timeoutMillis: Long): DiscoveredPeer? {
        val context = nextContext.getAndIncrement()
        val done = CountDownLatch(1)
        var peer: DiscoveredPeer? = null
        waiters[context] = Completion { status, instance ->
            if (status == 0 && instance != MemorySegment.NULL) peer = readInstance(instance)
            done.countDown()
        }
        Arena.ofShared().use { arena ->
            // DNS_SERVICE_RESOLVE_REQUEST: Version @0, InterfaceIndex @4, QueryName @8,
            // pResolveCompletionCallback @16, pQueryContext @24.
            val request = arena.allocate(QUERY_REQUEST_BYTES)
            request.set(I32, 0, DNS_QUERY_REQUEST_VERSION1)
            request.set(PTR, 8, arena.allocateFrom(fullName, UTF_16LE))
            request.set(PTR, 16, RESOLVE_COMPLETE)
            request.set(PTR, 24, MemorySegment.ofAddress(context))
            val cancel = arena.allocate(PTR)
            try {
                val status = RESOLVE.invokeExact(request, cancel) as Int
                if (status != DNS_REQUEST_PENDING) return null
                if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) RESOLVE_CANCEL.invokeExact(cancel) as Int
            } finally {
                waiters.remove(context)
            }
        }
        return peer
    }

    private fun readInstance(pointer: MemorySegment): DiscoveredPeer? {
        // DNS_SERVICE_INSTANCE: pszInstanceName @0, pszHostName @8, ip4Address @16,
        // ip6Address @24, wPort @32, wPriority @34, wWeight @36, dwPropertyCount @40, keys @48,
        // values @56, dwInterfaceIndex @64.
        val instance = pointer.reinterpret(SERVICE_INSTANCE_BYTES)
        val ip4 = instance.get(PTR, 16)
        if (ip4 == MemorySegment.NULL) return null
        // IP4_ADDRESS is a DWORD in network order, so its bytes in memory are the dotted quad.
        val address = ip4.reinterpret(4).toArray(ValueLayout.JAVA_BYTE)
            .joinToString(".") { (it.toInt() and 0xff).toString() }
        val count = instance.get(I32, 40).toLong()
        val keys = instance.get(PTR, 48).reinterpret(count * PTR.byteSize())
        val values = instance.get(PTR, 56).reinterpret(count * PTR.byteSize())
        val attributes = (0 until count).associate { index ->
            wide(keys.getAtIndex(PTR, index)) to
                values.getAtIndex(PTR, index).takeIf { it != MemorySegment.NULL }?.let(::wide)
        }
        return DiscoveredPeer(
            name = wide(instance.get(PTR, 0)).substringBefore('.'),
            hostAddress = address,
            port = instance.get(I16, 32).toInt() and 0xffff,
            attributes = attributes
        )
    }

    private fun wide(pointer: MemorySegment): String =
        pointer.reinterpret(Long.MAX_VALUE).getString(0, UTF_16LE)

    /** DNS_REQUEST_PENDING from winerror.h: the answer will come through the callback. */
    private const val DNS_REQUEST_PENDING = 9506
    private const val DNS_QUERY_REQUEST_VERSION1 = 1
    private const val DNS_TYPE_PTR = 12

    /** DnsFreeRecordList, from DNS_FREE_TYPE. */
    private const val DNS_FREE_RECORD_LIST = 1

    private const val REGISTER_REQUEST_BYTES = 48L
    private const val QUERY_REQUEST_BYTES = 32L
    private const val DNS_RECORD_PTR_BYTES = 40L
    private const val SERVICE_INSTANCE_BYTES = 72L

    private const val COMPLETION_SECONDS = 5L
}
