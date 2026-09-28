package com.soundmesh.product

import com.soundmesh.probe.sync.Carried
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeforePlayingTest {
    private fun peer(id: String, carrying: Carried) = PeerCarrying(id, "name-$id", carrying)

    @Test
    fun aMeasuredHostWithEveryPeerCalibratedIsAskedNothing() {
        val peers = listOf(peer("a", Carried.SOMETHING), peer("b", Carried.APPROXIMATE))
        assertEquals(emptyList<BeforePlaying>(), beforePlaying(ownLeadMissing = false, peers = peers))
    }

    /** Nobody to play to comes first, before anything about how well they would play. */
    @Test
    fun aHostWithNobodyConnectedIsToldThatFirst() {
        assertEquals(
            listOf(BeforePlaying.NobodyJoined, BeforePlaying.OwnLead),
            beforePlaying(ownLeadMissing = true, peers = emptyList())
        )
        assertEquals(listOf(BeforePlaying.NobodyJoined), beforePlaying(ownLeadMissing = false, peers = emptyList()))
    }

    /** Every uncalibrated handset in one question: one room round fixes them all. */
    @Test
    fun theOwnLeadIsAskedFirstAndThenEveryUncalibratedPeerAtOnce() {
        val peers = listOf(peer("a", Carried.NOTHING), peer("b", Carried.SOMETHING), peer("c", Carried.NOTHING))
        assertEquals(
            listOf(
                BeforePlaying.OwnLead,
                BeforePlaying.Uncalibrated(listOf("name-a", "name-c"))
            ),
            beforePlaying(ownLeadMissing = true, peers = peers)
        )
    }

    /**
     * Only a handset that says it carries nothing. One on a room round is roughly right, and one
     * too old to say anything is very likely fine - see Carried.UNSAID.
     */
    @Test
    fun onlyAHandsetCarryingNothingIsAskedAbout() {
        val peers = listOf(
            peer("a", Carried.APPROXIMATE),
            peer("b", Carried.UNSAID),
            peer("c", Carried.SOMETHING),
            peer("d", Carried.NOTHING)
        )
        assertEquals(
            listOf(BeforePlaying.Uncalibrated(listOf("name-d"))),
            beforePlaying(ownLeadMissing = false, peers = peers)
        )
    }

    @Test
    fun theRoomRoundIsDueWhileAnyDeviceCarriesNothing() {
        assertTrue(roomRoundDue(listOf(Carried.APPROXIMATE, Carried.NOTHING)))
        assertFalse(roomRoundDue(listOf(Carried.APPROXIMATE, Carried.SOMETHING, Carried.UNSAID)))
        assertFalse(roomRoundDue(emptyList()))
    }

    /** Done means somebody joined and nobody is due - an empty room has measured nothing. */
    @Test
    fun theRoomRoundReadsAsDoneOnlyWithSomebodyJoinedAndNobodyDue() {
        assertTrue(roomRoundDone(listOf(Carried.APPROXIMATE, Carried.SOMETHING)))
        assertFalse(roomRoundDone(listOf(Carried.APPROXIMATE, Carried.NOTHING)))
        assertFalse(roomRoundDone(emptyList()))
    }

    /** A handset already paired is going back to be measured again, and needs no telling. */
    @Test
    fun theFineCalibrationNoteIsSaidUnlessTheDeviceIsAlreadyPairedOrSomebodyTicked() {
        assertTrue(saysFineCalibrationFirst(Carried.APPROXIMATE, ticked = false))
        assertTrue(saysFineCalibrationFirst(Carried.NOTHING, ticked = false))
        assertTrue(saysFineCalibrationFirst(Carried.UNSAID, ticked = false))
        assertFalse(saysFineCalibrationFirst(Carried.SOMETHING, ticked = false))
        assertFalse(saysFineCalibrationFirst(Carried.APPROXIMATE, ticked = true))
    }
}
