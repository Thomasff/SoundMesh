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
class PlaybackScheduler(
    private val framesPerChunk: Int,
    private val capacityChunks: Int,
    /**
     * How early a chunk may be released rather than have silence written in front of it.
     *
     * Zero is the exact release this class had until O37, and it is exact to the nanosecond: a
     * chunk due a nanosecond later got silence. In steady playback that fires on ordinary jitter
     * tens of times a second, and each firing is a one-to-few-frame hole punched into the audio -
     * measured at 124 frames a second of silence spread across a run that was otherwise healthy.
     * The holes are what a listener hears, in bursts, as a crackle.
     *
     * A tolerance costs up to its own width in timing and leaves the waveform whole. It is the
     * mirror of the renderer's trim deadband, which does the same for a chunk released late; with
     * both, small jitter moves the write position inside a band instead of editing the audio.
     */
    private val earlyReleaseNanos: Long = 0,
    /**
     * Sequence at or above which [earlyReleaseNanos] does not apply, or null for none.
     *
     * The chirp is the instrument alignment is measured with, and an early release cannot be taken
     * back the way a late one can - the renderer trims a late chunk, but nothing un-plays an early
     * one. Releasing a chirp inside the tolerance would put the tolerance straight into every
     * measurement, so chirps keep the exact release.
     */
    private val exactReleaseFromSequence: Int? = null
) {
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
            ?: return if (started) silence(null, nowHostNanos) else PlaybackDecision.Idle
        val exact = exactReleaseFromSequence?.let { head.sequence >= it } ?: false
        val tolerance = if (exact) 0L else earlyReleaseNanos
        if (head.playAtHostNanos - nowHostNanos > tolerance) {
            return if (started) silence(head.playAtHostNanos, nowHostNanos) else PlaybackDecision.Wait
        }
        queue.removeAt(0)
        started = true
        played++
        return PlaybackDecision.Play(head)
    }

    @Synchronized
    fun stats(): SchedulerStats =
        SchedulerStats(queue.size, played, droppedLate, droppedOverflow, silenceFrames)

    /**
     * Fills only as far as [untilHostNanos], so the write stream lands exactly on the next chunk's
     * instant instead of stepping past it.
     *
     * Filling a whole chunk regardless of the gap is one half of a pair of whole-chunk
     * quantisations that fight each other. The overfill puts the write stream past the next
     * chunk's instant, so that chunk is released late by however much was overfilled, and the
     * renderer then trims exactly that much back off - leaving playback alternating between a
     * fully trimmed chunk and a full chunk of silence. Measured: 44% of a run spent in silence,
     * against 3-6% before the trim existed. Filling the real gap removes the cause rather than
     * the symptom, and leaves the trim to handle only what it was written for - a stream that has
     * no chunk to align to yet.
     *
     * A null [untilHostNanos] means nothing is queued, so there is no gap to measure and a whole
     * chunk keeps the AudioTrack fed. Never returns zero: a sub-frame gap truncates to nothing,
     * which would have the render loop write nothing and poll again on the same instant.
     */
    private fun silence(untilHostNanos: Long?, nowHostNanos: Long): PlaybackDecision {
        val frames = if (untilHostNanos == null) {
            framesPerChunk
        } else {
            val gapFrames = (untilHostNanos - nowHostNanos) * SAMPLE_RATE / 1_000_000_000L
            gapFrames.coerceIn(1L, framesPerChunk.toLong()).toInt()
        }
        silenceFrames += frames
        return PlaybackDecision.Silence(frames)
    }

    private fun chunkNanos(): Long = framesPerChunk * 1_000_000_000L / SAMPLE_RATE

    companion object {
        const val SAMPLE_RATE = 48000
    }
}
