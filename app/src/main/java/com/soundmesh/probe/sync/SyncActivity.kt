package com.soundmesh.probe.sync

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.DriftController
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.TonePcmSource
import com.soundmesh.probe.ProbeCase
import com.soundmesh.probe.RunStore
import java.util.concurrent.atomic.AtomicLong

/**
 * ADB driven harness for the M1 and M2 gates. Not product code: the product will discover
 * peers and manage sessions itself, while this takes both from intent extras.
 *
 * `mode=CLOCK_ONLY` runs just the UDP clock exchange (the M1 gate). `mode=FULL` additionally
 * streams a shared audio timeline over TCP and closes with a calibration chirp (the M2 gate).
 */
class SyncActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var runStore: RunStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this)
        setContentView(statusView)
        runStore = RunStore(filesDir)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handle()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle()
    }

    private fun handle() {
        val caseId = intent.getStringExtra(ProbeCase.EXTRA_CASE_ID)
        val role = intent.getStringExtra("role")
        val seconds = intent.getIntExtra("seconds", -1)
        val mode = intent.getStringExtra("mode")
        if (caseId == null || !ProbeCase.isSafeCaseId(caseId) || role !in setOf("HOST", "SINK") ||
            seconds !in 10..900 || mode == null || mode !in setOf("CLOCK_ONLY", "FULL")
        ) {
            statusView.text = "REJECTED"
            return
        }
        statusView.text = "$role RUNNING"
        Thread {
            // Both branches write their own sync.json on success (CLOCK_ONLY inline below, FULL
            // inside runHostFull/runSinkFull); this thread only needs to cover failures.
            val failure = runCatching {
                if (role == "HOST") runHost(mode, caseId, seconds) else runSink(mode, caseId, seconds)
            }.exceptionOrNull()?.javaClass?.simpleName
            if (failure != null) {
                runStore.writeSyncJson(caseId, "{\"schemaVersion\":1,\"role\":\"$role\",\"failureCode\":\"$failure\"}")
            }
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                statusView.text = "$role DONE"
            }
        }.start()
    }

    private fun runHost(mode: String, caseId: String, seconds: Int) {
        if (mode == "CLOCK_ONLY") {
            val server = ClockSyncServer(CLOCK_PORT)
            server.start()
            Thread.sleep(seconds * 1000L)
            server.stop()
            runStore.writeSyncJson(caseId, "{\"schemaVersion\":1,\"role\":\"HOST\",\"failureCode\":null}")
        } else {
            runHostFull(caseId, seconds)
        }
    }

    private fun runSink(mode: String, caseId: String, seconds: Int) {
        val address = intent.getStringExtra("host_address")
            ?: throw IllegalArgumentException("SINK needs host_address")
        if (mode == "CLOCK_ONLY") {
            val client = ClockSyncClient(address, CLOCK_PORT, ClockOffsetEstimator())
            val history = client.runFor(seconds)
            runStore.writeSyncJson(
                caseId,
                "{\"schemaVersion\":1,\"role\":\"SINK\",\"failureCode\":null,\"estimates\":${estimatesJson(history)}}"
            )
        } else {
            runSinkFull(address, caseId, seconds)
        }
    }

    // ---- FULL mode ----
    //
    // Host is the clock and the source of truth: it answers UDP clock requests, streams
    // TonePcmSource chunks over TCP timestamped 1.5s into its own future, and plays that same
    // audio locally off its own scheduler. The sink turns its measured offset into a
    // hostNanosNow() and feeds every chunk it receives to an identical scheduler/renderer pair,
    // so both devices are pulling from the same shared timeline, just converted differently.
    //
    // After the audio segment, the sink plays the calibration chirp at a host instant and the
    // host plays the same chirp 500ms later while recording the room throughout, so a single
    // WAV on the host carries both chirps for a PC script to cross-correlate.

    private fun runHostFull(caseId: String, seconds: Int) {
        val clockServer = ClockSyncServer(CLOCK_PORT)
        val chunkServer = ChunkServer(CHUNK_PORT)
        val scheduler = PlaybackScheduler(SyncRenderer.FRAMES_PER_CHUNK, SCHEDULER_CAPACITY_CHUNKS)
        val renderer = SyncRenderer(scheduler, DriftController()) { System.nanoTime() }
        try {
            clockServer.start()
            chunkServer.start()

            val source = TonePcmSource()
            val audioStart = System.nanoTime()
            val until = audioStart + seconds * 1_000_000_000L
            var lastPlayAtHostNanos = until

            val rendererThread = Thread { renderer.run(until + CALIBRATION_GAP_NANOS) }
            rendererThread.start()

            var sequence = 0
            var frameIndex = 0L
            while (System.nanoTime() < until) {
                val pcm = source.fill(frameIndex, SyncRenderer.FRAMES_PER_CHUNK)
                val playAt = System.nanoTime() + LEAD_NANOS
                val chunk = AudioChunk(sequence, playAt, pcm)
                lastPlayAtHostNanos = playAt
                chunkServer.broadcast(chunk)
                scheduler.submit(chunk)
                sequence++
                frameIndex += SyncRenderer.FRAMES_PER_CHUNK
                val nextAt = audioStart + frameIndex * 1_000_000_000L / SyncRenderer.SAMPLE_RATE
                val sleepNanos = nextAt - System.nanoTime()
                if (sleepNanos > 0) Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
            }
            rendererThread.join()

            val calibration = CalibrationRunner(runStore, caseId)
            val hostChirpAt = lastPlayAtHostNanos + CALIBRATION_GAP_NANOS + STAGGER_NANOS
            val recordThread = Thread { calibration.record(recordSecondsFor(hostChirpAt)) }
            recordThread.start()
            calibration.playChirpAt(hostChirpAt) { System.nanoTime() }
            recordThread.join()

            runStore.writeSyncJson(
                caseId,
                "{\"schemaVersion\":1,\"role\":\"HOST\",\"mode\":\"FULL\",\"failureCode\":null,\"renderer\":${renderer.report()}}"
            )
        } finally {
            chunkServer.stop()
            clockServer.stop()
        }
    }

    private fun runSinkFull(address: String, caseId: String, seconds: Int) {
        val estimator = ClockOffsetEstimator()
        val clockClient = ClockSyncClient(address, CLOCK_PORT, estimator)
        val hostNanosNow: () -> Long = { System.nanoTime() + (clockClient.currentEstimate()?.offsetNanos ?: 0L) }

        val scheduler = PlaybackScheduler(SyncRenderer.FRAMES_PER_CHUNK, SCHEDULER_CAPACITY_CHUNKS)
        val renderer = SyncRenderer(scheduler, DriftController(), hostNanosNow)
        val lastPlayAt = AtomicLong(0L)
        val chunkClient = ChunkClient(address, CHUNK_PORT) { chunk ->
            lastPlayAt.set(chunk.playAtHostNanos)
            scheduler.submit(chunk)
        }

        var history: List<ClockEstimate> = emptyList()
        val clockThread = Thread { history = clockClient.runFor(seconds) }
        try {
            clockThread.start()
            chunkClient.start()

            // hostNanosNow() is meaningless before the estimator has its first fit: it falls
            // back to a raw, uncorrected nanoTime() that can be off by however long the two
            // devices have been powered on. Starting the renderer's bound from that reading
            // risks a discontinuous jump the moment a real offset lands. Nothing arriving in
            // this window survives to be heard anyway (its 1.5s lead is smaller than the time
            // this can take), so waiting here does not cost anything that was not already lost.
            val warmupDeadline = System.nanoTime() + seconds * 1_000_000_000L
            while (clockClient.currentEstimate() == null && System.nanoTime() < warmupDeadline) {
                Thread.sleep(50)
            }

            val until = hostNanosNow() + (seconds + RENDERER_MARGIN_SECONDS) * 1_000_000_000L
            val rendererThread = Thread { renderer.run(until) }
            rendererThread.start()

            // The TCP connection stays open through calibration, so a gap in arriving chunks
            // (not a closed stream) is the only signal that the host has stopped broadcasting.
            var lastSeen = lastPlayAt.get()
            var lastChangeNanos = System.nanoTime()
            val idleDeadline = System.nanoTime() + (seconds + RENDERER_MARGIN_SECONDS) * 1_000_000_000L
            while (System.nanoTime() < idleDeadline) {
                Thread.sleep(100)
                val current = lastPlayAt.get()
                if (current != lastSeen) {
                    lastSeen = current
                    lastChangeNanos = System.nanoTime()
                } else if (current > 0 && System.nanoTime() - lastChangeNanos > IDLE_THRESHOLD_NANOS) {
                    break
                }
            }
            // rendererThread is intentionally left running: SyncRenderer.run only returns once
            // its own bound elapses and offers no cancellation hook, and by this point it has
            // nothing left to play, so joining it would just delay the result for no benefit.

            val calibration = CalibrationRunner(runStore, caseId)
            val observed = lastPlayAt.get()
            val chirpAt = (if (observed > 0) observed else hostNanosNow()) + CALIBRATION_GAP_NANOS
            calibration.playChirpAt(chirpAt, hostNanosNow)

            clockThread.join()
            runStore.writeSyncJson(
                caseId,
                "{\"schemaVersion\":1,\"role\":\"SINK\",\"mode\":\"FULL\",\"failureCode\":null," +
                    "\"estimates\":${estimatesJson(history)},\"renderer\":${renderer.report()}}"
            )
        } finally {
            chunkClient.stop()
        }
    }

    /** How long CalibrationRunner.record() must run, from now, to outlast the host's own chirp. */
    private fun recordSecondsFor(chirpStartHostNanos: Long): Int {
        val chirpTailNanos = (ChirpGenerator.DURATION_MS + 200) * 1_000_000L
        val remaining = chirpStartHostNanos + chirpTailNanos + RECORD_TAIL_NANOS - System.nanoTime()
        return ((remaining + 999_999_999L) / 1_000_000_000L).toInt().coerceAtLeast(1)
    }

    private fun estimatesJson(history: List<ClockEstimate>): String = buildString {
        append('[')
        history.forEachIndexed { index, estimate ->
            if (index > 0) append(',')
            append("{\"offsetNanos\":").append(estimate.offsetNanos)
            append(",\"uncertaintyNanos\":").append(estimate.uncertaintyNanos)
            append(",\"driftPpm\":").append(estimate.driftPpm)
            append(",\"sampleCount\":").append(estimate.sampleCount).append('}')
        }
        append(']')
    }

    companion object {
        const val CLOCK_PORT = 45123
        const val CHUNK_PORT = 45124

        /** playAtHostNanos = generation instant + this lead. */
        private const val LEAD_NANOS = 1_500_000_000L

        /** Gap between the last audio chunk and the sink's calibration chirp. */
        private const val CALIBRATION_GAP_NANOS = 2_000_000_000L

        /** The host's chirp follows the sink's by this much. */
        private const val STAGGER_NANOS = 500_000_000L

        /** The host keeps recording this long after its own chirp ends. */
        private const val RECORD_TAIL_NANOS = 2_000_000_000L

        /** How much longer than the nominal segment the sink's renderer/idle-wait stay alive. */
        private const val RENDERER_MARGIN_SECONDS = 10

        /** No new chunk for this long means the host has stopped broadcasting audio. */
        private const val IDLE_THRESHOLD_NANOS = 800_000_000L

        /** ~3s of audio at 20ms/chunk. */
        private const val SCHEDULER_CAPACITY_CHUNKS = 150
    }
}
