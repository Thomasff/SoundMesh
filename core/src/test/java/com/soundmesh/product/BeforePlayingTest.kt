package com.soundmesh.product

import com.soundmesh.probe.sync.Carried
import org.junit.Assert.assertEquals
import org.junit.Test

class BeforePlayingTest {
    private fun peer(id: String, carrying: Carried) = PeerCarrying(id, "name-$id", carrying)

    @Test
    fun aMeasuredHostWithEveryPeerCalibratedIsAskedNothing() {
        val peers = listOf(peer("a", Carried.SOMETHING), peer("b", Carried.APPROXIMATE))
        assertEquals(emptyList<BeforePlaying>(), beforePlaying(ownLeadMissing = false, peers = peers))
    }

    @Test
    fun theOwnLeadIsAskedFirstAndThenEachUncalibratedPeerInTurn() {
        val peers = listOf(peer("a", Carried.NOTHING), peer("b", Carried.SOMETHING), peer("c", Carried.NOTHING))
        assertEquals(
            listOf(
                BeforePlaying.OwnLead,
                BeforePlaying.Uncalibrated("a", "name-a"),
                BeforePlaying.Uncalibrated("c", "name-c")
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
            listOf(BeforePlaying.Uncalibrated("d", "name-d")),
            beforePlaying(ownLeadMissing = false, peers = peers)
        )
    }
}
