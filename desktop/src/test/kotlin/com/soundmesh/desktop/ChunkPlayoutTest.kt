package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChunkCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class ChunkPlayoutTest {

    @Test
    fun aChunkLandsOnTheFrameItsHostInstantNames() {
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }

        playout.play(AudioChunk(0, 1_000_000_000L, stereo(4)))

        assertEquals(listOf(48000L), output.scheduled.map { it.first })
        assertEquals(1, playout.played)
    }

    /**
     * A host that jumps starts its sequence again, and what this machine had queued is the place
     * it jumped from: thrown away once, on the restart, and never on an ordinary next chunk or a
     * hole.
     */
    @Test
    fun aSequenceThatStartsAgainDropsWhatWasQueued() {
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }
        playout.play(AudioChunk(0, 1_000_000_000L, stereo(4)))
        playout.play(AudioChunk(1, 1_020_000_000L, stereo(4)))
        playout.play(AudioChunk(3, 1_060_000_000L, stereo(4)))
        assertEquals(0, output.drops)

        playout.play(AudioChunk(0, 2_500_000_000L, stereo(4)))
        assertEquals(1, output.drops)
        assertEquals(1, playout.restarts)
        playout.play(AudioChunk(1, 2_520_000_000L, stereo(4)))
        assertEquals(1, output.drops)
    }

    @Test
    fun theClockOffsetMovesTheLandingFrameTheOtherWay() {
        // The host is a second ahead of this machine, so a host instant of two seconds is one
        // second from now here - half the frames of the same chunk with no offset. Getting this
        // sign backwards is the failure that leaves both ends working and nothing in step.
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 1_000_000_000L }

        playout.play(AudioChunk(0, 2_000_000_000L, stereo(4)))

        assertEquals(listOf(48000L), output.scheduled.map { it.first })
    }

    @Test
    fun theOffsetIsReadPerChunkRatherThanHeld() {
        val output = FakeOutput()
        var offset = 0L
        val playout = ChunkPlayout(output) { offset }

        playout.play(AudioChunk(0, 1_000_000_000L, stereo(4)))
        offset = 500_000_000L
        playout.play(AudioChunk(1, 1_000_000_000L, stereo(4)))

        assertEquals(listOf(48000L, 24000L), output.scheduled.map { it.first })
    }

    @Test
    fun bothChannelsSurviveTheTripToTheOutput() {
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }
        val pcm = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x03.toByte(), 0x00, 0x04, 0x00)

        playout.play(AudioChunk(0, 0L, pcm))

        val (_, channels, samples) = output.scheduled.single()
        assertEquals(2, channels)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), samples)
    }

    @Test
    fun aChunkTheOutputHasAlreadyPassedIsCountedRatherThanPlayed() {
        val output = FakeOutput()
        output.firstSchedulableFrame = 48001
        val playout = ChunkPlayout(output) { 0L }

        assertFalse(playout.play(AudioChunk(0, 1_000_000_000L, stereo(4))))

        assertTrue(output.scheduled.isEmpty())
        assertEquals(0, playout.played)
        assertEquals(1, playout.droppedLate)
    }

    @Test
    fun chunksAWholeChunkApartLeaveNoSeam() {
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }

        playout.play(AudioChunk(0, 0L, stereo(960)))
        playout.play(AudioChunk(1, 20_000_000L, stereo(960)))
        playout.play(AudioChunk(2, 40_000_000L, stereo(960)))

        assertEquals(listOf(0L, 960L, 1920L), output.scheduled.map { it.first })
        assertEquals(0, playout.worstSeamFrames)
        assertEquals(0, playout.seams)
    }

    @Test
    fun aChunkTheClockPutsAFewFramesOffIsCountedAsASeamButWrittenEndToEnd() {
        // The seam is what the clock reading said, and it stays visible. What is written is the
        // chunk butted against its neighbour: obeying a reading that wobbles by a frame put a
        // hole or two summed samples into the sound some 25 times a second, which a listener
        // heard as crackle on 09-23 - the handsets' old "clicks" in a new shape.
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }

        playout.play(AudioChunk(0, 0L, stereo(960)))
        playout.play(AudioChunk(1, 20_100_000L, stereo(960)))

        assertEquals(listOf(0L, 960L), output.scheduled.map { it.first })
        assertEquals(960, output.scheduled[1].third.size / 2)
        assertEquals(4, playout.worstSeamFrames)
        assertEquals(1, playout.seams)
        assertEquals(0, playout.nudges)
    }

    @Test
    fun aMissingSequenceIsNotReportedAsASeamAndIsPlacedWhereTheClockSays() {
        // The host dropped one, or the network did. That is a hole a whole chunk wide and it
        // shows up in the sequence itself; calling it a seam would bury the one-frame kind
        // this counter exists to find. There is no neighbour to butt against, so the chunk
        // goes where its instant says.
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }

        playout.play(AudioChunk(0, 0L, stereo(960)))
        playout.play(AudioChunk(2, 40_000_000L, stereo(960)))

        assertEquals(listOf(0L, 1920L), output.scheduled.map { it.first })
        assertEquals(0, playout.worstSeamFrames)
        assertEquals(0, playout.seams)
    }

    @Test
    fun aTimelineThatKeepsDriftingIsFollowedAFrameAtATimeWithoutABreak() {
        // Two sample clocks a few parts per million apart: here the host's timeline runs one
        // frame long every fourth chunk, far faster than any real pair, so the test is short.
        // Every chunk has to start where the one before ran out, and the sound has to stay
        // within the deadband of where the clock says it belongs.
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }
        var frame = 0L
        val measured = ArrayList<Long>()
        for (sequence in 0 until 400) {
            if (sequence > 0) frame += 960 + if (sequence % 4 == 0) 1 else 0
            measured.add(frame)
            playout.play(AudioChunk(sequence, instantOfFrame(frame), stereo(960)))
        }

        for (i in 1 until output.scheduled.size) {
            val (at, _, samples) = output.scheduled[i - 1]
            assertEquals("chunk $i does not start where chunk ${i - 1} ended", at + samples.size / 2, output.scheduled[i].first)
        }
        val worstLag = output.scheduled.indices.maxOf { kotlin.math.abs(measured[it] - output.scheduled[it].first) }
        assertTrue("fell $worstLag frames behind the clock", worstLag <= ChunkPlayout.NUDGE_DEADBAND_FRAMES + 3)
        assertTrue("never nudged", playout.nudges > 0)
        assertEquals(0, playout.replaced)
    }

    @Test
    fun aNudgedChunkIsStretchedByAFrameRatherThanHoled() {
        // A frame is added by drawing the chunk one frame longer, not by leaving a zero or
        // repeating a sample: the chunk's first and last samples stay where they were, so
        // both of its joins are as smooth as the music is.
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }
        val ramp = ramp(960)
        var frame = 0L
        for (sequence in 0 until 10) {
            if (sequence > 0) frame += 960 + ChunkPlayout.NUDGE_DEADBAND_FRAMES + 2
            playout.play(AudioChunk(sequence, instantOfFrame(frame), ramp))
        }

        val stretched = output.scheduled.map { it.third }.first { it.size / 2 != 960 }
        assertEquals(961, stretched.size / 2)
        assertEquals(sampleOf(ramp, 0), stretched[0].toInt())
        assertEquals(sampleOf(ramp, 959 * 2), stretched[960 * 2].toInt())
        for (i in 1 until 961) {
            assertTrue("sample $i steps back", stretched[i * 2] >= stretched[(i - 1) * 2])
        }
    }

    @Test
    fun aNudgedChunkIsSqueezedByAFrameWhenTheClockRunsTheOtherWay() {
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }
        var frame = 10_000L
        for (sequence in 0 until 10) {
            if (sequence > 0) frame += 960 - ChunkPlayout.NUDGE_DEADBAND_FRAMES - 2
            playout.play(AudioChunk(sequence, instantOfFrame(frame), constant(960, 1000)))
        }

        val squeezed = output.scheduled.map { it.third }.first { it.size / 2 != 960 }
        assertEquals(959, squeezed.size / 2)
        assertTrue("a sample other than the music's own", squeezed.all { it.toInt() == 1000 })
    }

    @Test
    fun aClockThatJumpsFarIsFollowedAtOnceRatherThanAFrameAtATime() {
        // Past the handset's product trim band the reading is not wobble to be smoothed but a
        // move - a new offset estimate, a stalled device - and walking to it a frame per chunk
        // would take seconds.
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }
        var frame = 0L
        for (sequence in 0 until 10) {
            if (sequence > 0) frame += 960 + if (sequence == 3) ChunkPlayout.REPLACE_BEYOND_FRAMES + 60 else 0
            playout.play(AudioChunk(sequence, instantOfFrame(frame), stereo(960)))
        }

        assertEquals(frame, output.scheduled.last().first)
        assertEquals(1, playout.replaced)
    }

    @Test
    fun theBandLineSaysHowOftenTheSoundWasEdited() {
        val playout = ChunkPlayout(FakeOutput()) { 0L }

        playAJoinedRun(playout, listOf(0, 0))

        assertTrue(playout.seamBand(), playout.seamBand().endsWith("heard: 0 nudged, 0 re-placed"))
    }

    @Test
    fun theBandSaysHowManyJoinsAreInsideItRatherThanHowBadTheWorstOneWas() {
        // Eight joins land clean, one is three frames out and one is twelve. The worst of them
        // is twelve either way; what the band adds is that nine joins in ten were within three,
        // which is the difference between a run with one hiccup and a run that is coming apart.
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }
        val seams = listOf(0, 0, 0, 0, 0, 0, 0, 0, 3, 12)

        playAJoinedRun(playout, seams)

        assertEquals(10, playout.joins)
        assertEquals(0, playout.seamFramesAtQuantile(0.5))
        assertEquals(3, playout.seamFramesAtQuantile(0.9))
        assertEquals(12, playout.seamFramesAtQuantile(1.0))
        // A rank that lands between two joins takes the wider of them, so the band is never a
        // promise the run did not keep. 0.85 of ten joins is the only one of these whose rank is
        // not a whole number, which is what makes rounding it the wrong way visible.
        assertEquals(3, playout.seamFramesAtQuantile(0.85))
        assertEquals(12, playout.worstSeamFrames)
    }

    @Test
    fun aGapAndAnOverlapOfTheSameWidthSitInTheSamePlaceInTheBand() {
        // The band is about how wide the join was, and a hole four frames long costs what four
        // frames of two signals summed costs. The sign is worstSeamFrames' job, not the band's.
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }

        playAJoinedRun(playout, listOf(4, -4))

        assertEquals(2, playout.joins)
        assertEquals(4, playout.seamFramesAtQuantile(1.0))
        assertEquals(2, playout.seams)
    }

    @Test
    fun aRunWithNoJoinsYetHasNoBandRatherThanAPerfectOne() {
        // A quantile of nothing is not zero. Zero is the answer a clean run gives, and a report
        // that prints it before the first join would be claiming a result it has not got.
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }

        playout.play(AudioChunk(0, 0L, stereo(960)))

        assertEquals(0, playout.joins)
        assertEquals(ChunkPlayout.NO_JOINS_YET, playout.seamFramesAtQuantile(0.5))
    }

    @Test
    fun aHostInstantThatMovedShowsUpAsTheHostsShareOfTheSeam() {
        // 125 µs is six frames exactly, so no share has a rounding frame to hide behind.
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }

        playout.play(AudioChunk(0, 0L, stereo(960)))
        playout.play(AudioChunk(1, 20_125_000L, stereo(960)))

        assertEquals(6, playout.worstSeamFrames)
        assertEquals(6, playout.seamShareFramesAtQuantile(SeamShare.HOST, 1.0))
        assertEquals(0, playout.seamShareFramesAtQuantile(SeamShare.OFFSET, 1.0))
        assertEquals(0, playout.seamShareFramesAtQuantile(SeamShare.DEVICE, 1.0))
    }

    @Test
    fun anOffsetThatMovedBetweenTwoChunksShowsUpAsTheOffsetsShare() {
        val output = FakeOutput()
        var offset = 0L
        val playout = ChunkPlayout(output) { offset }

        playout.play(AudioChunk(0, 0L, stereo(960)))
        offset = 125_000L
        playout.play(AudioChunk(1, 20_000_000L, stereo(960)))

        assertEquals(-6, playout.worstSeamFrames)
        assertEquals(0, playout.seamShareFramesAtQuantile(SeamShare.HOST, 1.0))
        assertEquals(6, playout.seamShareFramesAtQuantile(SeamShare.OFFSET, 1.0))
        assertEquals(0, playout.seamShareFramesAtQuantile(SeamShare.DEVICE, 1.0))
    }

    @Test
    fun aDeviceReadingThatMovedOnItsOwnIsWhatIsLeftForTheDevice() {
        // The host instants are a clean chunk apart and the offset holds still, so the only thing
        // left to put the second chunk eleven frames out is the reading of the device's clock -
        // the case the one unexplained bad run on 09-23 could not be told apart from.
        val calls = intArrayOf(0)
        val output = object : FrameOutput {
            override fun frameAtLocalNanos(localNanos: Long): Long =
                localNanos * 48_000L / 1_000_000_000L + if (++calls[0] == 2) 11 else 0

            override fun schedule(samples: ShortArray, channels: Int, atFrame: Long) = true
        }
        val playout = ChunkPlayout(output) { 0L }

        playout.play(AudioChunk(0, 0L, stereo(960)))
        playout.play(AudioChunk(1, 20_000_000L, stereo(960)))

        assertEquals(11, playout.worstSeamFrames)
        assertEquals(0, playout.seamShareFramesAtQuantile(SeamShare.HOST, 1.0))
        assertEquals(0, playout.seamShareFramesAtQuantile(SeamShare.OFFSET, 1.0))
        assertEquals(11, playout.seamShareFramesAtQuantile(SeamShare.DEVICE, 1.0))
    }

    /**
     * The band is read by whoever is reporting - a window polling twice a second, a command-line
     * loop - while the chunk thread is still counting. A read that landed between the count and
     * the histogram walked off the end of it and threw.
     */
    @Test
    fun theBandCanBeReadWhileChunksAreStillArriving() {
        val playout = ChunkPlayout(FakeOutput()) { 0L }
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val reader = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    playout.seamBand()
                    playout.seamShares()
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }.apply { start() }
        try {
            // Alternating widths so the histogram keeps gaining keys, which is when a read can miss one.
            // Reduced from the brief's 200 000: FakeOutput keeps every scheduled chunk, and that many
            // 960-frame stereo buffers overruns the test JVM's default heap well before the race shows.
            repeat(20_000) { sequence ->
                playout.play(chunkAt(sequence, sequence * 20_000_000L + (sequence % 7) * 21_000L))
            }
        } finally {
            reader.interrupt()
            reader.join(2_000)
        }
        failure.get()?.let { throw AssertionError("reading the band while playing threw", it) }
    }

    /** A chunk of the standard size, landing at [playAtHostNanos]. */
    private fun chunkAt(sequence: Int, playAtHostNanos: Long) =
        AudioChunk(sequence, playAtHostNanos, ByteArray(ChunkCodec.FRAMES_PER_CHUNK * 2 * 2))

    /**
     * Plays one chunk per entry in [seamFrames], each landing that many frames from where its
     * neighbour ran out. The first entry is the join between chunk 0 and chunk 1, so the run is
     * one chunk longer than the list.
     */
    private fun playAJoinedRun(playout: ChunkPlayout, seamFrames: List<Int>) {
        var frame = 0L
        playout.play(AudioChunk(0, instantOfFrame(frame), stereo(960)))
        seamFrames.forEachIndexed { index, seam ->
            frame += 960 + seam
            playout.play(AudioChunk(index + 1, instantOfFrame(frame), stereo(960)))
        }
    }

    /** The host instant that lands exactly on [frame], rounding up so the output's floor agrees. */
    private fun instantOfFrame(frame: Long) = (frame * 1_000_000_000L + 47_999L) / 48_000L

    private fun stereo(frames: Int) = ByteArray(frames * 2 * 2)

    /** Stereo, both channels the same value rising by ten a frame. */
    private fun ramp(frames: Int) = pcmOf(frames) { it * 10 }

    private fun constant(frames: Int, value: Int) = pcmOf(frames) { value }

    private fun pcmOf(frames: Int, value: (Int) -> Int): ByteArray {
        val pcm = ByteArray(frames * 2 * 2)
        for (f in 0 until frames) for (c in 0 until 2) {
            val v = value(f)
            pcm[(f * 2 + c) * 2] = (v and 0xFF).toByte()
            pcm[(f * 2 + c) * 2 + 1] = (v shr 8).toByte()
        }
        return pcm
    }

    /** The sample at [index] (in samples, not bytes) of little-endian [pcm]. */
    private fun sampleOf(pcm: ByteArray, index: Int): Int =
        ((pcm[index * 2].toInt() and 0xFF) or (pcm[index * 2 + 1].toInt() shl 8)).toShort().toInt()
}
