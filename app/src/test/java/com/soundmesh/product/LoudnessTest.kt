package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessTest {
    private fun pcm(vararg samples: Short): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for ((i, s) in samples.withIndex()) {
            bytes[i * 2] = (s.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun `silence is zero`() {
        val quiet = pcm(0, 0, 0, 0)
        assertEquals(0f, loudnessOf(quiet, 0, quiet.size), 1e-6f)
    }

    @Test
    fun `full scale is one`() {
        val loud = pcm(Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE)
        assertEquals(1f, loudnessOf(loud, 0, loud.size), 0.001f)
    }

    // Loudness is about size, not sign - a negative swing is exactly as loud as a positive one.
    @Test
    fun `a negative swing is as loud as a positive one`() {
        val up = pcm(10000, 10000)
        val down = pcm(-10000, -10000)
        assertEquals(loudnessOf(up, 0, up.size), loudnessOf(down, 0, down.size), 1e-6f)
    }

    @Test
    fun `quieter audio reads lower`() {
        val loud = pcm(20000, 20000)
        val soft = pcm(2000, 2000)
        assertTrue(loudnessOf(soft, 0, soft.size) < loudnessOf(loud, 0, loud.size))
    }

    // The renderer hands over a slice of a bigger buffer, so the offset has to be honoured -
    // reading from zero would measure whatever came before, which is the previous chunk.
    @Test
    fun `only the slice it was given is measured`() {
        val mixed = pcm(0, 0, Short.MAX_VALUE, Short.MAX_VALUE)
        assertEquals(0f, loudnessOf(mixed, 0, 4), 1e-6f)
        assertEquals(1f, loudnessOf(mixed, 4, 4), 0.001f)
    }

    // A truncated final chunk must not throw on the dangling byte, and must not be read as a
    // whole sample either.
    @Test
    fun `an odd number of bytes is not read past the end`() {
        val odd = ByteArray(5) { 0 }
        assertEquals(0f, loudnessOf(odd, 0, 5), 1e-6f)
    }
}
