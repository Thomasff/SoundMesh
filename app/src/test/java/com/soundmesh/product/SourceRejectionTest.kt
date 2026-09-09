package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceRejectionTest {
    /** The seven SourceUnusable throws, spelled exactly as the two file sources spell them. */
    private val codes = listOf(
        "SOURCE_FILE_MISSING",
        "SOURCE_FILE_NO_AUDIO",
        "SOURCE_FILE_TOO_SHORT",
        "SOURCE_FILE_DECODE_STALLED",
        "SOURCE_FILE_FORMAT_UNKNOWN",
        "SOURCE_FILE_FORMAT_UNUSABLE",
        "SOURCE_FILE_CHANNELS_UNUSABLE"
    )

    @Test
    fun everyKnownCodeSaysSomethingOfItsOwn() {
        val words = codes.map { SourceRejection.of(it) }
        assertEquals(codes.size, words.toSet().size)
        for (word in words) assertNotEquals(0, word)
    }

    /**
     * An exception with no code at all still has to reach the screen: "nothing happened when I
     * pressed choose" is the one outcome a person cannot act on.
     */
    @Test
    fun anUnknownFailureStillSaysSomething() {
        assertNotEquals(0, SourceRejection.of(null))
        assertNotEquals(0, SourceRejection.of("SOMETHING_ELSE"))
    }
}
