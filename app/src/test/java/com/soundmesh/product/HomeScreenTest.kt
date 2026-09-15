package com.soundmesh.product

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Two properties of the home screen and the words on it, read out of the sources the way
 * [PeerCalibrateActivityTest] reads its own.
 */
class HomeScreenTest {
    private val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)

    private fun string(name: String): String =
        Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(strings)?.groupValues?.get(1)
            ?: throw AssertionError("no such string: $name")

    /**
     * Whether to say out loud that this handset will be killed the moment it stops being looked at.
     *
     * Worth a rule of its own rather than an `if` on a screen, because the evening of 2026-09-14
     * went on it: a P30 dropped out of every room within seconds of going to the home screen, and
     * the screen said nothing at all - the icon on the host simply went hollow, which by then meant
     * three different things. Two wake locks and three rounds of instruments later, the answer was
     * a setting on the phone.
     */
    @Test
    fun `a handset whose background is not allowed is warned, once it has a job to do`() {
        assertTrue(warnsAboutBackground(HomeState(role = Role.SINK, backgroundAllowed = false)))
        assertTrue(warnsAboutBackground(HomeState(role = Role.HOST, backgroundAllowed = false)))
    }

    @Test
    fun `a handset that has been allowed is not nagged`() {
        assertFalse(warnsAboutBackground(HomeState(role = Role.SINK, backgroundAllowed = true)))
    }

    @Test
    fun `and neither is one that has not been given a part to play yet`() {
        // With no role picked, nothing on this phone outlives the screen, so there is nothing the
        // setting would protect - and a warning nobody can act on usefully is one people learn to
        // scroll past.
        assertFalse(warnsAboutBackground(HomeState(role = Role.NONE, backgroundAllowed = false)))
    }

    /**
     * The role is what this handset is being right now, chosen on this screen. It is not who
     * scanned whom: two handsets that have each scanned the other both hold a scanned pairing, and
     * reading the role off that file made both of them the sink so that no run could start at all.
     * Wording that explains the role by the pairing code teaches the reader that same wrong model,
     * and the reader here is also whoever next changes the code.
     */
    @Test
    fun theRoleWordingDoesNotExplainTheRoleByWhoScannedWhom() {
        for (name in listOf("pair_calibrate_role_host", "pair_calibrate_role_sink")) {
            val text = string(name)
            assertFalse("$name explains the role by the pairing code: $text", text.contains("扫过"))
            assertFalse("$name explains the role by the pairing code: $text", text.contains("出示过"))
        }
    }
    /**
     * The gesture a listener did not mean to make. A Material slider reports a touch on the track
     * exactly as it reports a drag, and the touch lands the value wherever the finger was - so a
     * sleeve across the screen used to buy a real jump and a second and a half of silence in every
     * handset in the room. What separates the two is whether the finger went anywhere.
     */
    @Test
    fun aTouchThatWentNowhereIsNotAJump() {
        assertNull(draggedTo(landedAt = 0.62f, leftAt = 0.62f))
        assertNull(draggedTo(landedAt = 0.62f, leftAt = 0.625f))
    }

    /** And the other half, or the control would be a decoration. */
    @Test
    fun aFingerThatTravelledIsAJumpToWhereItStopped() {
        assertEquals(0.9f, draggedTo(landedAt = 0.2f, leftAt = 0.9f))
        assertEquals(0.1f, draggedTo(landedAt = 0.8f, leftAt = 0.1f))
    }
    /**
     * The room slider appears, and goes on appearing.
     *
     * Reported on 2026-09-13: playing a local song, the whole volume panel was gone and there was
     * no way to set the room's volume at all. The condition for "stop following this phone" was
     * the flag saying a volume had been changed and not put back - which is read off a file that
     * outlives the app, so it was already true at the next start, before a role had been picked
     * and while there was nothing to show. It latched null and the panel never came back.
     */
    @Test
    fun goesOnOfferingTheRoomSliderAfterAVolumeHasBeenSetOnce() {
        // Before anybody drags it: wherever this phone is, whatever happened in an earlier run.
        assertEquals(40, roomVolumeShown(isHost = true, dragged = false, shown = null) { 40 })
        assertEquals(40, roomVolumeShown(isHost = true, dragged = false, shown = 75) { 40 })

        // After a drag: the room's number, and it stops following this phone.
        assertEquals(75, roomVolumeShown(isHost = true, dragged = true, shown = 75) { 40 })

        // A sink has no room to set one for.
        assertNull(roomVolumeShown(isHost = false, dragged = true, shown = 75) { 40 })
    }

    /**
     * Rounding is not disagreement, and a stream that will not move is.
     *
     * The distinction is the whole value of the read-back line. A percentage lands on a step and
     * reads back as a different percentage almost every time, so a check done in percent would
     * cry wolf on every single row and there would be no way left to say the one thing worth
     * saying - that a handset was told something and is somewhere else.
     */
    @Test
    fun callsAStreamThatWouldNotMoveADisagreementAndRoundingNotOne() {
        // 34% of fifteen steps is step five, which reads back as 33%. Same step, so no complaint.
        assertTrue(landedWhereAsked(asked = 34, index = 5, max = 15))
        // And on a handset with a different scale, where the same percentage is a different step.
        assertTrue(landedWhereAsked(asked = 34, index = 9, max = 25))

        // Told eighty per cent and sitting on step four of fifteen: that is a stream that did not
        // move, which is exactly what setStreamVolume has been seen doing on these handsets.
        assertFalse(landedWhereAsked(asked = 80, index = 4, max = 15))
    }

    /** Nothing to disagree with until somebody has told that handset something. */
    @Test
    fun saysNothingAboutAHandsetNobodyHasToldAnything() {
        assertTrue(landedWhereAsked(asked = null, index = 4, max = 15))
    }

    /**
     * The volume keys on a handset move the thumb on the host; a stream that refuses does not.
     *
     * Both arrive as "this handset is not where it was told to be", and drawing the thumb from
     * what was asked - which is what stops it snapping back - would freeze it against a person
     * standing at that phone pressing its keys. Which is exactly what it did: reported on
     * 2026-09-14, and it used to work before the thumb was moved off the report.
     */
    @Test
    fun letsAPersonAtTheHandsetWinAndStillCallsOutAStreamThatWillNotMove() {
        // Told thirty per cent, and it went to step nine on its own: somebody pressed the keys.
        assertTrue(somebodyElseMovedIt(asked = 30, before = 5, now = 9, max = 15))

        // Told eighty and sitting on the same step it was on before: the stream refused.
        assertFalse(somebodyElseMovedIt(asked = 80, before = 4, now = 4, max = 15))
        // Told thirty and landed on it: obeying is not somebody else.
        assertFalse(somebodyElseMovedIt(asked = 30, before = 8, now = 5, max = 15))
        // Nothing to compare against on the first reading, and nothing asked for is nobody to win against.
        assertFalse(somebodyElseMovedIt(asked = 30, before = null, now = 5, max = 15))
        assertFalse(somebodyElseMovedIt(asked = null, before = 4, now = 9, max = 15))
    }

    /**
     * "It has not said" and "it would not move" are two different sentences, and one red line
     * said neither.
     *
     * Reported on 2026-09-14: a handset showed "did not reach what you asked for" whatever it was
     * told, while its volume plainly changed in the room. Both halves were true - it was obeying,
     * and the number beside it was not what was asked - because that number was the one it
     * reported on connecting and it had not reported since. Naming that case is the difference
     * between a screen that misleads and one that points at the fault.
     */
    @Test
    fun tellsAHandsetThatHasNotAnsweredApartFromOneThatWouldNotMove() {
        val told = 10_000L
        val later = told + VOLUME_GRACE_MILLIS + 1

        // Nothing since the instruction went out: the reading beside it is old, whatever it says.
        assertEquals(
            VolumeComplaint.NOT_SAID,
            volumeComplaint(asked = 30, index = 8, max = 15, askedAt = told, saidAt = told - 1, now = later)
        )
        assertEquals(
            VolumeComplaint.NOT_SAID,
            volumeComplaint(asked = 30, index = 8, max = 15, askedAt = told, saidAt = null, now = later)
        )

        // It answered, and it is not where it was told to be: the stream refused.
        assertEquals(
            VolumeComplaint.REFUSED,
            volumeComplaint(asked = 30, index = 8, max = 15, askedAt = told, saidAt = told + 1, now = later)
        )

        // It answered and landed on the right step.
        assertEquals(
            VolumeComplaint.NONE,
            volumeComplaint(asked = 30, index = 5, max = 15, askedAt = told, saidAt = told + 1, now = later)
        )
    }

    /**
     * Nothing is said in the moment right after an instruction, because every handset is behind
     * then - and a warning that flashes on every drag of every slider is one nobody reads.
     */
    @Test
    fun saysNothingWhileTheAnswerIsStillCrossingTheRoom() {
        val told = 10_000L
        assertEquals(
            VolumeComplaint.NONE,
            volumeComplaint(
                asked = 30, index = 8, max = 15, askedAt = told, saidAt = null,
                now = told + VOLUME_GRACE_MILLIS - 1
            )
        )
    }

    /** And nothing at all about a handset nobody has told anything. */
    @Test
    fun saysNothingAboutAHandsetNobodyHasInstructed() {
        assertEquals(
            VolumeComplaint.NONE,
            volumeComplaint(asked = null, index = 8, max = 15, askedAt = 0L, saidAt = null, now = 10_000L)
        )
    }
}
