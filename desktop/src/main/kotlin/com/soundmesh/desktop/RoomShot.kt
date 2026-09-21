package com.soundmesh.desktop

import com.soundmesh.core.CalibrationSchedule
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.RoomGrid
import com.soundmesh.probe.WavFileWriter
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.ClockSyncClient
import java.io.File
import java.lang.foreign.Arena
import kotlin.math.abs

/**
 * Puts chirps into the room at instants a handset's clock will recognise, and records both sides.
 *
 * This is the half of the acoustic measurement that lives on the PC. The handset, in the probe's
 * `ROOM` mode, is recording the room and has put chirps of its own on a grid instant of its own
 * clock; this emits [RoomGrid.PEER_SLOT_NANOS] after each one, so both land in one recording with
 * the room unchanged between them. [RoomGrid.REPEATS] of them, because a single exchange puts the
 * handset's per-emission latency step straight into the answer - see [RoomGrid.REPEATS] for what
 * that was measured at and why this side cannot cancel it.
 *
 *   java --enable-native-access=ALL-UNNAMED -cp "<lib>" com.soundmesh.desktop.RoomShotKt \
 *       <handset-ip> <gridHostNanos> <out.wav> [exchangeSeconds] [intervalMillis] [port]
 *
 * `gridHostNanos` comes out of the `grid.txt` the handset writes the moment it has chosen the
 * instant, which is the only moment early enough to be useful. It is an argument rather than
 * something derived here on purpose: derived, the two sides could land either side of a grid
 * boundary and play ten seconds apart, and the failure would look like a recording with one chirp
 * in it. A stale value from an earlier run reads as an instant in the past and the lead check
 * below refuses it.
 *
 * **Why this machine records as well.** Read from the handset's recording alone, the answer holds
 * the whole distance between the two machines, and taking that out means measuring it - a tape
 * measure, and a premise about where the speaker sits that a run at 115 cm falsified. With both
 * machines recording both chirps, everything that scales with that distance leaves the answer and
 * what replaces it is fixed by the two cases rather than by the room: see [RoomPairKt] for the
 * arithmetic and for what does not cancel. Nothing about the handset's side changes; this only
 * adds the second recording.
 *
 * The capture stream is opened RAW, which is not an optimisation. On this endpoint the vendor's
 * processing chain passes what it judges to be speech and writes exact zeros for everything else,
 * so with it in the way a chirp across the room reads as digital silence.
 *
 * **What this prints and what it is for.** The instant the engine consumed the chirp's first
 * frame - not when the air moved. The gap between those two is the output delay constant, it is a
 * property of this machine and its endpoint, and nothing on this side of the speaker can measure
 * it. That is the whole reason a handset is in the room.
 *
 * **The offset's own bias is not removed and cannot be.** Two-way time transfer is biased by half
 * the asymmetry between its legs, which against a handset over WiFi runs to milliseconds and
 * grows with how idle the answering side is. So the exchange here runs at a fast cadence to keep
 * the handset awake. The distance cancels in the symmetric reading; this does not.
 */
fun main(args: Array<String>) {
    val host = args.getOrNull(0)
        ?: error("usage: <handset-ip> <gridHostNanos> <out.wav> [exchangeSeconds] [intervalMillis] [port]")
    val grid = args.getOrNull(1)?.toLong() ?: error("the grid instant off the handset's logcat is required")
    val outPath = args.getOrNull(2) ?: error("a path to write this machine's own recording to is required")
    val exchangeSeconds = args.getOrNull(3)?.toInt() ?: 12
    val intervalMillis = args.getOrNull(4)?.toLong() ?: 100L
    val port = args.getOrNull(5)?.toInt() ?: ClockPacket.DEFAULT_PORT

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
        // Both machines lay the window out from the one instant they share, through the same
        // function - see RoomGrid.planFor. Written out separately here the two would agree today
        // and part on the first change to either, with both sides working and neither meeting.
        val timing = CalibrationSchedule.of(
            RoomGrid.planFor(CASE, grid), PC_SLOT, chirpNanos(ChirpGenerator.generateMono().size)
        )
        val targets = timing.ownChirpAtHostNanos
        val target = targets.first()
        // Printed because it is the run's own witness that both chirps went into one recording.
        // The handset's is on the grid and this one a second and a half later; anything else means
        // the two sides were not talking about the same grid point.
        println(
            "grid   : handset chirps at $grid, this one at $target, " +
                "${targets.size} repeats ${millis(RoomGrid.REPEAT_NANOS)} apart " +
                "(${millis(grid - (System.nanoTime() + offset))} from now to the handset's)"
        )

        val targetLocal = target - offset
        val lead = targetLocal - System.nanoTime()
        check(lead > MINIMUM_LEAD_NANOS) {
            "only ${millis(lead)} left before the slot - too late to schedule. Start this sooner " +
                "after the handset, or shorten the exchange."
        }

        WasapiRenderer().use { renderer ->
            WasapiCapture(raw = true).use { capture ->
                println("render : ${renderer.deviceName ?: "(unnamed)"} - ${renderer.format}")
                println("volume : ${say(renderer.volume)}")
                println("capture: ${capture.deviceName ?: "(unnamed)"} - ${capture.format}, RAW")
                println("       : ${say(capture.volume)}")
                // The chirp is core's own, generated at its sample rate and correlated against a
                // recording sample for sample. An endpoint at another rate would be putting out a
                // different signal from the one the analysis looks for - and on the capture side,
                // reading one.
                check(renderer.format.sampleRate == ChirpGenerator.SAMPLE_RATE) {
                    "the render endpoint is at ${renderer.format.sampleRate} Hz and the chirp is at " +
                        "${ChirpGenerator.SAMPLE_RATE} Hz"
                }
                check(capture.format.sampleRate == ChirpGenerator.SAMPLE_RATE) {
                    "the capture endpoint is at ${capture.format.sampleRate} Hz and the chirp is at " +
                        "${ChirpGenerator.SAMPLE_RATE} Hz"
                }
                val chirp = ChirpGenerator.generateMono()

                Wasapi.timeBeginPeriod(1)
                try {
                    renderer.start()
                    val anchor = renderer.sampleClock()
                    // Every repeat is scheduled now, off one clock reading, rather than each off a
                    // fresh one as its turn comes: a reading taken later is a reading taken while
                    // the stream is running, and the difference between two of them is the thing
                    // this measurement is made of. One anchor for the window keeps the repeats
                    // comparable with each other whatever that anchor's own error is.
                    val frames = targets.map { renderer.frameAt(clock.qpcAt(it - offset), anchor) }
                    frames.forEach { renderer.schedule(chirp, it) }
                    val frame = frames.first()
                    println(
                        "waiting: ${frames.size} chirps from frame $frame, " +
                            "${millis(targetLocal - System.nanoTime())} away"
                    )

                    // Started late on purpose, and timed off the handset's instant rather than off
                    // whenever the exchange happened to finish. The capture stream loses frames
                    // while it settles and is clean afterwards - measured at six jumps inside the
                    // first 1.27 s and none after - so what this has to guarantee is that the
                    // settling is over before the first chirp, not that the file is long.
                    val captureAt = targetLocal - RoomGrid.PEER_SLOT_NANOS - CAPTURE_SETTLE_NANOS
                    val settle = captureAt - System.nanoTime()
                    if (settle < 0) println("note   : capture is starting ${millis(-settle)} late for its settling")
                    while (System.nanoTime() < captureAt) Thread.sleep(20)
                    capture.start()

                    val until = renderer.qpcAt(frames.last() + chirp.size, anchor) +
                        (renderer.qpcFrequency * TAIL_SECONDS).toLong()
                    while (renderer.now() < until) Thread.sleep(20)

                    // A second reading after seconds of running, for the reason experiment six
                    // found: a reading taken in the first milliseconds of a stream is silently
                    // wrong by milliseconds, and the fix it got is known to be necessary, not
                    // sufficient.
                    val late = renderer.sampleClock()
                    capture.stop()
                    renderer.stop()

                    // The answer this program exists to produce, both ways round: the instant the
                    // engine consumed the chirp's first frame, read off the anchor the schedule was
                    // built on and again off a clock sampled at the end. The two agreeing is what says
                    // the stream did not slip underneath the schedule.
                    val consumed = frames.map { clock.nanosAt(renderer.qpcAt(it, anchor)) + offset }
                    val consumedEarly = consumed.first()
                    val consumedLate = clock.nanosAt(renderer.qpcAt(frame, late)) + offset

                    val recording = capture.take()
                    val file = File(outPath)
                    WavFileWriter(file, recording.format.sampleRate, 1).use { writer ->
                        val bytes = ByteArray(recording.mono.size * 2)
                        recording.mono.forEachIndexed { index, sample ->
                            bytes[index * 2] = (sample.toInt() and 0xFF).toByte()
                            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
                        }
                        writer.writePcm(bytes, bytes.size)
                    }
                    // Where the schedule says this machine's own chirp left the engine, as a
                    // sample number in the file just written. The arrival is later than this by
                    // the capture chain's own latency, which nothing here knows - so it is a
                    // search hint for RoomPair and never a measurement.
                    val renderIndices = frames.map { recording.indexAt(renderer.qpcAt(it, anchor)) }
                    val renderIndex = renderIndices.first()
                    val peak = recording.mono.maxOfOrNull { abs(it.toInt()) } ?: 0

                    println()
                    println(
                        "heard  : ${recording.mono.size} samples " +
                            "(${"%.2f".format(recording.mono.size.toDouble() / recording.format.sampleRate)} s), " +
                            "peak $peak of ${Short.MAX_VALUE}, " +
                            "channels ${recording.channelPeaks.joinToString(" / ") { "%.0f".format(it) }}"
                    )
                    if (peak < AUDIBLE_PEAK) {
                        println("       : that is not a room. Check the capture endpoint before reading anything.")
                    }
                    println("wrote  : ${file.absolutePath}")
                    println()
                    println("asked  : $target")
                    println("consumed at (handset clock): $consumedEarly")
                    println("  same, off the late clock : $consumedLate " +
                        "(${millis(consumedLate - consumedEarly)} apart)")
                    println("render index in this file  : $renderIndex")
                    println("repeats: " + consumed.indices.joinToString(", ") {
                        "#$it ${millis(consumed[it] - targets[it])} off schedule"
                    })

                    // A file rather than more numbers on the next command line. One repeat needed
                    // two of them copied by hand through a shell script; eight needs sixteen, and
                    // a measurement whose inputs are transcribed is a measurement with a transcription
                    // in it. Every one of these is an instant this program alone observed.
                    val shot = File(file.parentFile, file.nameWithoutExtension + SHOT_SUFFIX)
                    shot.writeText(
                        listOf(
                            "wav=${file.name}",
                            "grid=$grid",
                            "repeatNanos=${RoomGrid.REPEAT_NANOS}",
                            "consumed=${consumed.joinToString(",")}",
                            "renderIndex=${renderIndices.joinToString(",")}"
                        ).joinToString("\n") + "\n"
                    )
                    println("wrote  : ${shot.absolutePath}")
                    println()
                    println("RoomPair <handset.wav> ${shot.name} <recordingStartedAtHostNanos>")
                } finally {
                    Wasapi.timeEndPeriod(1)
                }
            }
        }
    }
}

/** The chirp's own length in nanoseconds, which the schedule needs to know to leave room for it. */
private fun chirpNanos(samples: Int): Long = samples.toLong() * 1_000_000_000L / ChirpGenerator.SAMPLE_RATE

/** This machine chirps second, which is slot one of the plan both machines lay out. */
private const val PC_SLOT = 1

/** Names the plan rather than a run: nothing here stores anything under a case id. */
private const val CASE = "room"

/** What the sidecar beside the recording is called, and what RoomPair is handed instead of numbers. */
const val SHOT_SUFFIX = "-shot.txt"

private fun millis(nanos: Long): String = "%.3f ms".format(nanos / 1e6)

private fun say(volume: Pair<Float, Boolean>?): String =
    volume?.let { "${"%.0f".format(it.first * 100)}%${if (it.second) ", muted" else ""}" } ?: "(unknown)"

/**
 * Enough to open a stream, read its clock, get a frame in ahead of the engine, and still leave the
 * capture stream its settling time before the handset's chirp - which lands
 * [RoomGrid.PEER_SLOT_NANOS] before this machine's.
 */
private const val MINIMUM_LEAD_NANOS = 5_000_000_000L

/** How long the capture stream is given to settle before the first chirp is due. */
private const val CAPTURE_SETTLE_NANOS = 2_000_000_000L

private const val TAIL_SECONDS = 1.0

/** Below this the recording is not a quiet room, it is a capture path that is not working. */
private const val AUDIBLE_PEAK = 50
