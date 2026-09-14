package com.soundmesh.product

import com.soundmesh.probe.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checklist is the whole of the redesign: it is the one place that answers "why can I not
 * press play", and every line on it has to have somewhere to go.
 */
class ReadyChecklistTest {
    private fun marks(state: HomeState) = readyList(state).associate { it.line to it.mark }

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

    @Test
    fun `a host with a song, a calibration and company can start`() {
        assertTrue(canStart(readyList(readyHost)))
    }

    @Test
    fun `no song blocks, because there is nothing to play`() {
        val items = readyList(readyHost.copy(songName = null))
        assertEquals(
            Mark.BLOCK,
            markOf(readyHost.copy(songName = null), R.string.ready_song, R.string.ready_song_missing)
        )
        assertFalse(canStart(items))
    }

    // Capturing another app is a source too - a host that is capturing has something to play
    // even with no file picked, and blocking it would make the capture feature unreachable.
    @Test
    fun `capturing counts as having a song`() {
        assertTrue(canStart(readyList(readyHost.copy(songName = null, capturing = true))))
    }

    // Warn, do not block: an uncalibrated handset plays, it just plays early. Blocking here
    // would mean nobody can ever hear the thing before measuring it, and measuring it is the
    // part people skip.
    @Test
    fun `an unmeasured output lead warns but does not block`() {
        val state = readyHost.copy(selfCalibrated = null)
        assertEquals(
            Mark.WARN,
            markOf(state, R.string.ready_self_lead, R.string.ready_self_lead_missing)
        )
        assertTrue(canStart(readyList(state)))
    }

    @Test
    fun `a measured output lead is shown with its number`() {
        val item = readyList(readyHost).first {
            it.line in setOf(R.string.ready_self_lead, R.string.ready_self_lead_missing)
        }
        assertEquals(Mark.OK, item.mark)
        assertEquals("12.4", item.detail)
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
        assertFalse(canStart(readyList(state)))
    }

    @Test
    fun `nobody standing by blocks a host, because a room of one is not a room`() {
        assertFalse(canStart(readyList(readyHost.copy(standingBy = 0))))
    }

    // Warn: the pair correction is worth about a millisecond against tens of them without it,
    // so a room that has not been measured is wrong but listenable.
    @Test
    fun `handsets with no pair correction warn`() {
        val state = readyHost.copy(uncalibrated = 2)
        assertEquals(Mark.WARN, marks(state)[R.string.ready_uncalibrated])
        assertTrue(canStart(readyList(state)))
    }

    @Test
    fun `a sink is asked about pairing rather than about songs`() {
        val lines = readyList(HomeState(role = Role.SINK)).map { it.line }
        assertTrue(lines.any { it == R.string.ready_paired || it == R.string.ready_paired_none })
        assertFalse(lines.any { it == R.string.ready_song || it == R.string.ready_song_missing })
    }

    @Test
    fun `an unpaired sink blocks`() {
        assertFalse(canStart(readyList(HomeState(role = Role.SINK))))
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
