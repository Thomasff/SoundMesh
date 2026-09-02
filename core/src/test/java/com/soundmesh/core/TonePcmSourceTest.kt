package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TonePcmSourceTest {
    @Test
    fun producesStereoPcm16OfTheRequestedLength() {
        assertEquals(960 * 2 * 2, TonePcmSource().fill(0, 960).size)
    }

    @Test
    fun givesEveryDeviceTheSameBytesForTheSameFrameIndex() {
        val left = TonePcmSource().fill(123_456, 960)
        val right = TonePcmSource().fill(123_456, 960)

        assertEquals(true, left.contentEquals(right))
    }

    /** Phase comes from the absolute frame index, so chunk boundaries cannot click. */
    @Test
    fun joinsSeamlesslyAcrossChunkBoundaries() {
        val source = TonePcmSource()
        val joined = source.fill(0, 960) + source.fill(960, 960)

        assertEquals(true, joined.contentEquals(source.fill(0, 1920)))
    }
}
