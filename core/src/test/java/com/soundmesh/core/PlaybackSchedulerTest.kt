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
    fun fillsOnlyAsMuchSilenceAsTheGapToTheNextChunkNeeds() {
        // A gap of 5ms must be filled with 5ms of silence, not a whole chunk. Overfilling pushes
        // the write stream past the next chunk's instant, so that chunk is then released late by
        // however much was overfilled - the release-phase error the renderer has to trim back off.
        // Silence quantised to a whole chunk and a release quantised to a whole chunk fight each
        // other: the overfill creates exactly the lateness the trim then removes, and playback
        // ends up alternating between a fully trimmed chunk and a full chunk of silence.
        val scheduler = scheduler()
        scheduler.submit(chunk(0, 1_000_000_000))
        scheduler.submit(chunk(2, 1_000_000_000 + chunkNanos + 5_000_000))
        scheduler.poll(1_000_000_000)

        val decision = scheduler.poll(1_000_000_000 + chunkNanos)

        assertEquals(PlaybackDecision.Silence(240), decision)
        assertEquals(240, scheduler.stats().silenceFrames)
    }

    @Test
    fun fillsAWholeChunkWhenNothingIsQueuedToAlignTo() {
        // Nothing to aim at, so there is no gap to measure; a whole chunk keeps the AudioTrack fed
        // and whatever arrives later is aligned by the renderer's own trim instead.
        val scheduler = scheduler()
        scheduler.submit(chunk(0, 1_000_000_000))
        scheduler.poll(1_000_000_000)

        val decision = scheduler.poll(1_000_000_000 + chunkNanos)

        assertEquals(PlaybackDecision.Silence(framesPerChunk), decision)
    }

    @Test
    fun neverFillsZeroFramesOfSilence() {
        // A quarter of a frame of gap truncates to zero. Returning that would have the render loop
        // write nothing and poll again on the same instant, spinning instead of playing.
        val scheduler = scheduler()
        scheduler.submit(chunk(0, 1_000_000_000))
        scheduler.submit(chunk(2, 1_000_000_000 + chunkNanos + 5_208))
        scheduler.poll(1_000_000_000)

        val decision = scheduler.poll(1_000_000_000 + chunkNanos)

        assertEquals(PlaybackDecision.Silence(1), decision)
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

    @Test
    fun releasesAChunkThatIsOnlyMarginallyEarlyInsteadOfWedgingSilenceInFrontOfIt() {
        // O35-O37: the release test was a strict comparison, so a chunk due a nanosecond later
        // got silence written ahead of it. In steady playback that fires on ordinary jitter, tens
        // of times a second, each one a one-to-few-frame hole punched into the music - which is
        // what a listener hears as a crackle in bursts. Small early releases cost up to the
        // tolerance in timing and leave the waveform whole.
        val scheduler = PlaybackScheduler(framesPerChunk, capacityChunks = 4, earlyReleaseNanos = 1_000_000)
        scheduler.submit(chunk(0, 1_000_000_000))
        assertEquals(PlaybackDecision.Play(chunk(0, 1_000_000_000)), scheduler.poll(999_500_000))
    }

    @Test
    fun stillFillsSilenceWhenTheChunkIsFurtherOutThanTheTolerance() {
        val scheduler = PlaybackScheduler(framesPerChunk, capacityChunks = 4, earlyReleaseNanos = 1_000_000)
        scheduler.submit(chunk(0, 1_000_000_000))
        assertTrue(scheduler.poll(990_000_000) is PlaybackDecision.Wait)
    }

    @Test
    fun holdsAChirpToItsExactInstantEvenInsideTheTolerance() {
        // The chirp is what alignment is measured with, and an early release cannot be trimmed
        // back the way a late one can - releasing it early would put the tolerance straight into
        // every measurement.
        val scheduler = PlaybackScheduler(
            framesPerChunk, capacityChunks = 4, earlyReleaseNanos = 1_000_000, exactReleaseFromSequence = 1_000_000
        )
        scheduler.submit(chunk(0, 1_000_000_000))
        scheduler.poll(1_000_000_000)
        scheduler.submit(chunk(1_000_000, 2_000_000_000))
        assertTrue(scheduler.poll(1_999_500_000) is PlaybackDecision.Silence)
    }

    @Test
    fun leavesTheReleaseExactWhenNoToleranceIsAskedFor() {
        val scheduler = scheduler()
        scheduler.submit(chunk(0, 1_000_000_000))
        assertEquals(PlaybackDecision.Wait, scheduler.poll(999_999_999))
    }
}
