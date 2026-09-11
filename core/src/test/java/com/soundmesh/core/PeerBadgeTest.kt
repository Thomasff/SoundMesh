package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerBadgeTest {
    @Test
    fun givesOneNameTheSameBadgeEveryTime() {
        val name = "3f2a9c1d4b6e8075"

        assertEquals(PeerBadge.numberOf(name), PeerBadge.numberOf(name))
        assertEquals(PeerBadge.preferredColour(name), PeerBadge.preferredColour(name))
    }

    /**
     * The number is what a person says out loud and what a written note carries, so it has to fit
     * in the room's own vocabulary: two digits, said as a number rather than spelled as characters.
     */
    @Test
    fun keepsTheNumberToTwoDigitsAndTheColourInThePalette() {
        for (index in 0 until 500) {
            val name = name(index)
            val number = PeerBadge.numberOf(name)
            val colour = PeerBadge.preferredColour(name)

            assertTrue("two digits, got $number for $name", number in 0..99)
            assertTrue("outside the palette: $colour for $name", colour in 0 until PeerBadge.COLOURS)
        }
    }

    /**
     * A derivation that ignored most of the name would pass every test above while handing the
     * whole room one colour. Names differing only in their last character are the case that catches
     * it: a fold that stopped early, or one that only read the front, returns the same badge here.
     */
    @Test
    fun readsTheWholeNameRatherThanOneEndOfIt() {
        val front = (0 until 16).map { PeerBadge.numberOf("0000000000000000".take(15) + "%x".format(it)) }
        val back = (0 until 16).map { PeerBadge.numberOf("%x".format(it) + "000000000000000") }

        assertTrue("the last character decides nothing: $front", front.toSet().size > 1)
        assertTrue("the first character decides nothing: $back", back.toSet().size > 1)
    }

    /** Every colour has to be reachable, or the palette is smaller than it claims. */
    @Test
    fun spreadsAcrossTheWholePalette() {
        val used = (0 until 500).map { PeerBadge.preferredColour(name(it)) }.toSet()

        assertEquals((0 until PeerBadge.COLOURS).toSet(), used)
    }

    /**
     * The number and the colour are the two halves of one identity, and the pair is what tells two
     * handsets apart in writing once the colour has been taken away. Two handsets that share a
     * number must be able to differ in colour, so the two cannot be read off the same digits.
     */
    @Test
    fun doesNotMakeTheColourAFunctionOfTheNumber() {
        val sharingANumber = (0 until 2000).map { name(it) }
            .groupBy { PeerBadge.numberOf(it) }
            .values
            .first { it.size >= 3 }

        val colours = sharingANumber.map { PeerBadge.preferredColour(it) }.toSet()
        assertTrue("one number always means one colour: $sharingANumber", colours.size > 1)
    }

    private fun name(index: Int) = "%016x".format(index)
}
