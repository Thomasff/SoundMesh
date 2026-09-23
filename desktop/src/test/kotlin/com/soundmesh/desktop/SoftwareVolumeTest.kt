package com.soundmesh.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareVolumeTest {
    private fun chunk(value: Short, frames: Int = 960, channels: Int = 2) = ShortArray(frames * channels) { value }

    /** Untouched, the samples go out as they came: a room that never set a volume sounds as before. */
    @Test
    fun atFullVolumeTheChunkGoesOutUntouched() {
        val out = FakeOutput()
        val samples = chunk(1000)
        GainOutput(out, SoftwareVolume()).schedule(samples, 2, 0)
        assertSame(samples, out.scheduled.single().third)
    }

    /** Half way is the square: a quarter of the amplitude, and the caller's buffer is left alone. */
    @Test
    fun aSetVolumeScalesByItsSquareWithoutTouchingTheCallersBuffer() {
        val out = FakeOutput()
        val volume = SoftwareVolume().apply { set(50) }
        val gained = GainOutput(out, volume)
        val samples = chunk(1000)
        gained.schedule(samples, 2, 0)
        assertTrue(samples.all { it == 1000.toShort() })
        assertTrue(out.scheduled.single().third.all { it == 250.toShort() })
    }

    /**
     * A change lands as a ramp across one chunk, not a step at its edge: the first frame is
     * still near the old level and the last is at the new one, and the chunk after is flat.
     */
    @Test
    fun aChangeIsRampedAcrossTheChunkItLandsIn() {
        val out = FakeOutput()
        val volume = SoftwareVolume()
        val gained = GainOutput(out, volume)
        gained.schedule(chunk(1000), 2, 0)
        volume.set(0)
        gained.schedule(chunk(1000), 2, 960)
        gained.schedule(chunk(1000), 2, 1920)
        val ramp = out.scheduled[1].third
        assertTrue("first frame jumped: ${ramp[0]}", ramp[0] > 990)
        assertEquals(0, ramp[ramp.size - 1].toInt())
        assertArrayEquals(ShortArray(1920), out.scheduled[2].third)
    }

    @Test
    fun restorePutsItBackToFull() {
        val volume = SoftwareVolume().apply { set(30) }
        volume.restore()
        assertEquals(SoftwareVolume.FULL, volume.percent)
        assertEquals(1.0, volume.gain(), 0.0)
    }
}
