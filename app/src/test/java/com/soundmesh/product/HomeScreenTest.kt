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
     * The pair calibration cannot start without a role - it is the one thing the screen it opens
     * cannot work out for itself - so offering it before one is picked leads somewhere that can
     * only say "go back and pick one". Gated here rather than explained there.
     */
    @Test
    fun thePairCalibrationIsOfferedOnlyOnceARoleIsPicked() {
        assertFalse(offersPairCalibration(HomeState(role = Role.NONE)))
        assertTrue(offersPairCalibration(HomeState(role = Role.HOST)))
        assertTrue(offersPairCalibration(HomeState(role = Role.SINK)))
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
}
