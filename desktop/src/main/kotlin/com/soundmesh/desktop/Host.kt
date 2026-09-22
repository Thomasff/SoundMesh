package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.ClockSyncServer
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * This machine as a host: it answers the clock and it sends the audio, and a handset plays it.
 *
 * Everything before this in the desktop module measures something. This is the first thing here
 * that is a session - the two servers a sink dials, started together and left running - and it is
 * deliberately the smallest one that can be true. It streams the generated tone core already has,
 * because what is being shown is that a handset across the room plays what this machine sent it;
 * which file the bytes came from is a separate question and does not change the answer.
 *
 * It does not play anything itself. A host that streams and a host that also plays are two
 * claims, and the second one needs the renderer and an answer to where its own output sits in
 * time - which is the acoustic question this line is parked on, not a wiring one.
 *
 *   host [seconds] [chunkPort] [clockPort]
 *
 *   java --enable-native-access=ALL-UNNAMED -cp "<lib>" com.soundmesh.desktop.HostKt 60
 *
 * Point a handset at the address printed below. On the probe build that is the sink role with
 * `host_address` set; discovery is not answered here, because mDNS on this side is its own piece
 * of work and typing an address is not what is being proved.
 */
fun main(args: Array<String>) {
    val seconds = args.getOrNull(0)?.toInt() ?: DEFAULT_SECONDS
    val chunkPort = args.getOrNull(1)?.toInt() ?: ChunkCodec.DEFAULT_PORT
    val clockPort = args.getOrNull(2)?.toInt() ?: ClockPacket.DEFAULT_PORT

    val clockServer = ClockSyncServer(clockPort)
    val stream = HostStream(chunkPort)
    Runtime.getRuntime().addShutdownHook(Thread { stream.stop(); clockServer.stop() })

    clockServer.start()
    stream.start()
    println("clock  : answering on UDP $clockPort")
    println("audio  : serving on TCP $chunkPort")
    for (address in lanAddresses()) println("address: $address")

    // Waited for rather than streamed past. The handset host does not wait, and does not need to:
    // there the two ends are started by the same run. Here somebody starts this and then walks to
    // a phone, and a run that streamed its whole length to nobody would end with every counter
    // reading zero and nothing saying why.
    println("waiting: for a handset to connect, ${WAIT_SECONDS} s")
    if (!awaitSink(stream, WAIT_SECONDS)) {
        println("nothing connected - no audio was sent")
        stream.stop()
        clockServer.stop()
        return
    }

    val chunks = (seconds * 1_000_000_000L / HostStream.CHUNK_NANOS).toInt()
    println("playing: $chunks chunks, ${seconds} s, to ${stream.sinkCount()} sink(s)")
    stream.stream(chunks)
    println("done   : ${stream.droppedChunks()} chunk(s) dropped, ${stream.sinkCount()} sink(s) still connected")

    stream.stop()
    clockServer.stop()
}

private fun awaitSink(stream: HostStream, seconds: Int): Boolean {
    val deadline = System.nanoTime() + seconds * 1_000_000_000L
    while (System.nanoTime() < deadline) {
        if (stream.sinkCount() > 0) return true
        Thread.sleep(POLL_MILLIS)
    }
    return false
}

/**
 * The IPv4 addresses a handset could dial, in no particular order.
 *
 * Printed rather than chosen: this machine can be on a wired network and a hotspot at once, and
 * which of them the phone is on is not something this side can tell. Loopback is left out because
 * a handset cannot reach it; nothing else is filtered, because a guess about which interface is
 * "the" one is the kind that is right until the day somebody plugs in a cable.
 */
private fun lanAddresses(): List<String> =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nic -> nic.inetAddresses.asSequence().map { nic.displayName to it } }
        .filter { (_, address) -> address is Inet4Address }
        .map { (name, address) -> "${address.hostAddress}  ($name)" }
        .toList()

private const val DEFAULT_SECONDS = 60
private const val WAIT_SECONDS = 60
private const val POLL_MILLIS = 100L
