package com.soundmesh.desktop

import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.ClockSyncClient
import com.soundmesh.probe.sync.ClockSyncServer
import java.lang.foreign.Arena

/**
 * The Windows client's half of the clock exchange, in both directions.
 *
 * The exchange itself, its wire format and its estimator are the handsets' own code, moved into
 * core and otherwise untouched. That is the point rather than a convenience: a second
 * implementation of a four-timestamp protocol is a second thing to keep correct, and the way it
 * fails is that both ends keep working while meaning slightly different things. Here there is one
 * set of bytes and one estimator, and if the two machines disagree the disagreement is real.
 *
 *   ask   <host> [port] [seconds] [intervalMs]   exchange with a handset that is answering
 *   serve [port]                    answer a handset that is asking
 *
 *   java --enable-native-access=ALL-UNNAMED -cp "<lib>" com.soundmesh.desktop.ClockPeerKt ask 192.168.1.20
 *
 * What comes back is an offset between this machine's `System.nanoTime()` and the handset's. The
 * audio half of this client does not speak that clock, so the offset is also printed in QPC ticks
 * through [DesktopClock] - which is the conversion the whole cross-machine measurement rests on,
 * and the reason it is measured at startup rather than assumed.
 *
 * The round trips matter as much as the offset. A single exchange pins the offset only to an
 * interval as wide as its round trip, so the spread below is what says whether this link can
 * support a millisecond at all, before any sound is played.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "ask"
    Arena.ofConfined().use { arena ->
        val clock = DesktopClock.measure(arena)
        println("clock  : $clock")

        when (mode) {
            "serve" -> {
                val port = if (args.size > 1) args[1].toInt() else ClockPacket.DEFAULT_PORT
                val server = ClockSyncServer(port)
                server.start()
                println("serving: answering clock requests on UDP $port - Ctrl-C to stop")
                Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
                // Reporting who has asked recently is the only sign of life this side has; the
                // exchange is stateless and a server that nobody is asking looks exactly like one
                // that is working.
                while (true) {
                    Thread.sleep(REPORT_MILLIS)
                    val heard = server.heardFrom()
                    println(
                        if (heard.isEmpty()) "  nobody has asked yet"
                        else "  asking: " + heard.entries.joinToString(", ") {
                            "${it.key} (${(System.currentTimeMillis() - it.value) / 1000} s ago)"
                        }
                    )
                }
            }

            "ask" -> {
                val host = args.getOrNull(1) ?: error("usage: ask <host> [port] [seconds] [intervalMs]")
                val port = if (args.size > 2) args[2].toInt() else ClockPacket.DEFAULT_PORT
                val seconds = if (args.size > 3) args[3].toInt() else 20
                // Settable because the estimator keeps a fixed fraction of its window
                // while that window is filling: at the handsets' two-second cadence a
                // sixty-four deep window is two minutes away, and until it fills the
                // answer rests on one or two exchanges however many were made.
                val intervalMillis = if (args.size > 4) args[4].toLong() else 2000L
                val estimator = ClockOffsetEstimator()
                val client = ClockSyncClient(host, port, estimator)
                println("asking : $host:$port for $seconds s")
                println()

                // Burst first, for the reason the handsets do it: the estimator answers nothing
                // until its window has enough in it, and at the session cadence that is fourteen
                // seconds of waiting rather than computing.
                val history = client.runFor(seconds, intervalMillis, burstExchanges = 8, burstIntervalMillis = 250)
                val exchanges = client.recordedExchanges()
                if (exchanges.isEmpty()) {
                    println("no reply. Is the handset answering on UDP $port, and on this network?")
                    return
                }

                val trips = exchanges.map { it.roundTripNanos }.sorted()
                println(
                    "trips  : ${exchanges.size} exchanges, shortest ${micros(trips.first())}, " +
                        "median ${micros(trips[trips.size / 2])}, longest ${micros(trips.last())}"
                )
                val estimate = client.currentEstimate()
                if (estimate == null) {
                    println("estimator: nothing it will stand behind - too few exchanges survived")
                    return
                }
                println(
                    "offset : ${micros(estimate.offsetNanos)} of the handset's clock ahead of " +
                        "this one, +-${micros(estimate.uncertaintyNanos)}, from " +
                        "${estimate.sampleCount} kept exchanges"
                )
                println("drift  : ${"%+.1f".format(estimate.driftPpm)} ppm")

                // The two legs on their own, which the round trip adds together and the offset
                // hides. The midpoint estimator is exactly right when the legs are equal and wrong
                // by half their difference when they are not, so this is the line that says how
                // much of an answer is path rather than clock.
                //
                // Each leg spans both clocks, so the offset has to come out before they mean
                // anything. Against a second process on this machine it is zero and the raw
                // difference works; against a handset it is days, and the first run of this
                // printed legs of +337559882 ms and -337559880 ms. The offset removed here is the
                // estimator's own, so this cannot prove the estimator right - what it shows is the
                // shape the estimator is resting on.
                val offset = estimate.offsetNanos
                val out = exchanges.map { it.t2 - it.t1 - offset }.sorted()
                val back = exchanges.map { it.t4 - it.t3 + offset }.sorted()
                println(
                    "legs   : out ${micros(out[out.size / 2])} median / ${micros(out.first())} " +
                        "shortest, back ${micros(back[back.size / 2])} / ${micros(back.first())}"
                )
                // The same number the audio half has to use. Reported here so that the conversion
                // is on the page next to the thing it converts, and not left to be done later by
                // whoever wires the two together.
                println(
                    "in QPC : the handset's clock reads " +
                        "${clock.qpcAt(estimate.offsetNanos) - clock.qpcAt(0)} ticks ahead"
                )
                println()
                println(
                    "history: ${history.size} estimates, " +
                        "spread ${micros(
                            (history.maxOfOrNull { it.offsetNanos } ?: 0) -
                                (history.minOfOrNull { it.offsetNanos } ?: 0)
                        )} across the run"
                )
            }

            else -> error("usage: ask <host> [port] [seconds] [intervalMs] | serve [port]")
        }
    }
}

private fun micros(nanos: Long): String = "%.3f ms".format(nanos / 1e6)

private const val REPORT_MILLIS = 5_000L
