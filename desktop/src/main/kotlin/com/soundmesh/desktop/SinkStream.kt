package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.probe.sync.ChunkClient
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.ClockSyncClient

/**
 * This machine following a host: the clock leg and the audio leg, joined by [ChunkPlayout].
 *
 * The mirror of [HostStream], and deliberately the same two sockets the handsets use, so a host
 * cannot tell this from a phone. Nothing here is new protocol.
 *
 * **Silent until the clock answers.** The estimator publishes nothing below its minimum sample
 * count, and a chunk played against an offset of zero would not be early or late by a little - it
 * would be placed wherever the two machines' arbitrary nanosecond origins happen to sit apart,
 * which is any amount at all. Chunks that arrive before the first estimate are counted and
 * dropped, and that count is the thing to look at when a session starts quiet.
 */
class SinkStream(
    private val hostAddress: String,
    output: FrameOutput,
    private val chunkPort: Int = ChunkCodec.DEFAULT_PORT,
    private val clockPort: Int = ClockPacket.DEFAULT_PORT,
    private val peerId: String? = null
) {
    private val estimator = ClockOffsetEstimator(CLOCK_WINDOW, CLOCK_BEST)
    private val clockClient = ClockSyncClient(hostAddress, clockPort, estimator)
    private val playout = ChunkPlayout(output) { clockClient.currentEstimate()?.offsetNanos ?: 0L }

    private val chunkClient = ChunkClient(hostAddress, chunkPort, peerId) { chunk ->
        if (clockClient.currentEstimate() == null) chunksBeforeTheClockAnswered++ else playout.play(chunk)
    }

    @Volatile private var clockThread: Thread? = null

    /** Chunks that arrived before the estimator had an answer, and so were not played. */
    @Volatile var chunksBeforeTheClockAnswered: Int = 0
        private set

    val played: Int get() = playout.played
    val droppedLate: Int get() = playout.droppedLate
    val seams: Int get() = playout.seams
    val worstSeamFrames: Int get() = playout.worstSeamFrames

    fun offsetNanos(): Long? = clockClient.currentEstimate()?.offsetNanos

    /** Starts the clock leg only. The audio leg is [dial], so a caller can wait in between. */
    fun startClock() {
        check(clockThread == null) { "already started" }
        clockThread = Thread({ clockClient.runFor(FOREVER_SECONDS, CLOCK_INTERVAL_MILLIS) }, "sink-clock").apply {
            isDaemon = true
            start()
        }
    }

    /** Blocks until the estimator has published, or until [timeoutMillis] is up. */
    fun awaitClock(timeoutMillis: Long): Boolean {
        val giveUpAt = System.nanoTime() + timeoutMillis * 1_000_000L
        while (clockClient.currentEstimate() == null) {
            if (System.nanoTime() > giveUpAt) return false
            Thread.sleep(POLL_MILLIS)
        }
        return true
    }

    fun dial() = chunkClient.start()

    fun stop() {
        chunkClient.stop()
        clockThread?.interrupt()
        clockThread = null
    }

    companion object {
        /**
         * The cadence and window the handset sink settled on, named here for the same reason it is
         * named there: the core defaults have other readers that were never measured under this
         * shape. See SinkSession's own note, and cross-platform.md experiments 27 and 28.
         */
        const val CLOCK_INTERVAL_MILLIS = 250L
        const val CLOCK_WINDOW = 512
        const val CLOCK_BEST = 64

        private const val FOREVER_SECONDS = 365 * 24 * 3600
        private const val POLL_MILLIS = 20L
    }
}
