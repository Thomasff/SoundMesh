package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.probe.sync.ClockPacket
import java.lang.foreign.Arena

/**
 * This machine following a host, out of its own speakers.
 *
 * The other half of [Host], and the half that makes the desktop a client rather than a source: it
 * dials a host's clock and audio, and plays what arrives at the instants the host asked for. The
 * host can be a handset or another copy of [Host] - nothing here knows which, because nothing on
 * the wire says.
 *
 *   sink [<host address>] [seconds] [chunkPort] [clockPort]
 *
 *   java --enable-native-access=ALL-UNNAMED -cp "<lib>" com.soundmesh.desktop.SinkKt 192.168.0.13 60
 *
 * With no address it looks for one the way a handset sink does, and refuses the same things: none
 * answering, none speaking this protocol, and two hosts, because joining whichever answered first
 * is joining a stranger's room on a shared network. The chunk port then comes from the record.
 *
 * **What a good run proves and what it does not.** It proves the session exists on this side: the
 * clock converges, chunks arrive, and they land on frames the device had not yet reached. It does
 * not prove this machine is in step with a handset in the same room, and cannot: the distance
 * between the frame the engine consumed and the moment the air moved is this machine's output
 * delay constant, nobody has measured it, and it is not visible from in here. Two ears in a room
 * are the instrument for that, and the number they would give is the acoustic cut this line is
 * parked on.
 */
fun main(args: Array<String>) {
    // The address is the one argument that is not a number, so it can be left out without the
    // numbers after it changing places - the way Host tells its file from its ports.
    val numbers = args.filter { it.toIntOrNull() != null }
    val seconds = numbers.getOrNull(0)?.toInt() ?: DEFAULT_SECONDS
    val clockPort = numbers.getOrNull(2)?.toInt() ?: ClockPacket.DEFAULT_PORT
    val typed = args.firstOrNull { it.toIntOrNull() == null }
    val (hostAddress, chunkPort) = if (typed != null) {
        typed to (numbers.getOrNull(1)?.toInt() ?: ChunkCodec.DEFAULT_PORT)
    } else {
        println("looking: for a host, ${DISCOVERY_WINDOW_MILLIS / 1000} s")
        val outcome = PeerDiscovery.discover(DISCOVERY_WINDOW_MILLIS)
        val peer = outcome.peer ?: run {
            println("no host: ${outcome.failure}, ${outcome.seen} answered, ${outcome.compatible} usable")
            for (host in outcome.hosts) println("         ${host.name} at ${host.hostAddress}:${host.port}")
            return
        }
        println("found  : ${peer.name} at ${peer.hostAddress}:${peer.port}")
        peer.hostAddress to peer.port
    }

    Arena.ofShared().use { arena ->
        val clock = DesktopClock.measure(arena)
        WasapiRenderer().use { renderer ->
            println("device : ${renderer.deviceName} at ${renderer.format.sampleRate} Hz, ${renderer.format.channels} ch")
            println("clock  : $clock")
            val sink = SinkStream(hostAddress, WasapiOutput(renderer, clock), chunkPort, clockPort)
            Runtime.getRuntime().addShutdownHook(Thread { sink.stop(); renderer.stop() })

            // The stream starts before the clock does, because start() does not return until the
            // engine is really consuming and a reading taken in that gap is worth nothing.
            renderer.start()
            sink.startClock()
            println("clock  : exchanging with $hostAddress:$clockPort, ${CLOCK_WAIT_SECONDS} s to settle")
            if (!sink.awaitClock(CLOCK_WAIT_SECONDS * 1000L)) {
                println("no clock - the host did not answer UDP $clockPort, so nothing was played")
                return
            }
            println("clock  : offset ${micros(sink.offsetNanos()!!)} ms")

            sink.dial()
            println("audio  : following $hostAddress:$chunkPort for ${seconds} s")
            report(sink, seconds)
            sink.stop()

            // Long enough for whatever is already on the timeline to be heard rather than cut off
            // mid-chunk, which would sound exactly like the fault this run is looking for.
            Thread.sleep(TAIL_MILLIS)
        }
    }
}

private fun report(sink: SinkStream, seconds: Int) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000L
    while (System.nanoTime() < deadline) {
        Thread.sleep(REPORT_MILLIS)
        val offset = sink.offsetNanos()?.let { micros(it) } ?: "none"
        println(
            "       : played ${sink.played}, late ${sink.droppedLate}, offset $offset ms\n" +
                "         seams ${sink.seams}, ${sink.seamBand()}\n" +
                "         split ${sink.seamShares()}"
        )
    }
}

private fun micros(nanos: Long): String = String.format("%.3f", nanos / 1_000_000.0)

private const val DEFAULT_SECONDS = 60
private const val CLOCK_WAIT_SECONDS = 30
private const val REPORT_MILLIS = 5000L
private const val TAIL_MILLIS = 500L

/** The handset sink's window, for the reason it gives: mDNS never says that was all of them. */
private const val DISCOVERY_WINDOW_MILLIS = 5_000
