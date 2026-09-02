package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSchedulerTest {
    private val framesPerChunk = 960
    private val chunkNanos = 20_000_000L
    private fun chunk(sequence: Int, playAt: Long) =
        AudioChunk(sequence, playAt, ByteArray(framesPerChunk * 4) { sequence.toByte() })

    private fun scheduler() = PlaybackScheduler(framesPerChunk = framesPerChunk, capacityChunks = 4)

    @Test
    fun waitsWhileTheFirstChunkIsStillInTheFuture() {
        val scheduler = scheduler()
        scheduler.submit(chunk(0, 1_000_000_000))

        assertEquals(PlaybackDecision.Wait, scheduler.poll(900_000_000))
    }

    @Test
    fun staysIdleBeforeAnythingHasArrived() {
        assertEquals(PlaybackDecision.Idle, scheduler().poll(1_000_000_000))
    }

    @Test
    fun playsTheChunkOnceItsInstantArrives() {
        val scheduler = scheduler()
        scheduler.submit(chunk(7, 1_000_000_000))

        val decision = scheduler.poll(1_000_000_000)

        assertTrue(decision is PlaybackDecision.Play)
        assertEquals(7, (decision as PlaybackDecision.Play).chunk.sequence)
        assertEquals(1, scheduler.stats().played)
    }

    @Test
    fun dropsAChunkWhoseInstantHasAlreadyPassedAndSaysSo() {
        val scheduler = scheduler()
        scheduler.submit(chunk(0, 1_000_000_000))
        scheduler.submit(chunk(1, 1_000_000_000 + chunkNanos))

        val decision = scheduler.poll(1_000_000_000 + chunkNanos)

        assertTrue(decision is PlaybackDecision.Play)
        assertEquals(1, (decision as PlaybackDecision.Play).chunk.sequence)
        assertEquals(1, scheduler.stats().droppedLate)
    }

    @Test
    fun fillsSilenceForAGapAfterPlaybackHasStarted() {
        val scheduler = scheduler()
        scheduler.submit(chunk(0, 1_000_000_000))
        scheduler.submit(chunk(2, 1_000_000_000 + 2 * chunkNanos))
        scheduler.poll(1_000_000_000)

        val decision = scheduler.poll(1_000_000_000 + chunkNanos)

        assertEquals(PlaybackDecision.Silence(framesPerChunk), decision)
        assertEquals(framesPerChunk, scheduler.stats().silenceFrames)
    }

    @Test
    fun dropsTheOldestChunkWhenTheQueueIsFull() {
        val scheduler = scheduler()
        repeat(6) { index -> scheduler.submit(chunk(index, 1_000_000_000 + index * chunkNanos)) }

        assertEquals(4, scheduler.stats().queued)
        assertEquals(2, scheduler.stats().droppedOverflow)
    }

    @Test
    fun playsInInstantOrderEvenIfChunksAreSubmittedOutOfOrder() {
        val scheduler = scheduler()
        scheduler.submit(chunk(1, 1_000_000_000 + chunkNanos))
        scheduler.submit(chunk(0, 1_000_000_000))

        val first = scheduler.poll(1_000_000_000) as PlaybackDecision.Play

        assertEquals(0, first.chunk.sequence)
    }

    @Test
    fun statsWindowIsZeroWhenNothingHappenedBetweenTwoSnapshots() {
        val snapshot = SchedulerStats(queued = 3, played = 5, droppedLate = 1, droppedOverflow = 0, silenceFrames = 20)

        val window = schedulerStatsWindow(snapshot, snapshot)

        assertEquals(0, window.played)
        assertEquals(0, window.droppedLate)
        assertEquals(0, window.droppedOverflow)
        assertEquals(0, window.silenceFrames)
    }

    @Test
    fun statsWindowAttributesOnlyWhatChangedBetweenTheTwoSnapshots() {
        val before = SchedulerStats(queued = 10, played = 100, droppedLate = 2, droppedOverflow = 1, silenceFrames = 180)
        val after = SchedulerStats(queued = 4, played = 106, droppedLate = 3, droppedOverflow = 1, silenceFrames = 220)

        val window = schedulerStatsWindow(before, after)

        assertEquals(6, window.played)
        assertEquals(1, window.droppedLate)
        assertEquals(0, window.droppedOverflow)
        assertEquals(40, window.silenceFrames)
    }
}
