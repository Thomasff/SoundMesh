package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.ClockSyncServer
import java.lang.foreign.Arena
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
 * Say `play` and it comes out of this machine's speakers too, on the same instants it sent.
 * Off by default, because a run that only has to prove the wire should not also need an output
 * device to open. What playing does NOT establish is that this machine is in step with a
 * handset: both ends turn an instant into a frame, neither knows how far its own speaker sits
 * behind that frame, and the two unmeasured constants do not cancel. That is the acoustic
 * question this line is parked on. Being able to hear both at once is still worth having - a
 * room either hears one sound or two, and two is tens of milliseconds.
 *
 *   host [seconds] [chunkPort] [clockPort] [play]
 *
 *   java --enable-native-access=ALL-UNNAMED -cp "<lib>" com.soundmesh.desktop.HostKt 60 play
 *
 * Point a handset at the address printed below. On the probe build that is the sink role with
 * `host_address` set; discovery is not answered here, because mDNS on this side is its own piece
 * of work and typing an address is not what is being proved.
 */
fun main(args: Array<String>) {
    // Positional for the numbers and a word for the switch, the way Listen takes `loopback`:
    // a fourth position nobody can remember the order of is worse than a word in the log.
    val numbers = args.filter { it.toIntOrNull() != null }
    val seconds = numbers.getOrNull(0)?.toInt() ?: DEFAULT_SECONDS
    val chunkPort = numbers.getOrNull(1)?.toInt() ?: ChunkCodec.DEFAULT_PORT
    val clockPort = numbers.getOrNull(2)?.toInt() ?: ClockPacket.DEFAULT_PORT
    val play = args.contains("play")

    Arena.ofShared().use { arena ->
        val renderer = if (play) WasapiRenderer() else null
        val output = renderer?.let { WasapiOutput(it, DesktopClock.measure(arena)) }
        renderer?.let { println("device : ${it.deviceName} at ${it.format.sampleRate} Hz, ${it.format.channels} ch") }
        try {
            run(seconds, chunkPort, clockPort, output, renderer)
        } finally {
            renderer?.close()
        }
    }
}

private fun run(
    seconds: Int,
    chunkPort: Int,
    clockPort: Int,
    output: FrameOutput?,
    renderer: WasapiRenderer?
) {
    val clockServer = ClockSyncServer(clockPort)
    val stream = HostStream(chunkPort, localOutput = output)
    Runtime.getRuntime().addShutdownHook(Thread { stream.stop(); clockServer.stop() })

    // Before anything is scheduled on it: start() does not return until the engine is really
    // consuming, and a frame worked out from a reading taken in that gap is worth nothing.
    renderer?.start()

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
    if (output != null) {
        println(
            "local  : played ${stream.playedLocally()}, seams ${stream.localSeams()}, " +
                stream.localSeamBand()
        )
        // Long enough for what is already on the timeline to be heard rather than cut off
        // mid-chunk, which sounds exactly like the fault a run like this is looking for.
        Thread.sleep(TAIL_MILLIS)
    }

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
private const val TAIL_MILLIS = 2000L
