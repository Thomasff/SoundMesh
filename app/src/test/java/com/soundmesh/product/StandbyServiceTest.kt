package com.soundmesh.product

import com.soundmesh.core.RoomCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one line the standing service is drawn around: what a handset can do while nobody is
 * looking at it.
 */
class StandbyServiceTest {
    /**
     * All of them, and the two that changed are the point of the list.
     *
     * Measuring was on the other side until 2026-09-15, and not because anything about measuring
     * needs a screen: the sink half of a round grew inside an activity, and an app in the
     * background may not start one. Moving it into [SinkRound] changed the answer rather than the
     * rule, and this is what a room of phones lying face down is for.
     *
     * Asserted command by command rather than by shape, because the cost of the two mistakes is
     * not the same: a command wrongly on the "needs a screen" side is a feature that quietly does
     * not work when the phone is in a pocket, and one wrongly on the other side is a handset
     * trying to start an activity it is not allowed to start, in silence.
     */
    @Test
    fun obeysEveryCommandWhileTheScreenIsAway() {
        assertTrue(canObeyWhileAway(RoomCommand.PLAY))
        assertTrue(canObeyWhileAway(RoomCommand.STOP))
        assertTrue(canObeyWhileAway(RoomCommand.SET_VOLUME))
        assertTrue(canObeyWhileAway(RoomCommand.RESTORE_VOLUME))

        assertTrue(canObeyWhileAway(RoomCommand.MEASURE_ROOM))
        assertTrue(canObeyWhileAway(RoomCommand.MEASURE_OVERHEAD))
    }

    /**
     * The answer above is only true for as long as obeying does not reach for an activity.
     *
     * Read as source because the fault it guards has no other symptom: a `startActivity` from here
     * is refused by the system in silence, on a handset nobody is looking at, and what reaches the
     * host is a phone that simply never joined. That is the evening of 2026-09-13, and the answer
     * above would have turned it from a said-out-loud refusal into a silent one.
     */
    @Test
    fun aRoundHereNeverReachesForAScreen() {
        val source = File("src/main/java/com/soundmesh/product/StandbyService.kt").readText(Charsets.UTF_8)
        assertFalse(
            "the standing service starts an activity, which it may not do while nobody is looking",
            source.contains("startActivity(")
        )
        assertTrue("a round joined from here is SinkRound's", source.contains("SinkRound("))
    }

    /**
     * A round records this handset, so what it is playing has to stop before the chirps.
     *
     * The cost of skipping it is not a failed round. A correlation against a recording with music
     * over the top still produces a number, and that number is then applied to every session
     * afterwards with nothing anywhere to notice it by.
     */
    @Test
    fun aRoundStopsWhateverThisHandsetIsPlayingFirst() {
        val source = File("src/main/java/com/soundmesh/product/StandbyService.kt").readText(Charsets.UTF_8)
        val round = source.substringAfter("private fun goAndMeasure(")
        val hush = round.indexOf("hushWhateverIsPlaying()")
        val run = round.indexOf("SinkRound(")
        assertTrue("a round begins without hushing what is playing", hush in 1 until run)
        assertTrue(
            "the hush asks the session to stop rather than assuming it has",
            source.contains("SessionService.ACTION_STOP")
        )
    }

    /** And this is what makes the list above a list of all of them rather than of the ones I recalled. */
    @Test
    fun everyCommandOnTheChannelHasBeenAskedThisQuestion() {
        assertEquals(6, RoomCommand.entries.size)
    }
}
