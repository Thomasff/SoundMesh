package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A frame timeline that is a straight line through the origin, which is what makes a sign error
 * in the clock offset visible: frame zero is local nanosecond zero, so a chunk's landing frame is
 * a number that can be worked out by hand.
 */
private class FakeOutput(private val sampleRate: Int = 48000) : FrameOutput {
    var firstSchedulableFrame: Long = 0
    val scheduled = ArrayList<Triple<Long, Int, ShortArray>>()

    override fun frameAtLocalNanos(localNanos: Long): Long = localNanos * sampleRate / 1_000_000_000L

    override fun schedule(samples: ShortArray, channels: Int, atFrame: Long): Boolean {
        if (atFrame < firstSchedulableFrame) return false
        scheduled.add(Triple(atFrame, channels, samples))
        return true
    }
}

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

    private fun stereo(frames: Int) = ByteArray(frames * 2 * 2)
}
