package com.soundmesh.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What happens to a room that is playing when the handset hosting it stops being the host.
 *
 * Both of these were reported from a real room on 2026-09-18 and both are the same shape: a
 * control or a role changed on this screen while the session underneath it went on as before. The
 * session outliving the screen is deliberate - it is what lets a phone lie face down and keep
 * playing - so every place that ends the role has to end the session by hand, and the two that did
 * not are here.
 *
 * Read as source, because none of it can be run on a JVM: an Activity, a foreground service and a
 * Compose screen. Each assertion names the line whose removal puts the reported behaviour back.
 */
class PuttingDownTheRoomTest {
    /** Read with the line endings flattened: this working tree holds both, file by file. */
    private fun source(path: String) =
        File(path).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private val home get() = source("src/main/java/com/soundmesh/product/HomeActivity.kt")
    private val screen get() = source("src/main/java/com/soundmesh/product/HomeScreen.kt")

    /**
     * Picking any other role while the room is playing stops the room.
     *
     * Reported as two faults and it is one: the music carried on, and the next handset to take the
     * host role could not command a phone that had never given the role up. Nothing in the old
     * code stopped the session on a role change at all - the stop existed only on the button and
     * on the source picker.
     */
    @Test
    fun `giving up the host role stops what this handset is playing`() {
        val pick = home.substringAfter("pickRole = { role ->").substringBefore("chooseSong =")
        assertTrue(
            "the session is left running when the role is given up",
            pick.contains("if (role != Role.HOST && state.running) leaveTheRoomPlaying()")
        )
        // Before the copy, not after. Everything downstream reads state.role, so a stop ordered
        // after the role had already changed would be ordered by a handset that is no longer the
        // host - which is the same silence the bug had.
        assertTrue(
            "the room is put down after the role has already changed under it",
            pick.indexOf("leaveTheRoomPlaying()") < pick.indexOf("state = state.copy(role = role")
        )
    }

    /**
     * The room is told while there is still a channel to tell it on.
     *
     * The tick that normally announces a session ending cannot do this one. It returns on a role
     * that is not HOST - the role has just changed - and [HomeActivity.takeUpTheRoom] shuts the
     * command server a moment later. Both halves are asserted, because the first is what makes
     * the explicit send necessary and without it somebody would reasonably delete it as duplicate.
     */
    @Test
    fun `the room is told to stop before the channel it is told on is shut`() {
        val leave = home.substringAfter("private fun leaveTheRoomPlaying()").substringBefore("\n    }")
        assertTrue(
            "nothing tells the standing phones to stop",
            leave.contains("RoomCommands.send(RoomCommand.STOP)")
        )
        assertTrue("the session is left running", leave.contains("stopSession()"))
        assertTrue(
            "the tick that announces a session would have said this anyway",
            home.substringAfter("private fun announceSession(running: Boolean)")
                .contains("if (state.role != Role.HOST) return")
        )
    }

    /**
     * A sink is not offered the three buttons that only the host has anything behind.
     *
     * The middle one did worse than nothing: `paused` is this handset's own, a sink's own is
     * always false, so a host pausing the room left every sink in it drawing a pause button over
     * silence - the one control on the screen that looked live was saying the opposite of what the
     * room was doing.
     */
    @Test
    fun `a sink is not offered a pause it cannot honour`() {
        val controls = screen
            .substringAfter("internal fun PlayControls(")
            .substringBefore("private fun TransportRow(")
        assertTrue(
            "the transport row is drawn whatever this handset is",
            controls.contains("if (state.role == Role.HOST) TransportRow(state, actions)")
        )
        assertFalse(
            "a pause button is still drawn outside the host's own row",
            controls.contains("TransportIcon.PAUSE")
        )
        // The reason it is removed rather than disabled, pinned where it lives: the call a sink
        // would make lands on nothing at all.
        assertTrue(
            "a sink now has somewhere for a pause to go",
            source("src/main/java/com/soundmesh/session/SinkSession.kt")
                .contains("override fun setPaused(wanted: Boolean) = Unit")
        )
    }

    /**
     * A handset that takes the host role stops pointing at a host.
     *
     * The other half of the same report. A look for a host is skipped while one is remembered,
     * so a phone that ever scanned a code would go on dialling that one after it had been the
     * host itself - and the room it is actually standing in would never reach it.
     */
    @Test
    fun `taking the host role stops this handset pointing at a host`() {
        val host = home
            .substringAfter("private fun takeUpTheRoom()")
            .substringAfter("Role.HOST ->")
            .substringBefore("Role.SINK ->")
        assertTrue(
            "a handset that becomes the host goes on pointing at the last one it scanned",
            host.contains("PairedHost(filesDir).forget()")
        )
        // What makes the line above load bearing: the standby search does not overwrite a stored
        // host of its own accord. It used to skip looking entirely while one was stored; since
        // 2026-09-19 it looks again once the line to that host has been down long enough to say
        // the host has moved, which is a re-point and not a fresh search - so a handset that
        // became the host still has to let go of its own pairing by hand.
        assertTrue(
            "the standby service would look for a host anyway, so this would not matter",
            source("src/main/java/com/soundmesh/product/StandbyService.kt")
                .contains("if (pointed && downFor < HostSearch.STALE_AFTER_MILLIS) return")
        )
    }

    /** Stop is left on both ends, because it is the one of the four that is real on a sink. */
    @Test
    fun `a sink still has a way to leave the room`() {
        val controls = screen
            .substringAfter("internal fun PlayControls(")
            .substringBefore("private fun TransportRow(")
        assertTrue(
            "the stop button went with the pause button",
            controls.contains("OutlinedButton(onClick = actions.stop")
        )
    }
}
