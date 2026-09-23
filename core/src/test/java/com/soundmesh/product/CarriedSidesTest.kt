package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which half each device carried, for the ones not in the room just now - the one memory the
 * handset host and the desktop host keep by the same rules, so a device is handed the same half
 * whichever of them it comes back to.
 */
class CarriedSidesTest {
    private val a = "a1b2c3d4e5f60718"
    private val b = "0918273645abcdef"
    private val c = "1122334455667788"

    /**
     * The handset used to clear the memory against the room as it now stood, so a device coming
     * back was struck from it the moment it arrived and handed nothing.
     */
    @Test
    fun aDeviceThatComesBackIsHandedItsHalfAgain() {
        val sides = CarriedSides()
        assertEquals(emptySet<String>(), sides.reconciled(setOf(b), before = listOf(a, b), after = listOf(a)))
        assertEquals(setOf(b), sides.reconciled(emptySet(), before = listOf(a), after = listOf(a, b)))
    }

    /** What a device present carries is what it carries now: a half taken away is not given back. */
    @Test
    fun aHalfTakenAwayStaysTakenAway() {
        val sides = CarriedSides()
        assertEquals(setOf(b), sides.reconciled(setOf(b), before = listOf(a, b), after = listOf(a, b, c)))
        assertEquals(emptySet<String>(), sides.reconciled(emptySet(), before = listOf(a, b, c), after = listOf(a, b)))
        assertEquals(emptySet<String>(), sides.reconciled(emptySet(), before = listOf(a, b), after = listOf(a)))
        assertEquals(emptySet<String>(), sides.reconciled(emptySet(), before = listOf(a), after = listOf(a, b)))
    }

    /** What goes on disk is the absent devices' halves as well, or a restart forgets them. */
    @Test
    fun whatIsKeptIncludesTheDevicesNotHere() {
        val sides = CarriedSides()
        sides.reconciled(setOf(b, c), before = listOf(a, b, c), after = listOf(a, c))
        assertEquals(setOf(b, c), sides.toKeep(setOf(c), present = listOf(a, c)))
    }

    /** What the saved drawing says is taken as remembered, and handed out as devices arrive. */
    @Test
    fun whatWasSavedIsHandedOutAsDevicesArrive() {
        val sides = CarriedSides()
        sides.remember(setOf(b))
        assertEquals(emptySet<String>(), sides.reconciled(emptySet(), before = emptyList(), after = listOf(a)))
        assertEquals(setOf(b), sides.reconciled(emptySet(), before = listOf(a), after = listOf(a, b)))
    }
}
