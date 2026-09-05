package com.soundmesh.product

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
