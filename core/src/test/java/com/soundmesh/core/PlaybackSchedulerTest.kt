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

    /**
     * What a seek needs and nothing else has: audio already accepted has to be able to go away.
     *
     * Everything here is otherwise append-and-release, which is right for a stream that only ever
     * moves forward. A listener dragging a progress bar is the one thing that makes what is
     * already queued wrong rather than early - and it is queued on every handset in the room, not
     * only the one being dragged.
     */
    @Test
    fun clearingThrowsAwayWhatWasQueuedAndSaysHowMuch() {
        val scheduler = scheduler()
        scheduler.submit(chunk(0, 1_000))
        scheduler.submit(chunk(1, 2_000))
        assertEquals(2, scheduler.clear())
        assertEquals(0, scheduler.stats().queued)
    }

    @Test
    fun clearingAnEmptyQueueThrowsAwayNothing() {
        assertEquals(0, scheduler().clear())
    }

    /**
     * What was cleared is gone, not merely skipped: the instant it was due for belongs to the
     * audio that replaced it, and a chunk that came back would be played over the top of it.
     */
    @Test
    fun whatWasClearedDoesNotComeBack() {
        val scheduler = scheduler()
        scheduler.submit(chunk(0, 1_000))
        scheduler.clear()
        assertTrue(scheduler.poll(1_000) is PlaybackDecision.Idle)
    }

    /** Clearing is not stopping. The next chunk plays exactly as it would have. */
    @Test
    fun aClearedSchedulerStillPlaysWhatComesNext() {
        val scheduler = scheduler()
        scheduler.submit(chunk(0, 1_000))
        scheduler.clear()
        val next = chunk(0, 5_000)
        scheduler.submit(next)
        assertEquals(PlaybackDecision.Play(next), scheduler.poll(5_000))
    }

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

    /**
     * What one handset writes, chunk by chunk, when it is driven the way the render loop drives it.
     *
     * Each decision advances the clock by exactly the frames it produced, which is what the render
     * loop's own instant does: it asks about the moment the next frame written will be heard, and
     * writing a frame moves that moment on by one frame.
     */
    private fun writeStream(
        scheduler: PlaybackScheduler,
        fromHostNanos: Long,
        polls: Int
    ): List<Pair<Long, String>> {
        val written = ArrayList<Pair<Long, String>>()
        var now = fromHostNanos
        repeat(polls) {
            val frames = when (val decision = scheduler.poll(now)) {
                is PlaybackDecision.Play -> {
                    written += now to "chunk ${decision.chunk.sequence}"
                    framesPerChunk
                }
                is PlaybackDecision.Silence -> {
                    written += now to "silence ${decision.frames}"
                    decision.frames
                }
                else -> 0
            }
            now += frames.toLong() * 1_000_000_000L / PlaybackScheduler.SAMPLE_RATE
        }
        return written
    }

    /**
     * A handset that never receives one chunk goes on writing every later one at the same instant
     * as a handset that received them all.
     *
     * The property the room rests on once the handsets stop playing the same waveform. While they
     * all play the whole mix, a hole is that handset's own blip; once one carries the low half and
     * another the high, a hole that also shifted what came after it would leave the two halves
     * permanently out of step - a phase break rather than a dropout, and one nothing recovers from.
     *
     * Already true when this was written, and written down because it was not obvious: the gap fill
     * measures the real distance to the next chunk's instant rather than writing a whole chunk, so
     * the write stream is pinned to the host clock and a missing chunk costs exactly its own
     * duration. That came from O37, which was chasing a crackle rather than this.
     */
    @Test
    fun aMissingChunkCostsItsOwnDurationAndNothingAfterIt() {
        val whole = scheduler()
        val holed = scheduler()
        for (sequence in 0 until 4) {
            val at = sequence * chunkNanos
            whole.submit(chunk(sequence, at))
            // The one that never arrived: a chunk the network lost, not one that came late.
            if (sequence != 1) holed.submit(chunk(sequence, at))
        }

        val wholeStream = writeStream(whole, 0L, polls = 4)
        val holedStream = writeStream(holed, 0L, polls = 4)

        // Where each surviving chunk was written, which is the thing that must not move.
        val wholeAt = wholeStream.filter { it.second.startsWith("chunk") }.associate { it.second to it.first }
        val holedAt = holedStream.filter { it.second.startsWith("chunk") }.associate { it.second to it.first }
        for (sequence in listOf("chunk 2", "chunk 3")) {
            assertEquals(sequence, wholeAt[sequence], holedAt[sequence])
        }
        // And the hole is exactly one chunk of silence, not a whole chunk plus a shifted stream.
        // At the instant the missing chunk was due, and exactly as long as it would have been.
        assertEquals(listOf(chunkNanos to "silence $framesPerChunk"), holedStream.filter { it.second.startsWith("silence") })
    }

    /**
     * And the same when the chunk arrives too late to play rather than not at all, which is the
     * other way a handset loses one - a stall on the link, not a loss.
     */
    @Test
    fun aChunkDroppedForBeingLateDoesNotMoveTheOnesAfterIt() {
        val whole = scheduler()
        val late = scheduler()
        for (sequence in 0 until 4) {
            val at = sequence * chunkNanos
            whole.submit(chunk(sequence, at))
            late.submit(chunk(sequence, at))
        }

        val wholeStream = writeStream(whole, 0L, polls = 4)
        // Started a whole chunk in: chunk 0 is now unrecoverably late and is dropped.
        val lateStream = writeStream(late, chunkNanos, polls = 4)

        val wholeAt = wholeStream.filter { it.second.startsWith("chunk") }.associate { it.second to it.first }
        val lateAt = lateStream.filter { it.second.startsWith("chunk") }.associate { it.second to it.first }
        for (sequence in listOf("chunk 1", "chunk 2", "chunk 3")) {
            assertEquals(sequence, wholeAt[sequence], lateAt[sequence])
        }
    }

    @Test
    fun leavesTheReleaseExactWhenNoToleranceIsAskedFor() {
        val scheduler = scheduler()
        scheduler.submit(chunk(0, 1_000_000_000))
        assertEquals(PlaybackDecision.Wait, scheduler.poll(999_999_999))
    }
}
