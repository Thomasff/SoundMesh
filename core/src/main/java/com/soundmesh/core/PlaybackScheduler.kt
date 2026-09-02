package com.soundmesh.core

sealed interface PlaybackDecision {
    /** Nothing has arrived yet and playback has not started. */
    object Idle : PlaybackDecision

    /** The next chunk belongs to the future; hand nothing to the output. */
    object Wait : PlaybackDecision

    data class Play(val chunk: AudioChunk) : PlaybackDecision

    data class Silence(val frames: Int) : PlaybackDecision
}

data class SchedulerStats(
    val queued: Int,
    val played: Int,
    val droppedLate: Int,
    val droppedOverflow: Int,
    val silenceFrames: Int
)

/**
 * The change in the session-wide counters between two [SchedulerStats] snapshots.
 *
 * SchedulerStats accumulates for the whole run, so on its own it cannot say whether a drop or a
 * silence-filled gap happened during a specific window - the calibration chirp, for one. Taking
 * two snapshots (before the window, after it) and diffing them attributes the counters to that
 * window instead of the whole session. `queued` is a point-in-time depth, not a counter, so it is
 * deliberately left out.
 */
data class SchedulerStatsWindow(
    val played: Int,
    val droppedLate: Int,
    val droppedOverflow: Int,
    val silenceFrames: Int
)

fun schedulerStatsWindow(before: SchedulerStats, after: SchedulerStats): SchedulerStatsWindow =
    SchedulerStatsWindow(
        played = after.played - before.played,
        droppedLate = after.droppedLate - before.droppedLate,
        droppedOverflow = after.droppedOverflow - before.droppedOverflow,
        silenceFrames = after.silenceFrames - before.silenceFrames
    )

/**
 * Holds chunks until the instant they must be heard, then releases them in order.
 *
 * The caller passes the host instant at which the data it is about to write will actually
 * reach the speakers, so output latency is the renderer's concern, not this class's.
 */
class PlaybackScheduler(private val framesPerChunk: Int, private val capacityChunks: Int) {
    private val queue = ArrayList<AudioChunk>()
    private var started = false
    private var played = 0
    private var droppedLate = 0
    private var droppedOverflow = 0
    private var silenceFrames = 0

    @Synchronized
    fun submit(chunk: AudioChunk) {
        val at = queue.indexOfFirst { it.playAtHostNanos > chunk.playAtHostNanos }
        if (at < 0) queue.add(chunk) else queue.add(at, chunk)
        while (queue.size > capacityChunks) {
            queue.removeAt(0)
            droppedOverflow++
        }
    }

    @Synchronized
    fun poll(nowHostNanos: Long): PlaybackDecision {
        while (queue.isNotEmpty() && queue[0].playAtHostNanos < nowHostNanos) {
            // A whole chunk period late is unrecoverable; silence is better than playing it wrong.
            if (nowHostNanos - queue[0].playAtHostNanos < chunkNanos()) break
            queue.removeAt(0)
            droppedLate++
        }
        val head = queue.firstOrNull()
            ?: return if (started) silence() else PlaybackDecision.Idle
        if (head.playAtHostNanos > nowHostNanos) {
            return if (started) silence() else PlaybackDecision.Wait
        }
        queue.removeAt(0)
        started = true
        played++
        return PlaybackDecision.Play(head)
    }

    @Synchronized
    fun stats(): SchedulerStats =
        SchedulerStats(queue.size, played, droppedLate, droppedOverflow, silenceFrames)

    private fun silence(): PlaybackDecision {
        silenceFrames += framesPerChunk
        return PlaybackDecision.Silence(framesPerChunk)
    }

    private fun chunkNanos(): Long = framesPerChunk * 1_000_000_000L / SAMPLE_RATE

    companion object {
        const val SAMPLE_RATE = 48000
    }
}
