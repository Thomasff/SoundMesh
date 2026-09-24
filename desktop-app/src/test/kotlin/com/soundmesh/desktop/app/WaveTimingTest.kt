package com.soundmesh.desktop.app

import com.soundmesh.product.HANDSET_LAP_DP
import com.soundmesh.product.waveTiming
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WaveTimingTest {
    /**
     * A screen three times round a handset's gets three times the crests, so each crest is as long
     * as on the handset, and each passes a point as often, so it moves as fast. Reported 2026-09-24:
     * round the whole of a computer's screen the waves were stretched out by the length of the ring.
     */
    @Test
    fun aLongerRingKeepsTheHandsetsWavelength() {
        val handset = waveTiming(HANDSET_LAP_DP, keepHandsetWavelength = true)
        val screen = waveTiming(3f * HANDSET_LAP_DP, keepHandsetWavelength = true)
        for (i in handset.m.indices) {
            assertEquals(3f * handset.m[i], screen.m[i], 0f)
            assertEquals(handset.m[i] * handset.cyc[i], screen.m[i] * screen.cyc[i], 1e-9)
        }
    }

    /** Crest counts stay whole, or a lap would not close on itself. */
    @Test
    fun crestCountsStayWhole() {
        val odd = waveTiming(1.37f * HANDSET_LAP_DP, keepHandsetWavelength = true)
        for (m in odd.m) assertEquals(Math.round(m).toFloat(), m, 0f)
    }

    /** The handset itself keeps its table whatever its size, as it always has. */
    @Test
    fun theHandsetKeepsItsTable() {
        val small = waveTiming(0.5f * HANDSET_LAP_DP, keepHandsetWavelength = false)
        val big = waveTiming(2f * HANDSET_LAP_DP, keepHandsetWavelength = false)
        assertArrayEquals(small.m, big.m, 0f)
        assertArrayEquals(small.cyc, big.cyc, 0.0)
    }
}
