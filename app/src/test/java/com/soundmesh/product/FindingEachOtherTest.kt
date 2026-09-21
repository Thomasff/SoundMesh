package com.soundmesh.product

import org.junit.Assert.assertEquals
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
        val look = search.substringAfter("fun lookOnce(").substringBefore("fun lookAgain(")
        val guards = look.split("if (paired.read() != null) return").size - 1
        val writes = look.split("paired.write(").size - 1
        assertTrue("nothing is written down after a host is found", writes >= 1)
        // Counted against the writes rather than fixed at a number, which is what this assertion
        // was until a second way of finding a host arrived (the gateway, 09-22) and moved the
        // number without touching the rule. The rule is one re-read before the window is spent
        // and one immediately before every single write - the window is seconds long, and a scan
        // finishing inside it is the one thing that must never be overwritten.
        assertEquals(
            "a search that started before a scan finished overwrites the scan",
            writes + 1,
            guards
        )
    }

    /**
     * A handset never joins its own stale record.
     *
     * Giving the role back is a request to a platform daemon, not an act: the record goes on being
     * answered for seconds after somebody picks 当从机, and other devices' caches hold it longer.
     * One record on the network is exactly the shape a search accepts, so without this the phone
     * stores itself as its own host and dials a port nothing is serving. It never recovers on its
     * own either, because a search does not argue with a stored host - the two rules meet here and
     * the result is a handset permanently pointed at itself.
     */
    @Test
    fun aHandsetNeverPairsWithItsOwnRecord() {
        val once = search.substringAfter("fun lookOnce(").substringBefore("fun lookAgain(")
        // Out of the whole list, not out of the single host already picked. Asking afterwards
        // answers only the case where its own record was the only answer; standing beside a real
        // host it made two, and two is a refusal, so a handset that had just handed the role over
        // joined nobody for as long as the platform kept answering for it. Seventy seconds of it,
        // measured on 09-21.
        assertTrue(
            "a phone that has just stopped being the host counts its own record as a host",
            once.contains("outcome.hosts.filter { PeerAdvertisement.hostIdOf(it) != mine }")
        )
        // The other direction of the same fault: one stale record of its own reads as "one other
        // host is here", which is the single shape that moves a sink onto a different handset.
        assertTrue(
            "its own record counts as another host when this handset re-points itself",
            search.substringAfter("fun lookAgain(")
                .contains("outcome.hosts.filter { PeerAdvertisement.hostIdOf(it) != mine }")
        )
    }

    /**
     * The screen says what is true now, and it is not looking at the field that knows.
     *
     * Both halves of the finding happen with nobody touching the phone: the search runs in a
     * service and writes the host down, and the line to that host comes up and goes down later
     * still. A screen that reads the stored host once, on the way in, is describing the moment it
     * opened for as long as somebody leaves it alone - which on a phone lying on a table is the
     * whole evening. On 09-21 that was both directions at once: a handset the host was already
     * listing in its room still saying 正在自动找主机, and a handset with no host left on the
     * network still claiming one. Re-picking the role was the only thing that moved either, and
     * only because picking a role re-reads the file.
     */
    @Test
    fun theScreenSaysWhetherThereIsAHostRatherThanWhetherThereWasOne() {
        assertTrue(
            "the stored host is read on the way into this screen and never again",
            home.substringAfter("private val refresh = object : Runnable")
                .substringBefore("handler.postDelayed(this, REFRESH_MILLIS)")
                .contains("paired = PairedHost(filesDir).read(),")
        )
        // Which is a different question from the one above, and the one people read: the stored
        // host survives that host closing the app - deliberately, it is what brings the room back
        // by itself - so a line drawn off it is as true the day after.
        val line = source("src/main/java/com/soundmesh/product/ReadyScreen.kt")
            .substringAfter("private fun ScanLine(")
        assertTrue(
            "the pairing line claims a host off the stored one rather than off the live line",
            line.contains("if (state.onStandby) R.string.ready_paired else R.string.ready_paired_none")
        )
        assertTrue(
            "the dot beside it still follows the stored host",
            line.contains("Dot(null, hollow = !state.onStandby)")
        )
    }

    /**
     * The host's record follows the host, whether or not anything is playing.
     *
     * The sink's half of this landed first and did nothing on its own. A sink that has been down
     * long enough looks again and asks where its host is now - and the answer came back off a
     * record naming the address the host had before it switched networks, which reads as "still
     * where it was" and changes nothing. The watch used to belong to the session, so it existed
     * only while music was playing, and standing by is where a room spends nearly all of its time.
     */
    @Test
    fun theRecordFollowsTheHandsetOntoItsNewNetwork() {
        val beacon = source("src/main/java/com/soundmesh/probe/sync/HostBeacon.kt")
        assertTrue(
            "nothing watches the network while this handset is only standing by as the host",
            beacon.substringAfter("fun hold(").contains("watch()")
        )
        assertTrue(
            "a changed address does not make the record be said again",
            beacon.substringAfter("private fun moved(").contains("again()")
        )
        // Registering delivers the current network. Read as a move, it would withdraw and re-say
        // the record the instant it was taken, which is the one moment a rename would land.
        assertTrue(
            "the first reading of the network is treated as a move",
            beacon.substringAfter("private fun moved(")
                .contains("if (before == null || !before.movedTo(now)) return")
        )
        // Two watchers calling this on one event re-register a name while its own withdrawal is
        // still in flight, and the platform answers that by renaming it.
        assertFalse(
            "the session says the record again as well, so a network change says it twice",
            source("src/main/java/com/soundmesh/session/SessionService.kt").contains("readvertise()")
        )
    }

    /**
     * A line that is open and carrying nothing is noticed, and dialled again.
     *
     * Holding a socket open says nothing at either end. One whose far end left the network is
     * closed by nobody: the read parks for ever, writes wait in the kernel rather than failing,
     * and the flag that says "connected" goes on saying it. Hung off that flag, the clock that
     * sends this handset looking for its host never started in the one case it exists for.
     */
    @Test
    fun aLineThatCarriesNothingIsNotMistakenForAWorkingOne() {
        val standby = source("src/main/java/com/soundmesh/product/StandbyService.kt")
        assertTrue(
            "the down clock is hung off the socket flag, which a dead line keeps true",
            standby.contains("private fun carrying(): Boolean = line?.connected == true && missed == 0")
        )
        assertTrue(
            "the clock is started from something other than whether the line is carrying",
            standby.contains("if (carrying()) downSince = 0L else if (downSince == 0L) downSince = now")
        )
        // A look can only fix a host that moved. The other half is the host that did not move and
        // the socket that died anyway - the network changed and changed back - where the address
        // in the file is right and nothing will ever close the line holding it.
        assertTrue(
            "a dead line to an unchanged address is never dropped, so it is never re-dialled",
            standby.contains("if (pointed) line?.dialAgain()")
        )
    }

    /**
     * A record that outlived its host does not hold the room up for a minute.
     *
     * The decision is [HostRepoint.ofUnreachable] and is tested in core, away from any network.
     * What is here is the one line that puts it in the path, because with `of` in its place
     * everything goes on compiling, every test in core goes on passing, and the only trace is a
     * room that takes a minute to come back after the host is handed over. Measured 09-21: five
     * consecutive "the host is still at .83" ten seconds apart, pointed at a handset that had
     * stopped being the host 52 seconds earlier.
     */
    @Test
    fun aSinkDoesNotWaitOutTheOldHostsRecord() {
        assertTrue(
            "a re-look believes a record answering from an address it cannot reach",
            search.substringAfter("fun lookAgain(").contains("HostRepoint.ofUnreachable(")
        )
        // The other half of the same wiring: this is only sound because the look happens only
        // after the line to that address has been down. A look on any other terms would strike
        // out records of hosts that are perfectly fine.
        assertTrue(
            "the re-look is not gated on the line to the stored host being down",
            source("src/main/java/com/soundmesh/product/StandbyService.kt")
                .contains("if (pointed && downFor < HostSearch.STALE_AFTER_MILLIS) return")
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
     * And it asks the handset, not the record, and only where the role is taken.
     *
     * Two faults, one screen, both reported 2026-09-19 as "两台都点了当主机，来回按几次之后，当主机
     * 的那台还是会退回选角色界面".
     *
     * The first is when it asks. Running on every resume turns "this handset may not become the
     * second host" into "whichever handset last came back to this screen loses" - so a settled
     * host that walks back into this screen steps down for the sink it is serving.
     *
     * The second is what it asks. A discovered record is not a host: giving the role back is a
     * request to a platform daemon, the record goes on being answered for seconds afterwards, and
     * other devices' caches hold it longer still. Pressing the two roles back and forth is exactly
     * how somebody fills the network with those, and stepping down for one leaves a room with no
     * host at all - with the ghost gone by the time anybody looks for the reason.
     */
    @Test
    fun aSettledHostIsNotUnseatedByAResumeOrByAGhost() {
        val role = home.substringAfter("private fun takeUpTheRoom()")
        assertTrue(
            "the check runs on every resume, so coming back to this screen can unseat the host",
            role.contains("val changed = roleTakenUp != state.role") &&
                role.contains("if (changed) refuseToBeTheSecondHost()")
        )
        val check = home.substringAfter("private fun refuseToBeTheSecondHost()")
        assertTrue(
            "a record answering is taken for a handset that is still a host",
            check.contains("if (!RoomCommands.stillServing(other))")
        )
        // The port is bound by serve() and closed by stop(), which is to say it is open exactly
        // while that handset is a host. Nothing else on this network answers on it.
        assertTrue(
            "the liveness question is asked of something other than the host's own port",
            source("src/main/java/com/soundmesh/probe/sync/RoomCommandChannel.kt")
                .contains("it.connect(InetSocketAddress(address, COMMAND_PORT), timeoutMillis)")
        )
    }

    /**
     * And the sentence lands where the person was sent, which is the role picker.
     *
     * It existed before 2026-09-19 and was drawn in the song block - part of the screen this
     * handset had just been thrown out of. So the app undid the press and said nothing at all.
     */
    @Test
    fun theRefusalIsSaidOnTheScreenItSendsSomebodyTo() {
        assertTrue(
            "the role picker says nothing about why it is up",
            source("src/main/java/com/soundmesh/product/WelcomeScreen.kt")
                .contains("state.problem?.let { Note(stringResource(it), Tone.WRONG) }")
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
