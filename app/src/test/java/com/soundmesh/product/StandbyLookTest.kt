package com.soundmesh.product

import com.soundmesh.probe.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Before this, one hollow circle meant several things at once: it left, the app is not running,
 * the ROM killed it. Faults you check in different places, sharing one symbol, is the same as
 * having no symbol.
 *
 * A dark screen used to be one of them. It went on 2026-09-18: a handset with its screen off
 * plays, obeys and reports exactly as one with its screen on, so drawing it apart said there was
 * something to worry about where there was not.
 */
class StandbyLookTest {
    @Test
    fun `connected is simply following`() {
        assertEquals(StandbyLook.FOLLOWING, standbyLook(connected = true, saidNotExempt = false))
    }

    @Test
    fun `disconnected with nothing else known is gone`() {
        assertEquals(
            StandbyLook.GONE,
            standbyLook(connected = false, saidNotExempt = false)
        )
    }

    // Both halves have to be true. "It said it was not exempt" is the handset's own report, not
    // something this host inferred, and a disconnection on its own says nothing about why.
    @Test
    fun `disconnected AND having said it is not exempt is a kill`() {
        assertEquals(
            StandbyLook.KILLED,
            standbyLook(connected = false, saidNotExempt = true)
        )
    }

    @Test
    fun `still connected is never a kill, whatever it said about power saving`() {
        assertEquals(
            StandbyLook.FOLLOWING,
            standbyLook(connected = true, saidNotExempt = true)
        )
    }

    @Test
    fun `nothing to advise while it is behaving`() {
        assertNull(vendorAdvice(exempt = true, killedAnyway = false))
        assertNull(vendorAdvice(exempt = false, killedAnyway = false))
    }

    // Not yet exempt and being killed: the standard setting is the thing to try, and there is a
    // button for it. Do not send somebody into a vendor menu they may not need.
    @Test
    fun `not exempt and killed points at the standard setting`() {
        assertEquals(R.string.vendor_try_exemption, vendorAdvice(exempt = false, killedAnyway = true))
    }

    // Exempt and killed ANYWAY is the half nothing can query: the vendor switches have no API at
    // all. So it is told apart by behaviour, which is the only thing left.
    @Test
    fun `exempt and killed anyway names the vendor list, because no API can read it`() {
        assertEquals(R.string.vendor_launch_manager, vendorAdvice(exempt = true, killedAnyway = true))
    }
}
