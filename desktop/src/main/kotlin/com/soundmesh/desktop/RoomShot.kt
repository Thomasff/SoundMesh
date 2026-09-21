package com.soundmesh.desktop

import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.RoomGrid
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.ClockSyncClient
import java.lang.foreign.Arena

/**
 * Puts one chirp into the room at an instant a handset's clock will recognise.
 *
 * This is the half of the acoustic measurement that lives on the PC. The handset, in the probe's
 * `ROOM` mode, is recording the room and has put a chirp of its own on a grid instant of its own
 * clock; this emits [RoomGrid.PEER_SLOT_NANOS] after the same one, so both land in one recording
 * with the room unchanged between them.
 *
 *   java --enable-native-access=ALL-UNNAMED -cp "<lib>" com.soundmesh.desktop.RoomShotKt \
 *       <handset-ip> <gridHostNanos> [exchangeSeconds] [intervalMillis] [port]
 *
 * `gridHostNanos` comes off the handset's logcat - the run logs it as soon as it has chosen it,
 * which is the only moment early enough to be useful. It is an argument rather than something
 * derived here on purpose: derived, the two sides could land either side of a grid boundary and
 * play ten seconds apart, and the failure would look like a recording with one chirp in it.
 *
 * **What this prints and what it is for.** The last line is the host instant the engine consumed
 * the chirp's first frame - not when the air moved. The gap between those two is the output delay
 * constant, it is a property of this machine and its endpoint, and nothing on this side of the
 * speaker can measure it. That is the whole reason a handset is in the room.
 *
 * **The offset's own bias is not removed and cannot be.** Two-way time transfer is biased by half
 * the asymmetry between its legs, which against a handset over WiFi runs to milliseconds and
 * grows with how idle the answering side is. So the exchange here runs at a fast cadence to keep
 * the handset awake, and the measurement this feeds is read as a *difference* between two
 * placements, where a bias that holds still across the two cancels.
 */
fun main(args: Array<String>) {
    val host = args.getOrNull(0)
        ?: error("usage: <handset-ip> <gridHostNanos> [exchangeSeconds] [intervalMillis] [port]")
    val grid = args.getOrNull(1)?.toLong() ?: error("the grid instant off the handset's logcat is required")
    val exchangeSeconds = args.getOrNull(2)?.toInt() ?: 14
    val intervalMillis = args.getOrNull(3)?.toLong() ?: 100L
    val port = args.getOrNull(4)?.toInt() ?: ClockPacket.DEFAULT_PORT

    Arena.ofConfined().use { arena ->
        val clock = DesktopClock.measure(arena)
        println("clock  : $clock")

        val estimator = ClockOffsetEstimator()
        val client = ClockSyncClient(host, port, estimator)
        println("asking : $host:$port for $exchangeSeconds s at ${intervalMillis} ms")
        client.runFor(exchangeSeconds, intervalMillis, burstExchanges = 8, burstIntervalMillis = 100)
        val exchanges = client.recordedExchanges()
        if (exchanges.isEmpty()) {
            println("no reply. Is the handset in ROOM mode and on this network?")
            return
        }
        val estimate = client.currentEstimate()
        if (estimate == null) {
            println("estimator: nothing it will stand behind - too few exchanges survived")
            return
        }
        val trips = exchanges.map { it.roundTripNanos }.sorted()
        println(
            "trips  : ${exchanges.size} exchanges, shortest ${millis(trips.first())}, " +
                "median ${millis(trips[trips.size / 2])}"
        )
        val offset = estimate.offsetNanos
        println(
            "offset : ${millis(offset)} of the handset's clock ahead of this one, " +
                "+-${millis(estimate.uncertaintyNanos)}, from ${estimate.sampleCount} kept exchanges"
        )
        // Printed because it is the run's own witness that both chirps went into one recording.
        // The handset's is on the grid and this one a second and a half later; anything else means
        // the two sides were not talking about the same grid point.
        val target = grid + RoomGrid.PEER_SLOT_NANOS
        println(
            "grid   : handset chirps at $grid, this one at $target " +
                "(${millis(grid - (System.nanoTime() + offset))} from now to the handset's)"
        )

        val targetLocal = target - offset
        val lead = targetLocal - System.nanoTime()
        check(lead > MINIMUM_LEAD_NANOS) {
            "only ${millis(lead)} left before the slot - too late to schedule. Start this sooner " +
                "after the handset, or shorten the exchange."
        }

        WasapiRenderer().use { renderer ->
            println("render : ${renderer.deviceName ?: "(unnamed)"} - ${renderer.format}")
            println("volume : ${say(renderer.volume)}")
            // The chirp is core's own, generated at its sample rate and correlated against a
            // recording sample for sample. An endpoint at another rate would be putting out a
            // different signal from the one the analysis looks for.
            check(renderer.format.sampleRate == ChirpGenerator.SAMPLE_RATE) {
                "the render endpoint is at ${renderer.format.sampleRate} Hz and the chirp is at " +
                    "${ChirpGenerator.SAMPLE_RATE} Hz"
            }
            val chirp = ChirpGenerator.generateMono()

            Wasapi.timeBeginPeriod(1)
            try {
                renderer.start()
                val anchor = renderer.sampleClock()
                val frame = renderer.frameAt(clock.qpcAt(targetLocal), anchor)
                renderer.schedule(chirp, frame)
                println("waiting: chirp on frame $frame, ${millis(targetLocal - System.nanoTime())} away")

                val until = renderer.qpcAt(frame + chirp.size, anchor) +
                    (renderer.qpcFrequency * TAIL_SECONDS).toLong()
                while (renderer.now() < until) Thread.sleep(20)

                // A second reading after seconds of running, for the reason experiment six found:
                // a reading taken in the first milliseconds of a stream is silently wrong by
                // milliseconds, and the fix it got is known to be necessary, not sufficient.
                val late = renderer.sampleClock()
                renderer.stop()

                // The answer this program exists to produce, both ways round: the instant the
                // engine consumed the chirp's first frame, read off the anchor the schedule was
                // built on and again off a clock sampled at the end. The two agreeing is what says
                // the stream did not slip underneath the schedule.
                val consumedEarly = clock.nanosAt(renderer.qpcAt(frame, anchor)) + offset
                val consumedLate = clock.nanosAt(renderer.qpcAt(frame, late)) + offset
                println()
                println("asked  : $target")
                println("consumed at (handset clock): $consumedEarly")
                println("  same, off the late clock : $consumedLate " +
                    "(${millis(consumedLate - consumedEarly)} apart)")
                println()
                println("Hand the first of those to RoomRead along with the handset's own instant.")
            } finally {
                Wasapi.timeEndPeriod(1)
            }
        }
    }
}

private fun millis(nanos: Long): String = "%.3f ms".format(nanos / 1e6)

private fun say(volume: Pair<Float, Boolean>?): String =
    volume?.let { "${"%.0f".format(it.first * 100)}%${if (it.second) ", muted" else ""}" } ?: "(unknown)"

/** Enough to open a stream, read its clock and get a frame in ahead of the engine. */
private const val MINIMUM_LEAD_NANOS = 3_000_000_000L
private const val TAIL_SECONDS = 1.0
