package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk
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
    fun aChunkThatLandsPastItsNeighbourIsCountedAsASeam() {
        // A gap leaves a hole in the sound and an overlap sums two signals; the sign is what
        // says which. Both are the same fault and both have to be visible.
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }

        playout.play(AudioChunk(0, 0L, stereo(960)))
        playout.play(AudioChunk(1, 20_100_000L, stereo(960)))

        assertEquals(listOf(0L, 964L), output.scheduled.map { it.first })
        assertEquals(4, playout.worstSeamFrames)
        assertEquals(1, playout.seams)
    }

    @Test
    fun aMissingSequenceIsNotReportedAsASeam() {
        // The host dropped one, or the network did. That is a hole a whole chunk wide and it
        // shows up in the sequence itself; calling it a seam would bury the one-frame kind
        // this counter exists to find.
        val output = FakeOutput()
        val playout = ChunkPlayout(output) { 0L }

        playout.play(AudioChunk(0, 0L, stereo(960)))
        playout.play(AudioChunk(2, 40_000_000L, stereo(960)))

        assertEquals(0, playout.worstSeamFrames)
        assertEquals(0, playout.seams)
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
}
