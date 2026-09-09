package com.soundmesh.session

import com.soundmesh.probe.R
import com.soundmesh.product.SessionReadout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.soundmesh.probe.sync.SyncRenderer
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

    private fun report(sinks: Int, room: List<String>, lateChunks: Int = 0, skippedSongs: Int = 0): String =
        "{" + hostReportFields(
            maxBroadcastNanos = 1_000_000L,
            generated = 3_010,
            droppedToSinks = 0,
            sinks = sinks,
            roomPeerIds = room,
            unnamedSinks = 0,
            lateChunks = lateChunks,
            skippedSongs = skippedSongs
        ) + "}"

    private fun valueOf(report: String, label: Int): String =
        SessionReadout.counters(report).first { it.label == label }.value

    /**
     * The end of a song is a second and a half after the source stops answering, not at it.
     *
     * This is the whole content of the function: a chunk is stamped 1.5 s into its own future and
     * every handset in the room is holding its copy of that instant. Ending at the moment the
     * source ran dry would take the last second and a half off the end of every song, everywhere
     * at once, and would leave every counter in the report saying the audio was fine.
     */
    @Test
    fun aSongThatRanOutStillHasItsLeadLeftToPlay() {
        val now = 5_000_000_000L
        val lastDue = now + 1_500_000_000L
        assertTrue(endOfAudioNanos(lastDue, now) > lastDue)
    }

    /** And the tail is the whole of the last chunk, not the instant it starts. */
    @Test
    fun theLastChunkIsHeardToItsEnd() {
        val lastDue = 5_000_000_000L
        assertEquals(lastDue + SyncRenderer.CHUNK_NANOS, endOfAudioNanos(lastDue, 0L))
    }

    /** A source that ended before saying anything has no tail to wait for. */
    @Test
    fun aSongThatPlayedNothingEndsNow() {
        assertEquals(700L, endOfAudioNanos(NOTHING_PLAYED, nowNanos = 700L))
    }

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

    /**
     * A folder is the one source that can lose part of itself and play on regardless.
     *
     * Nothing else would show it. The songs that did play are correct, the counters are correct,
     * and a folder missing its fourth track sounds exactly like a folder that never held one - so
     * the listener's only other evidence is knowing how many tracks the album has.
     */
    @Test
    fun aHostSaysHowManySongsItCouldNotPlay() {
        val text = report(sinks = 1, room = listOf(host, near), skippedSongs = 2)
        assertEquals("2", valueOf(text, R.string.counter_songs_skipped))
    }

    /** And says zero on a folder that played whole, for the same reason the other one does. */
    @Test
    fun aHostThatSkippedNothingStillSaysSo() {
        assertEquals("0", valueOf(report(sinks = 1, room = listOf(host, near)), R.string.counter_songs_skipped))
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
