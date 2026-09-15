package com.soundmesh.product

import com.soundmesh.core.RoomExcuse
import com.soundmesh.probe.sync.Carried
import org.junit.Assert.assertEquals
import org.junit.Test

class StandingRosterTest {
    @Test
    fun putsEachStandingHandsetOnItsOwnLine() {
        val rows = rosterOf(
            peerIds = listOf("aaaa1111", "bbbb2222"),
            name = { if (it == "aaaa1111") "Magic6" else "X10" },
            carrying = mapOf("aaaa1111" to Carried.SOMETHING, "bbbb2222" to Carried.NOTHING),
            quiet = emptySet(),
            excuses = emptyMap()
        )

        assertEquals(listOf("Magic6", "X10"), rows.map { it.name })
        assertEquals(listOf(Carried.SOMETHING, Carried.NOTHING), rows.map { it.carrying })
    }

    /**
     * A handset that has never said what it is called still has to be pointed at.
     *
     * The last four of the identity rather than the whole of it, on the same grounds as
     * `nameOf`: a name nobody can read out loud is not a name, and four characters is the
     * shortest thing somebody can say into a phone call.
     */
    @Test
    fun fallsBackToSomethingAPersonCanSayOutLoud() {
        val rows = rosterOf(
            peerIds = listOf("aaaa1111"),
            name = { null },
            carrying = emptyMap(),
            quiet = emptySet(),
            excuses = emptyMap()
        )

        assertEquals("1111", rows.single().name)
    }

    /**
     * Saying nothing is its own answer and not the same as saying nothing is carried.
     *
     * A build from before that message would otherwise be sent off to recalibrate a handset that
     * is already fine - which is the distinction [Carried] was split three ways for.
     */
    @Test
    fun aHandsetThatNeverSaidIsNotAHandsetThatSaidNo() {
        val rows = rosterOf(
            peerIds = listOf("aaaa1111"),
            name = { "Magic6" },
            carrying = emptyMap(),
            quiet = emptySet(),
            excuses = emptyMap()
        )

        assertEquals(Carried.UNSAID, rows.single().carrying)
    }

    /** Quiet and the last excuse are both per handset, because what fixes them is per handset. */
    @Test
    fun carriesWhatIsWrongWithOneHandsetOnThatHandsetsLine() {
        val rows = rosterOf(
            peerIds = listOf("aaaa1111", "bbbb2222"),
            name = { "phone" },
            carrying = emptyMap(),
            quiet = setOf("bbbb2222"),
            excuses = mapOf("bbbb2222" to RoomExcuse.NO_MICROPHONE)
        )

        assertEquals(listOf(false, true), rows.map { it.quiet })
        assertEquals(listOf(null, RoomExcuse.NO_MICROPHONE), rows.map { it.excuse })
    }

    /**
     * An excuse from a handset that has since left is not drawn.
     *
     * Excuses are kept past the socket on purpose - the sentence has to survive long enough for
     * somebody to read it - so this list, which is the standing handsets and only those, is where
     * that keeping stops.
     */
    @Test
    fun doesNotDrawAnExcuseFromAHandsetThatIsNoLongerHere() {
        val rows = rosterOf(
            peerIds = listOf("aaaa1111"),
            name = { "phone" },
            carrying = emptyMap(),
            quiet = emptySet(),
            excuses = mapOf("gone9999" to RoomExcuse.NO_MICROPHONE)
        )

        assertEquals(listOf("aaaa1111"), rows.map { it.peerId })
        assertEquals(null, rows.single().excuse)
    }
}
