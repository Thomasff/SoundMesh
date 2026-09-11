package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomColoursTest {
    @Test
    fun givesAHandsetTheColourItPrefersWhenNobodyIsUsingIt() {
        val alone = namePreferring(5)

        val colours = RoomColours().reconcile(listOf(alone))

        assertEquals(5, colours[alone])
    }

    @Test
    fun movesTheSecondHandsetAlongWhenTwoWantTheSameColour() {
        val (first, second) = twoPreferring(5)

        val colours = RoomColours().reconcile(listOf(first, second))

        assertEquals(5, colours[first])
        assertEquals(6, colours[second])
    }

    @Test
    fun wrapsPastTheEndOfThePalette() {
        val (first, second) = twoPreferring(PeerBadge.COLOURS - 1)

        val colours = RoomColours().reconcile(listOf(first, second))

        assertEquals(PeerBadge.COLOURS - 1, colours[first])
        assertEquals(0, colours[second])
    }

    /**
     * The colour a handset was pushed onto is its colour for as long as it stays, even once the
     * handset that pushed it there is gone.
     *
     * A room that re-ran the probe on every roster change would recolour a handset the moment
     * another one dropped - which is precisely the moment somebody is staring at the screen trying
     * to work out which one dropped. The one event this identity exists to report is the one that
     * would change it.
     */
    @Test
    fun keepsAColourAfterTheHandsetThatPushedItThereLeaves() {
        val (first, second) = twoPreferring(5)
        val colours = RoomColours()
        colours.reconcile(listOf(first, second))

        val alone = colours.reconcile(listOf(second))

        assertEquals("the second handset went back to what it preferred", 6, alone[second])
    }

    @Test
    fun doesNotMoveAHandsetWhenAnotherJoins() {
        val (first, second) = twoPreferring(5)
        val colours = RoomColours()
        val before = colours.reconcile(listOf(second))

        val after = colours.reconcile(listOf(second, first))

        assertEquals(before[second], after[second])
        assertNotEquals(after[second], after[first])
    }

    /** A colour is only held while its handset is there, or a room slowly runs out of them. */
    @Test
    fun letsALaterHandsetTakeAColourItsOwnerLeftBehind() {
        val (first, second) = twoPreferring(5)
        val colours = RoomColours()
        colours.reconcile(listOf(first))
        colours.reconcile(emptyList<String>())

        val after = colours.reconcile(listOf(second))

        assertEquals("nothing is using 5 any more", 5, after[second])
    }

    /**
     * Past a full palette the probe has nowhere to go. It hands out a duplicate rather than
     * refusing a name a place, because a thirteenth handset with no colour is a handset missing
     * from the drawing, and a drawing that is one short is the fault nobody thinks to check.
     */
    @Test
    fun handsOutADuplicateRatherThanFailingOnceThePaletteIsFull() {
        val crowd = (0 until PeerBadge.COLOURS + 1).map { namePreferring(it % PeerBadge.COLOURS, skip = it / PeerBadge.COLOURS) }

        val colours = RoomColours().reconcile(crowd)

        assertEquals(crowd.toSet(), colours.keys)
        assertTrue(colours.values.all { it in 0 until PeerBadge.COLOURS })
    }

    /** The same room twice reads the same, however the roster happens to be ordered. */
    @Test
    fun doesNotDependOnTheOrderTheRosterArrivesIn() {
        val (first, second) = twoPreferring(5)
        val third = namePreferring(9)

        val forwards = RoomColours().reconcile(listOf(first, second, third))
        val backwards = RoomColours().reconcile(listOf(third, second, first))

        assertEquals(forwards, backwards)
    }

    private fun twoPreferring(colour: Int): Pair<String, String> =
        namePreferring(colour) to namePreferring(colour, skip = 1)

    /** A name whose preferred colour is [colour], skipping the first [skip] that qualify. */
    private fun namePreferring(colour: Int, skip: Int = 0): String {
        var remaining = skip
        for (index in 0 until 100_000) {
            val name = "%016x".format(index)
            if (PeerBadge.preferredColour(name) != colour) continue
            if (remaining == 0) return name
            remaining--
        }
        throw AssertionError("no name prefers colour $colour")
    }
}
