package com.soundmesh.product

import com.soundmesh.core.PairingCode
import com.soundmesh.probe.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checklist is the whole of the redesign: it is the one place that says what is still wrong
 * with this room, and every line on it has to have somewhere to go.
 *
 * It stopped being a gate on 2026-09-18 - the way onto the playing stage is open whatever is on
 * this list - so what [Mark.BLOCK] means now is what it always said: a phone in this room will be
 * silent and nobody will be told why.
 */
class ReadyChecklistTest {
    private fun marks(state: HomeState) = readyList(state).associate { it.line to it.mark }

    /** Whether anything on the list says a phone in this room will be silent. See [Mark]. */
    private fun nothingBlocks(state: HomeState) = readyList(state).none { it.mark == Mark.BLOCK }

    // A row's line resource swaps with its state - "选好歌了" becomes "还没选歌" - so a test that
    // looks a row up by one id finds nothing the moment the row is in the state the test is about.
    private fun markOf(state: HomeState, vararg ids: Int) =
        readyList(state).first { it.line in ids }.mark

    private val readyHost = HomeState(
        role = Role.HOST,
        songName = "夜曲.flac",
        selfCalibrated = 12.4,
        standingBy = 2
    )

    /** The same host streaming what it is playing, which is the one run the output lead is in. */
    private val capturingHost = readyHost.copy(songName = null, capturing = true)

    @Test
    fun `a host with a song, a calibration and company can start`() {
        assertTrue(nothingBlocks(readyHost))
    }

    @Test
    fun `no song blocks, because there is nothing to play`() {
        val items = readyList(readyHost.copy(songName = null))
        assertEquals(
            Mark.BLOCK,
            markOf(readyHost.copy(songName = null), R.string.ready_song, R.string.ready_song_missing)
        )
        assertFalse(items.none { it.mark == Mark.BLOCK })
    }

    // The status board is not where a song is picked: that is the playing page. Seen 2026-09-25
    // on a new phone picking 当主机 - 还没选歌 above 进入播放, on every phone that had never
    // played anything, and on none that had, because those remember their last song.
    @Test
    fun `the status board says nothing about a song not picked yet`() {
        val items = readyList(readyHost.copy(songName = null))
        assertFalse(boardProblems(items).any { it.line == R.string.ready_song_missing })
    }

    // Capturing another app is a source too - a host that is capturing has something to play
    // even with no file picked, and blocking it would make the capture feature unreachable.
    @Test
    fun `capturing counts as having a song`() {
        assertTrue(nothingBlocks(readyHost.copy(songName = null, capturing = true)))
    }

    // Warn, do not block: an uncalibrated handset plays, it just plays early. Blocking here
    // would mean nobody can ever hear the thing before measuring it, and measuring it is the
    // part people skip.
    @Test
    fun `an unmeasured output lead warns but does not block`() {
        val state = capturingHost.copy(selfCalibrated = null)
        assertEquals(
            Mark.WARN,
            markOf(state, R.string.ready_self_lead, R.string.ready_self_lead_missing)
        )
        assertTrue(nothingBlocks(state))
    }

    @Test
    fun `a measured output lead is shown with its number`() {
        val item = readyList(capturingHost).first {
            it.line in setOf(R.string.ready_self_lead, R.string.ready_self_lead_missing)
        }
        assertEquals(Mark.OK, item.mark)
        assertEquals("12.4", item.detail)
    }

    /**
     * And it is not asked about at all where it would change nothing.
     *
     * The number is the gap between the accessibility output and media, and the only run that
     * plays on the accessibility output is a host streaming what it is playing - see
     * CAPTURING_HOST_USAGE and SessionService, which is the one place it is ever read. A file, a
     * folder and every sink in the room play on media, where the gap is zero by definition.
     *
     * It was on all four lists until 2026-09-15, which put an amber mark and an errand in front
     * of everybody - and the errand is a minute and a half of chirps in a quiet room.
     */
    @Test
    fun `the output lead is only asked about where it is used`() {
        val lines = setOf(R.string.ready_self_lead, R.string.ready_self_lead_missing)

        assertTrue(readyList(capturingHost).any { it.line in lines })
        assertFalse(readyList(readyHost).any { it.line in lines })
        assertFalse(readyList(HomeState(role = Role.SINK)).any { it.line in lines })
    }

    // This is the one that blocks, and the evening of 2026-09-14 is why: a handset that is not
    // exempt is killed within seconds of its screen being left, and the room then plays with a
    // silent phone in it that nobody can explain.
    @Test
    fun `a handset that will be killed blocks, and says which one`() {
        val state = readyHost.copy(blockedPeerNames = listOf("蓝色"))
        val item = readyList(state).first { it.line == R.string.ready_blocked }
        assertEquals(Mark.BLOCK, item.mark)
        assertEquals("蓝色", item.detail)
        assertEquals(ReadyGoto.ALLOW_BACKGROUND, item.goto)
        assertFalse(nothingBlocks(state))
    }

    @Test
    fun `nobody standing by blocks a host, because a room of one is not a room`() {
        assertFalse(nothingBlocks(readyHost.copy(standingBy = 0)))
    }

    // Warn: the pair correction is worth about a millisecond against tens of them without it,
    // so a room that has not been measured is wrong but listenable.
    @Test
    fun `handsets with no pair correction warn`() {
        val state = readyHost.copy(uncalibrated = 2)
        assertEquals(Mark.WARN, marks(state)[R.string.ready_uncalibrated])
        assertTrue(nothingBlocks(state))
    }

    @Test
    fun `a sink is asked about pairing rather than about songs`() {
        val lines = readyList(HomeState(role = Role.SINK)).map { it.line }
        assertTrue(lines.any { it == R.string.ready_paired || it == R.string.ready_paired_none })
        assertFalse(lines.any { it == R.string.ready_song || it == R.string.ready_song_missing })
    }

    @Test
    fun `an unpaired sink blocks`() {
        assertFalse(nothingBlocks(HomeState(role = Role.SINK)))
    }

    /** A host this handset has been pointed at, which says nothing about whether it answers. */
    private val storedHost = PairingCode(hostId = "9f31", address = "192.168.43.7", chunkPort = 45003)

    // Pointed at a host that has closed the app. The stored pairing is still there and still
    // correct - it is what this handset will dial the moment that host comes back - and it is
    // not an answer to "is anything going to play here", which is the only question this list
    // asks. Ticked off the stored pairing, a sink sat green beside a host that had been gone
    // for minutes on 09-21, while the standby line under it said 还没连上主机.
    @Test
    fun `a sink whose host has gone is not ticked`() {
        val state = HomeState(role = Role.SINK, paired = storedHost, onStandby = false)
        assertEquals(Mark.BLOCK, markOf(state, R.string.ready_paired, R.string.ready_paired_none))
        assertFalse(nothingBlocks(state))
    }

    // The other direction, and it has to be here: without it an implementation that always
    // blocks passes the test above.
    @Test
    fun `a sink that is standing by is ticked`() {
        val state = HomeState(role = Role.SINK, paired = storedHost, onStandby = true)
        assertEquals(Mark.OK, markOf(state, R.string.ready_paired, R.string.ready_paired_none))
        assertTrue(nothingBlocks(state))
    }

    // Every line is a thing to do something about. A line with nothing to do about it is a
    // notification, and notifications belong somewhere that is not a checklist.
    @Test
    fun `every line that is not OK has somewhere to go`() {
        val states = listOf(
            readyHost.copy(songName = null),
            readyHost.copy(selfCalibrated = null),
            readyHost.copy(uncalibrated = 2),
            readyHost.copy(blockedPeerNames = listOf("蓝色")),
            readyHost.copy(standingBy = 0),
            HomeState(role = Role.SINK),
            HomeState(role = Role.SINK, backgroundAllowed = false)
        )
        for (state in states) {
            for (item in readyList(state)) {
                if (item.mark != Mark.OK) {
                    assertTrue("${item.line} has no goto", item.goto != null)
                }
            }
        }
    }

    @Test
    fun `no role means no checklist at all`() {
        assertTrue(readyList(HomeState(role = Role.NONE)).isEmpty())
    }
}
