package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.SpatialField
import com.soundmesh.probe.sync.ChunkClient
import com.soundmesh.probe.sync.ClockPacket
import com.soundmesh.probe.sync.ClockSyncClient
import com.soundmesh.probe.sync.SpatialFieldClient

/**
 * This machine following a host: the clock leg and the audio leg, joined by [ChunkPlayout].
 *
 * The mirror of [HostStream], and deliberately the same sockets the handsets use, so a host
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
    private val peerId: String? = null,
    private val spatialPort: Int = SPATIAL_PORT,
    /**
     * This machine's constant for the pair, subtracted from host time as a handset sink subtracts
     * its own: measured if it has been, a room round's approximation if not, zero if neither.
     */
    val alignmentOffsetNanos: Long = 0L
) {
    private val estimator = ClockOffsetEstimator(CLOCK_WINDOW, CLOCK_BEST)
    private val clockClient = ClockSyncClient(hostAddress, clockPort, estimator)
    private val playout = ChunkPlayout(output, alignmentOffsetNanos = alignmentOffsetNanos) {
        clockClient.currentEstimate()?.offsetNanos ?: 0L
    }

    // Only a named sink can be on a handset host's drawing, so only a named one has a room to play.
    private val spatial = peerId?.let { SinkSpatial(it) }

    private val chunkClient = ChunkClient(hostAddress, chunkPort, peerId) { chunk ->
        arrived++
        if (clockClient.currentEstimate() == null) chunksBeforeTheClockAnswered++ else playout.play(spatial?.shaped(chunk) ?: chunk)
    }

    @Volatile private var clockThread: Thread? = null

    /**
     * Chunks that came off the wire at all, played or not - what says the host is still sending.
     *
     * The chunk client says nothing when its socket ends; the thread reading it just stops. So
     * "the host went quiet" is read from this count standing still, the way the handset sink reads
     * it from the time since its last chunk.
     */
    @Volatile var arrived: Int = 0
        private set

    /** Chunks that arrived before the estimator had an answer, and so were not played. */
    @Volatile var chunksBeforeTheClockAnswered: Int = 0
        private set

    val played: Int get() = playout.played

    /** The host's newest rule, or null before one arrived - what this machine draws the room from. */
    val rule: SpatialField? get() = spatial?.latest

    /** Who holds which colour, as the host last said on the spatial channel. */
    @Volatile var badges: Map<String, Int> = emptyMap()
        private set

    /** Chunks a handset host's spatial rule changed - see [SinkSpatial.shapedChunks]. */
    val shaped: Int get() = spatial?.shapedChunks ?: 0
    val droppedLate: Int get() = playout.droppedLate
    val restarts: Int get() = playout.restarts
    val seams: Int get() = playout.seams
    val worstSeamFrames: Int get() = playout.worstSeamFrames

    /** How wide the joins are, as a level - see [ChunkPlayout.seamBand]. */
    fun seamBand(): String = playout.seamBand()

    /** Which of host, offset and device the seams came from - see [ChunkPlayout.seamShares]. */
    fun seamShares(): String = playout.seamShares()

    fun offsetNanos(): Long? = clockClient.currentEstimate()?.offsetNanos

    /** Starts the clock leg only. The audio leg is [dial], so a caller can wait in between. */
    fun startClock() {
        check(clockThread == null) { "already started" }
        clockThread = Thread({ clockClient.runFor(FOREVER_SECONDS, CLOCK_INTERVAL_MILLIS) }, "sink-clock").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Blocks until the estimator has published, until [timeoutMillis] is up, or until [keepWaiting]
     * says the wait is no longer wanted - a host that says stop while this is still settling.
     */
    fun awaitClock(timeoutMillis: Long, keepWaiting: () -> Boolean = { true }): Boolean {
        val giveUpAt = System.nanoTime() + timeoutMillis * 1_000_000L
        while (clockClient.currentEstimate() == null) {
            if (System.nanoTime() > giveUpAt || !keepWaiting()) return false
            Thread.sleep(POLL_MILLIS)
        }
        return true
    }

    @Volatile private var spatialClient: SpatialFieldClient? = null

    /**
     * Dials the audio, and then the spatial channel under this machine's name.
     *
     * The second is what keeps this machine on a handset host's room drawing while it plays: that
     * drawing is whoever has named themselves there, not whoever stands by (09-23). The rules that
     * come down it shape what this machine plays, as they do on a handset - see [SinkSpatial].
     * Allowed to fail on its own, as it is on the handsets: a host
     * without the channel must not cost the room its sound.
     */
    fun dial() {
        chunkClient.start()
        spatialClient = peerId?.let { name ->
            SpatialFieldClient(hostAddress, spatialPort, name, onBadges = { badges = it }) { spatial?.apply(it) }
                .takeIf { runCatching { it.start() }.isSuccess }
        }
    }

    fun stop() {
        spatialClient?.stop()
        chunkClient.stop()
        clockThread?.interrupt()
        clockThread = null
    }

    companion object {
        /** The handsets' spatial channel (SyncActivity.SPATIAL_PORT), which lives in the app module. */
        const val SPATIAL_PORT = 45126

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
