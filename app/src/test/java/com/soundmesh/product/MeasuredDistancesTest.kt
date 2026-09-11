package com.soundmesh.product

import com.soundmesh.probe.sync.StoredRoomField
import com.soundmesh.probe.sync.StoredSeparation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Where the room screen's distances come from: two files, one fact each.
 *
 * They overlap - a room measures the pairs this handset is in no differently from the pairs it is
 * not - and neither file records when it was written. So which one answers a shared pair has to be
 * decided once, here, rather than by whichever happens to be read second.
 */
class MeasuredDistancesTest {
    @get:Rule val folder = TemporaryFolder()

    private val self = "a1b2c3d4e5f60718"
    private val one = "0918273645abcdef"
    private val two = "1122334455667788"

    private val room = listOf(self, one, two)

    @Test
    fun aHandsetThatHasMeasuredNothingHasNoDistances() {
        assertEquals(emptyMap<Pair<String, String>, Double>(), measuredDistances(folder.root, self, room))
    }

    /** The pair flow's own file answers the pairs this handset is an end of, either way round. */
    @Test
    fun aPairThisHandsetIsInComesFromThePerPeerFile() {
        StoredSeparation(folder.root, one).write(2.02)

        val distances = measuredDistances(folder.root, self, room)

        assertEquals(2.02, distances[self to one]!!, 1e-9)
        assertEquals(2.02, distances[one to self]!!, 1e-9)
    }

    /** And the room file answers the pairs it does not, which nothing else can measure at all. */
    @Test
    fun aPairBetweenTwoOtherHandsetsComesFromTheRoomFile() {
        StoredRoomField(folder.root).write(mapOf((one to two) to 2.51))

        val distances = measuredDistances(folder.root, self, room)

        assertEquals(2.51, distances[one to two]!!, 1e-9)
        assertEquals(2.51, distances[two to one]!!, 1e-9)
    }

    /**
     * And where both hold the same pair, the per-peer file wins.
     *
     * Not because it is likelier to be newer - nothing here knows that - but because it is the one
     * every arm that measures a distance writes, including the room. Letting the room file answer
     * a pair the pair flow also keeps would give one fact two owners, and the disagreement would
     * be a distance that is quietly wrong rather than an error anybody sees.
     */
    @Test
    fun whereBothFilesHoldAPairThePerPeerFileAnswers() {
        StoredRoomField(folder.root).write(mapOf((self to one) to 9.00, (one to two) to 2.51))
        StoredSeparation(folder.root, one).write(2.02)

        val distances = measuredDistances(folder.root, self, room)

        assertEquals(2.02, distances[self to one]!!, 1e-9)
        assertEquals(2.51, distances[one to two]!!, 1e-9)
    }

    /**
     * A field outlives the room it was measured in, and a handset that has gone home is still in
     * it. The per-peer half is asked only about handsets in the room; the room half is not, and
     * the screen drops what it cannot draw - see MeasuredDistances in SpatialPanel.
     */
    @Test
    fun aHandsetNoLongerInTheRoomKeepsItsPerPeerDistanceOutOfTheAnswer() {
        StoredSeparation(folder.root, two).write(3.00)

        val distances = measuredDistances(folder.root, self, listOf(self, one))

        assertNull(distances[self to two])
    }

    /** A handset with no name for itself yet can still show what the room measured about others. */
    @Test
    fun aHandsetThatDoesNotKnowItsOwnNameStillReadsTheRoom() {
        StoredRoomField(folder.root).write(mapOf((one to two) to 2.51))

        assertEquals(2.51, measuredDistances(folder.root, null, room)[one to two]!!, 1e-9)
    }
}
