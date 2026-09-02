package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DriftControllerTest {
    @Test
    fun doesNothingWhileTheErrorStaysInsideTheDeadband() {
        val controller = DriftController()

        repeat(5) { assertEquals(0, controller.observe(30).adjustFrames) }
    }

    @Test
    fun insertsAFrameWhenPlaybackIsPersistentlyAhead() {
        val controller = DriftController()
        repeat(4) { controller.observe(200) }

        assertEquals(1, controller.observe(200).adjustFrames)
    }

    @Test
    fun dropsAFrameWhenPlaybackIsPersistentlyBehind() {
        val controller = DriftController()
        repeat(4) { controller.observe(-200) }

        assertEquals(-1, controller.observe(-200).adjustFrames)
    }

    @Test
    fun ignoresAnIsolatedStaleReadingInsteadOfChasingIt() {
        val controller = DriftController()
        // Four clean readings inside the deadband, then one absurd outlier.
        repeat(4) { controller.observe(10) }

        val decision = controller.observe(5000)

        assertEquals(0, decision.adjustFrames)
        assertEquals(10, decision.filteredErrorFrames)
    }

    @Test
    fun neverCorrectsByMoreThanTheConfiguredLimit() {
        val controller = DriftController(maxAdjustFrames = 1)
        repeat(4) { controller.observe(100_000) }

        assertEquals(1, controller.observe(100_000).adjustFrames)
    }

    @Test
    fun worksBeforeTheMedianWindowHasFilledUp() {
        val controller = DriftController()

        assertEquals(200, controller.observe(200).filteredErrorFrames)
        assertEquals(1, controller.observe(200).adjustFrames)
    }
}
