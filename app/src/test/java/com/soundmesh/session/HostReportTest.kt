package com.soundmesh.session

import com.soundmesh.probe.R
import com.soundmesh.product.SessionReadout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a host adds to its renderer's report, and whether the screen can read it back.
 *
 * A HostSession cannot be built and started off a device - it binds three sockets and its renderer
 * opens an AudioTrack - so the fields are assembled by a function of their values and that function
 * is what is tested. The precedent is trackProfileJson and spatialShaped: the decision worth
 * guarding is hoisted out of the thing that needs hardware.
 *
 * Half of these go through SessionReadout rather than asserting on the text. The two halves are
 * written in different packages and read the same names, and a name that agrees with itself in
 * only one of them is the failure worth catching - asserting the text here would pass while the
 * screen showed nothing.
 */
class HostReportTest {
    private val host = "a1b2c3d4e5f60718"
    private val near = "0918273645abcdef"
    private val far = "1122334455667788"

    private fun report(sinks: Int, room: List<String>, lateChunks: Int = 0): String =
        "{" + hostReportFields(
            maxBroadcastNanos = 1_000_000L,
            generated = 3_010,
            droppedToSinks = 0,
            sinks = sinks,
            roomPeerIds = room,
            unnamedSinks = 0,
            lateChunks = lateChunks
        ) + "}"

    private fun valueOf(report: String, label: Int): String =
        SessionReadout.counters(report).first { it.label == label }.value

    /**
     * The one mark a source that fell behind leaves anywhere.
     *
     * A host reads a chunk 1.5 s before it is heard and the scheduler holds three seconds more,
     * so a decoder that stumbled and caught up again changes no other number in the run - not the
     * timeline, not the trims, not what anybody heard. Without this the only visible version of
     * that fault is the one that never recovers.
     */
    @Test
    fun aHostSaysHowOftenItWaitedForItsSource() {
        val text = report(sinks = 1, room = listOf(host, near), lateChunks = 7)
        assertEquals("7", valueOf(text, R.string.counter_source_late))
    }

    /** Zero is a reading. A row that appeared only on a bad session could not be watched. */
    @Test
    fun aHostThatNeverWaitedStillSaysSo() {
        assertEquals("0", valueOf(report(sinks = 1, room = listOf(host, near)), R.string.counter_source_late))
    }

    /** The host first, because that is the order the drawing is in and the order a listener reads. */
    @Test
    fun theWholeRosterIsOneValueTheScreenCanReadBack() {
        val text = report(sinks = 2, room = listOf(host, near, far))

        assertTrue(text.contains("\"roomPeerIds\":\"$host,$near,$far\""))
        assertEquals("a1b2 0918 1122", valueOf(text, R.string.counter_room))
    }

    /**
     * Two announced and two being sent audio: a whole room. The pair is the reading rather than
     * either number alone - a count of connections says nothing about how many were expected, and
     * a roster says nothing about which of them is still listening.
     */
    @Test
    fun aWholeRoomReadsTheSameOnBothCounts() {
        assertEquals("2/2", valueOf(report(sinks = 2, room = listOf(host, near, far)), R.string.counter_sinks))
    }

    /**
     * One handset gone, which is as far as this can honestly go: the audio sockets are anonymous,
     * so the screen can say how many are still connected and not which one is not.
     */
    @Test
    fun aHandsetThatLeftShowsAsTheTwoCountsDisagreeing() {
        assertEquals("1/2", valueOf(report(sinks = 1, room = listOf(host, near, far)), R.string.counter_sinks))
    }

    /** A host by itself is a room of one, not an empty one. Nobody has connected and it says so. */
    @Test
    fun aHostAloneIsARoomOfOne() {
        val text = report(sinks = 0, room = listOf(host))

        assertEquals("a1b2", valueOf(text, R.string.counter_room))
        assertEquals("0/0", valueOf(text, R.string.counter_sinks))
    }

    /**
     * A session with no name of its own binds no control channel, so it has no roster to compare
     * against and says only the one number it knows. An empty room row would read as a room that
     * emptied rather than as a build that never had one.
     */
    @Test
    fun aNamelessSessionCountsItsConnectionsAndClaimsNoRoom() {
        val text = report(sinks = 2, room = emptyList())

        assertFalse(SessionReadout.counters(text).map { it.label }.contains(R.string.counter_room))
        assertEquals("2", valueOf(text, R.string.counter_sinks))
    }

    /** The fields that were already there are still there, in a form the screen still reads. */
    @Test
    fun theFieldsThatWereAlreadyReportedSurvive() {
        val text = report(sinks = 1, room = listOf(host, near))

        assertTrue(text.contains("\"generated\":3010"))
        assertTrue(text.contains("\"unnamedSinks\":0"))
        assertEquals("1 ms", valueOf(text, R.string.counter_broadcast))
        assertEquals("0", valueOf(text, R.string.counter_to_sinks))
    }
}
