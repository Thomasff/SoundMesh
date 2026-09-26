package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk
import com.soundmesh.probe.sync.RoundChunks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A measuring round's sound on this machine, on a straight-line output. */
class DesktopRoundTest {
    private val offset = 5_000_000_000L
    private val hostNanosNow = { System.nanoTime() + offset }
    private val frames = RoundChunks.FRAMES_PER_CHUNK

    private fun chunk(sequence: Int, atHostNanos: Long) = AudioChunk(sequence, atHostNanos, ByteArray(frames * 2 * 2))

    /**
     * The host instant becomes this machine's by the clock offset, and the frame is the output's for
     * that instant; the chunks after it in sequence are butted on, so no sweep has a frame's wobble
     * inside it.
     */
    @Test
    fun aChunkLandsOnTheFrameForItsHostInstantAndItsSuccessorsAreButtedOn() {
        val fake = FakeSpeakers()
        val speaker = FrameRoundSpeaker(fake::open, SoftwareVolume(), hostNanosNow)
        speaker.start(endAtHostNanos = hostNanosNow())
        val at = hostNanosNow() + 2_000_000_000L
        speaker.submit(chunk(RoundChunks.CHIRP_SEQUENCE_BASE, at))
        speaker.submit(chunk(RoundChunks.CHIRP_SEQUENCE_BASE + 1, at + RoundChunks.CHUNK_NANOS + 777))

        val expected = fake.output.frameAtLocalNanos(at - offset)
        val first = fake.output.scheduled[0].first
        assertTrue("landed on $first for $expected", kotlin.math.abs(first - expected) <= 1)
        assertEquals(first + frames, fake.output.scheduled[1].first)
        assertNull(speaker.failure)
    }

    /** A late chirp is a failure of the round; a late warm-up chunk is not. */
    @Test
    fun aLateChirpFailsTheRoundAndALateToneDoesNot() {
        val fake = FakeSpeakers()
        fake.output.firstSchedulableFrame = Long.MAX_VALUE
        val speaker = FrameRoundSpeaker(fake::open, SoftwareVolume(), hostNanosNow)
        speaker.start(endAtHostNanos = hostNanosNow())

        speaker.submit(chunk(0, hostNanosNow()))
        assertNull(speaker.failure)

        speaker.submit(chunk(RoundChunks.CHIRP_SEQUENCE_BASE, hostNanosNow()))
        assertNotNull(speaker.failure)
    }

    /** The speakers are this round's, and let go of when it ends. */
    @Test
    fun finishingLetsGoOfTheSpeakers() {
        val fake = FakeSpeakers()
        val speaker = FrameRoundSpeaker(fake::open, SoftwareVolume(), hostNanosNow)
        speaker.start(endAtHostNanos = hostNanosNow() + 50_000_000L)

        val report = speaker.finish()

        assertTrue(fake.closed)
        assertTrue(report!!, report.contains("\"lateChirps\":0"))
    }
}
