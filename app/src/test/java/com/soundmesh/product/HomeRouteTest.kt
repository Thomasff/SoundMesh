package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeRouteTest {
    @Test
    fun `no role picked is the welcome screen`() {
        assertEquals(HomeRoute.WELCOME, routeOf(HomeState(role = Role.NONE)))
    }

    @Test
    fun `a role but nothing running is the checklist`() {
        assertEquals(HomeRoute.READY, routeOf(HomeState(role = Role.HOST)))
        assertEquals(HomeRoute.READY, routeOf(HomeState(role = Role.SINK)))
    }

    @Test
    fun `a running session is the playing screen`() {
        assertEquals(HomeRoute.PLAYING, routeOf(HomeState(role = Role.HOST, running = true)))
        assertEquals(HomeRoute.PLAYING, routeOf(HomeState(role = Role.SINK, running = true)))
    }

    // A session cannot be running with no role - but if the two ever disagree, the role is the
    // one that decides, because the welcome screen is the only one that can fix a missing role.
    @Test
    fun `a running session with no role still asks for a role`() {
        assertEquals(HomeRoute.WELCOME, routeOf(HomeState(role = Role.NONE, running = true)))
    }

    /**
     * Back from the playing stage lands on the stage before it, and the music goes on playing.
     *
     * The session is what is running, not the screen - leaving the playing stage says nothing to
     * it - so [routeOf] cannot go on reading a running session as "must be looking at it". Which
     * is also why the answer is a parameter rather than a field on the state: it is where a
     * person is standing, not what this handset is doing.
     */
    @Test
    fun backOutOfAPlayingRoomLandsOnTheStageBeforeIt() {
        assertEquals(
            HomeRoute.READY,
            routeOf(HomeState(role = Role.HOST, running = true), steppedBack = true)
        )
        assertEquals(
            HomeRoute.READY,
            routeOf(HomeState(role = Role.SINK, running = true), steppedBack = true)
        )
    }

    /** And the role still wins that argument, for the reason [routeOf] gives. */
    @Test
    fun steppingBackStillCannotSkipTheRolePicker() {
        assertEquals(
            HomeRoute.WELCOME,
            routeOf(HomeState(role = Role.NONE, running = true), steppedBack = true)
        )
    }

    @Test
    fun `an unset optional fact is null rather than an empty string`() {
        assertNull(configured(""))
        assertNull(configured("   "))
        assertEquals("https://example.invalid", configured("https://example.invalid"))
    }
}
