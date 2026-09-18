package com.soundmesh.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two phones on one network finding each other with nobody holding one up to the other.
 *
 * Until 2026-09-18 the record that says "there is a host here" was the session's, so it went out
 * when somebody pressed play and not before - and a room spends nearly all of its time not
 * playing. A sink opened into that room saw nothing and waited for a person with a camera.
 *
 * Read as source, because none of it can be run here: every piece is NsdManager, a foreground
 * service or an Activity. What each assertion names is the line whose removal puts the old
 * behaviour back quietly - a phone that never says it is there, a phone that stops looking, or a
 * remembered host overwritten by whoever answered first.
 */
class FindingEachOtherTest {
    /** Read with the line endings flattened: this working tree holds both, file by file. */
    private fun source(path: String) =
        File(path).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private val home get() = source("src/main/java/com/soundmesh/product/HomeActivity.kt")
    private val search get() = source("src/main/java/com/soundmesh/probe/sync/HostSearch.kt")

    /**
     * The record goes out when the role is picked, not when the music starts.
     *
     * The half that is easy to leave out is the other one: a handset that stops being the host has
     * to stop saying it is, or the next sink to open spends a connect timeout on a phone that is
     * now a sink itself.
     */
    @Test
    fun aHostSaysItIsOneBeforeAnybodyPressesPlay() {
        val role = home.substringAfter("private fun takeUpTheRoom()")
        assertTrue(
            "nothing says this handset is a host until a session starts",
            role.contains("HostBeacon.hold(this, HostIdentity(filesDir).current(), HostBeacon.Holder.ROLE)")
        )
        assertTrue(
            "a handset that stops being the host goes on advertising as one",
            role.substringAfter("Role.SINK ->").contains("HostBeacon.release(HostBeacon.Holder.ROLE)")
        )
        assertTrue(
            "a handset with no role at all goes on advertising as a host",
            role.substringAfter("Role.NONE ->").contains("HostBeacon.release(HostBeacon.Holder.ROLE)")
        )
        // The obvious way to let two things hold one record is to count them, and it is wrong
        // here for a reason nothing else in this file would catch: the screen takes its hold on
        // every single resume. A count would climb all evening, and the record would outlive the
        // role being given up - a handset advertising itself as a host while sitting there as a
        // sink, which every other phone in the room believes.
        assertTrue(
            "the record is counted rather than held by name",
            source("src/main/java/com/soundmesh/probe/sync/HostBeacon.kt")
                .contains("private val holders = HashSet<Holder>()")
        )
    }

    /**
     * A handset with nothing to dial keeps looking instead of stopping.
     *
     * This is the whole of what makes the finding work in the order it actually happens in: the
     * phones are set down first and the host is switched on afterwards. A service that stopped
     * because there was nothing to dial yet would never see it.
     */
    @Test
    fun aSinkWithNoHostKeepsLookingForOne() {
        val standby = source("src/main/java/com/soundmesh/product/StandbyService.kt")
        assertFalse(
            "standby still stops itself the moment it has nothing to dial",
            standby.contains("if (announcement() == null) return stopSelf()")
        )
        assertTrue(
            "nothing ever looks for a host",
            standby.substringAfter("private val tellIfMoved").contains("lookForAHost()")
        )
        assertTrue(
            "the sink is started only once it already knows a host, which is never at first",
            home.substringAfter("private fun takeUpTheRoom()")
                .substringAfter("Role.SINK ->")
                .substringBefore("Role.NONE ->")
                .contains("startForegroundService(Intent(this, StandbyService::class.java))")
        )
    }

    /**
     * What was found never overwrites what somebody said.
     *
     * A scanned code is a person having pointed at one particular handset, and no amount of
     * answering on a network beats that. Asked twice on purpose: a look holds still for a whole
     * discovery window, and a scan finishing inside that window is exactly the thing that must
     * survive it.
     */
    @Test
    fun aFoundHostNeverArguesWithARememberedOne() {
        val look = search.substringAfter("fun lookOnce(")
        assertTrue(
            "a search that started before a scan finished overwrites the scan",
            look.split("if (paired.read() != null) return").size - 1 == 2
        )
        assertTrue(
            "nothing is written down after a host is found",
            look.contains("paired.write(code)")
        )
    }

    /**
     * A handset told to be the host checks first, and gives way rather than making a second room.
     *
     * Not once it is playing. By then it has a room, and the handset that should give way is the
     * one that took the role a minute ago.
     */
    @Test
    fun theSecondHostStepsDownInsteadOfSplittingTheRoom() {
        assertTrue(
            "picking the host role never asks whether one is already there",
            home.substringAfter("private fun takeUpTheRoom()").contains("refuseToBeTheSecondHost()")
        )
        val check = home.substringAfter("private fun refuseToBeTheSecondHost()")
        assertTrue(
            "a host in the middle of playing is sent back to the role screen",
            check.contains("if (state.role != Role.HOST || state.running) return@runOnUiThread")
        )
        assertTrue(
            "a host that already has a room of standing phones gives it up to a newcomer",
            check.contains("if (RoomCommands.standingBy() > 0) return@runOnUiThread")
        )
        assertTrue(
            "the person is not told why the screen went back",
            check.contains("R.string.role_host_taken")
        )
    }

    /**
     * The scanned code stays, and it is not a leftover.
     *
     * Discovery is multicast, and a network that does not carry it between its clients answers
     * exactly as an empty one does - a guest network, a campus one, a router filtering multicast
     * to save airtime. On those the code on the host's screen is the entire answer, so the screen
     * still offers it and the words beside it say so.
     */
    @Test
    fun theCodeIsStillThereForTheNetworksThisCannotWorkOn() {
        assertTrue(
            "the scan is gone from the sink's screen",
            source("src/main/java/com/soundmesh/product/ReadyScreen.kt")
                .contains("stringResource(R.string.pair_scan), actions.scan")
        )
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(
            "nothing tells somebody on such a network what to do instead",
            strings.contains("有的网络（校园网、公司网、公共热点）不让设备互相通信")
        )
    }
}
