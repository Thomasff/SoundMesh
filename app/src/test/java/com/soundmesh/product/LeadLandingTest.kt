package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a finished output-lead measurement is allowed to do on its own.
 *
 * The screen used to store every run the moment it finished and say so in a sentence. That is the
 * one arrangement in which a run that went wrong - a quiet room somebody walked through, a phone
 * picked up - silently replaces an answer that was right, with nothing on screen to compare it
 * against afterwards.
 */
class LeadLandingTest {
    @Test
    fun `the first measurement stores itself, because there is nothing to weigh it against`() {
        assertEquals(LeadLanding(store = 124_229L, offer = null), landingFor(124_229L, null, keeps = true))
    }

    /** A zero is what a handset nobody has calibrated carries, and it is not an answer. */
    @Test
    fun `a stored zero counts as nothing stored`() {
        assertEquals(LeadLanding(store = 124_229L, offer = null), landingFor(124_229L, 0L, keeps = true))
    }

    @Test
    fun `a second measurement is put to the person rather than stored`() {
        val landing = landingFor(121_600L, 124_229L, keeps = true)
        assertNull("the stored answer must survive a measurement nobody has adopted", landing.store)
        assertEquals(121_600L, landing.offer)
    }

    /**
     * A verification measures what is left over after the stored constant is applied, and a
     * repeatability run measures the reference path against itself. Either one written where the
     * constant lives wrecks it - the first halves the correction, the second wipes it.
     */
    @Test
    fun `a run that is not about the constant neither stores nor offers`() {
        assertEquals(LeadLanding(null, null), landingFor(300L, 124_229L, keeps = false))
        assertEquals(LeadLanding(null, null), landingFor(300L, null, keeps = false))
    }

    @Test
    fun `a refused run leaves both alone`() {
        assertEquals(LeadLanding(null, null), landingFor(null, 124_229L, keeps = true))
    }

    /** Stored or put to the person, either is a run that worked; stopped or refused is not. */
    @Test
    fun `a run worked when it stored or offered something and was not stopped`() {
        assertTrue(worked(LeadLanding(store = 124_229L, offer = null), stopped = false))
        assertTrue(worked(LeadLanding(store = null, offer = 121_600L), stopped = false))
        assertFalse(worked(LeadLanding(null, null), stopped = false))
        assertFalse(worked(LeadLanding(store = 124_229L, offer = null), stopped = true))
    }

    /** 完成 waits for 替代 or 取消: until one is pressed there is still a decision on the screen. */
    @Test
    fun `done is offered once a run worked and nothing is left to decide`() {
        assertTrue(showsDone(CalibrateState(measured = true)))
        assertFalse(showsDone(CalibrateState(measured = true, offered = 121_600L)))
        assertFalse(showsDone(CalibrateState(measured = true, running = true)))
        assertFalse(showsDone(CalibrateState(measured = false)))
    }
}
