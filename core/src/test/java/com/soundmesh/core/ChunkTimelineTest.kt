package com.soundmesh.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkTimelineTest {
    @Test
    fun theFirstChunkIsDueWhenItArrived() {
        val timeline = ChunkTimeline(FRAMES_PER_CHUNK, SAMPLE_RATE)

        assertEquals(START, timeline.accept(START))
    }

    @Test
    fun aSourceThatArrivesOnTheGridIsLeftWhereItIs() {
        val timeline = ChunkTimeline(FRAMES_PER_CHUNK, SAMPLE_RATE)

        repeat(50) { chunk -> assertEquals(grid(chunk), timeline.accept(grid(chunk))) }
    }

    @Test
    fun arrivalJitterIsDividedAwayInsteadOfPassedOn() {
        val timeline = ChunkTimeline(FRAMES_PER_CHUNK, SAMPLE_RATE)
        val jitter = Random(7)

        var worst = 0L
        for (chunk in 0 until CHUNKS) {
            val arrival = grid(chunk) + if (chunk == 0) 0L else jitter.nextLong(-JITTER, JITTER)
            val due = timeline.accept(arrival)
            if (chunk > SETTLED) worst = maxOf(worst, Math.abs(due - grid(chunk)))
        }

        // The bound is the renderer's trim deadband, 5 ms, with room to spare: below it nothing
        // is cut. The input is harsher than the capture this was written for, which kept most
        // chunks inside that deadband to begin with.
        assertTrue("$worst ns left of $JITTER", worst < 3_000_000L)
    }

    @Test
    fun theTimelineStaysUniformThroughJitter() {
        val timeline = ChunkTimeline(FRAMES_PER_CHUNK, SAMPLE_RATE)
        val jitter = Random(11)

        var previous = timeline.accept(grid(0))
        var worst = 0L
        for (chunk in 1 until CHUNKS) {
            val due = timeline.accept(grid(chunk) + jitter.nextLong(-JITTER, JITTER))
            if (chunk > SETTLED) worst = maxOf(worst, Math.abs(due - previous - CHUNK_NANOS))
            previous = due
        }

        // Uniformity is the property the renderer actually consumes: it plays chunk after chunk
        // and cuts the waveform whenever the next one is not where the last one said it would be.
        assertTrue("$worst ns of step error", worst < 500_000L)
    }

    @Test
    fun aSourceWhoseClockRunsSlowIsFollowedRatherThanFought() {
        val timeline = ChunkTimeline(FRAMES_PER_CHUNK, SAMPLE_RATE)

        // 200 parts per million, so by the last chunk the source is 8 ms past a fixed grid - well
        // outside the trim deadband, and exactly what a grid that never moved would fail on.
        var worst = 0L
        for (chunk in 0 until CHUNKS) {
            val arrival = grid(chunk) + chunk * CHUNK_NANOS / 5_000
            val due = timeline.accept(arrival)
            if (chunk > SETTLED) worst = maxOf(worst, Math.abs(due - arrival))
        }

        assertTrue("$worst ns behind the source", worst < 1_500_000L)
    }

    @Test
    fun theNextChunkIsDueOneChunkAfterTheLastOne() {
        val timeline = ChunkTimeline(FRAMES_PER_CHUNK, SAMPLE_RATE)
        val due = timeline.accept(START)

        assertEquals(due + CHUNK_NANOS, timeline.nextDueNanos())
    }

    @Test
    fun nothingIsDueUntilTheFirstChunkHasArrived() {
        assertEquals(null, ChunkTimeline(FRAMES_PER_CHUNK, SAMPLE_RATE).nextDueNanos())
    }

    @Test
    fun followingEveryArrivalWholeIsWhatSmoothingOfOneMeans() {
        val timeline = ChunkTimeline(FRAMES_PER_CHUNK, SAMPLE_RATE, smoothingChunks = 1)

        timeline.accept(START)

        // The defect this class was written for, reachable on purpose: a timeline that copies the
        // instant its source happened to hand a chunk over.
        assertEquals(START + CHUNK_NANOS + 7_000_000L, timeline.accept(grid(1) + 7_000_000L))
    }

    @Test
    fun refusesASmoothingThatCannotBeAveragedOver() {
        val refused = runCatching { ChunkTimeline(FRAMES_PER_CHUNK, SAMPLE_RATE, smoothingChunks = 0) }

        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }

    private fun grid(chunk: Int): Long = START + chunk * CHUNK_NANOS

    private companion object {
        const val FRAMES_PER_CHUNK = 960
        const val SAMPLE_RATE = 48_000
        const val CHUNK_NANOS = 20_000_000L
        const val START = 1_000_000_000L

        /** What the Magic6's capture handed over: twenty milliseconds either side. */
        const val JITTER = 20_000_000L

        const val CHUNKS = 2_000
        const val SETTLED = 500
    }
}
