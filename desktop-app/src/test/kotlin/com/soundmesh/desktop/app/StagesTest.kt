package com.soundmesh.desktop.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which page the window is on, by the handset's routeOf rules. */
class StagesTest {
    @Test
    fun noRoleIsTheFirstPageEvenWhileSomethingPlays() {
        assertEquals(Stage.WELCOME, stageOf(role = null, running = true, steppedBack = false, holding = true))
    }

    @Test
    fun aPickedRoleWithNothingPlayingIsTheBoard() {
        assertEquals(Stage.READY, stageOf(Role.SINK, running = false, steppedBack = false, holding = false))
    }

    @Test
    fun aPlayingRoomIsThePlayingPage() {
        assertEquals(Stage.PLAYING, stageOf(Role.SINK, running = true, steppedBack = false, holding = false))
    }

    @Test
    fun steppingBackOutOfAPlayingRoomLeavesItPlayingOnTheBoard() {
        assertEquals(Stage.READY, stageOf(Role.HOST, running = true, steppedBack = true, holding = false))
    }

    @Test
    fun enteringThePlayingPageWithNothingPlayingStaysThere() {
        assertEquals(Stage.PLAYING, stageOf(Role.HOST, running = false, steppedBack = false, holding = true))
    }
}
