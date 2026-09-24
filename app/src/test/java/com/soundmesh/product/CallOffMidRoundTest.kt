package com.soundmesh.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stopping a round that has already started making a sound.
 *
 * Until 2026-09-18 the stop button only worked while the handsets were still waiting to be told
 * what to do. Once the schedule was out, every phone in the room was acting on its own clock and
 * nothing the host said reached any of them, so the button went grey and the room chirped for
 * another forty seconds at somebody who had already asked it not to.
 *
 * Read as source, because none of it can be run here: every piece of a round is an AudioRecord, an
 * AudioTrack or a socket, and the fault this guards is a wait that does not end - which on the JVM
 * is a test that hangs rather than one that fails. What each assertion names is the single line
 * whose removal puts the old behaviour back, silently and in a place nothing else looks at.
 */
class CallOffMidRoundTest {
    /** Read with the line endings flattened: this working tree holds both, file by file. */
    private fun source(path: String) =
        File(path).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private val runner get() = source("../core/src/main/java/com/soundmesh/probe/sync/PeerCalibrationRunner.kt")

    private val lead get() = source("src/main/java/com/soundmesh/probe/sync/OutputLeadRunner.kt")

    /**
     * The recording stops, and so does the sound.
     *
     * Two different things and both have to end. A run that stopped recording but went on playing
     * is a room still chirping; one that stopped playing but went on recording is a handset that
     * looks finished and is not.
     */
    @Test
    fun aCalledOffRunStopsBothHalves() {
        assertTrue(
            "the recording loop runs to its deadline whatever happens",
            source("src/main/java/com/soundmesh/probe/sync/CalibrationRunner.kt")
                .contains("while (System.nanoTime() < deadline && !stopped())")
        )
        assertTrue(
            "the recording is never told what would stop it",
            runner.contains("calibration.record(secondsUntil(timing.recordUntilHostNanos), calledOff)")
        )
        assertTrue(
            "the renderer plays on to the instant the schedule named",
            runner.contains("if (calledOff()) speaker.stopNow()") &&
                source("src/main/java/com/soundmesh/probe/sync/HandsetRoundSpeaker.kt")
                    .contains("override fun stopNow() = renderer.stopNow()")
        )
        assertTrue(
            "the renderer has no way to be ended early",
            source("src/main/java/com/soundmesh/probe/sync/SyncRenderer.kt").contains("fun stopNow()")
        )
    }

    /**
     * Every wait a round stands still in asks whether it is still wanted.
     *
     * The longest of them is the warm-up, and it is one sleep. A run told to stop inside it used to
     * wake at the far end and go on to play its chirps, which is a stop button that works and then
     * makes a noise anyway.
     */
    @Test
    fun noWaitInARoundOutlastsThePress() {
        assertTrue(
            "the wait for a host instant sleeps through anything that happens during it",
            runner.contains("val remaining = minOf(hostNanos - hostNanosNow(), POLL_NANOS)")
        )
        assertTrue(
            "the warm-up plays itself out",
            runner.contains("for (sequence in 0 until chunks) {\n            if (calledOff()) return")
        )
        assertTrue(
            "the chirps are submitted whatever has happened since the last one",
            runner.contains("if (recordingFailure != null || calledOff()) return")
        )

        // The sixteen silent seconds of clock filling, which is where somebody who pressed start
        // by mistake presses stop. Both loops, because the first one ends as soon as there is any
        // estimate at all and the second is the one that actually takes the time.
        val sink = source("../core/src/main/java/com/soundmesh/product/SinkRound.kt")
        assertTrue(
            "the clock convergence wait cannot be interrupted",
            sink.contains("System.nanoTime() < deadline &&\n                !calledOff()")
        )
        assertTrue(
            "the clock fill wait cannot be interrupted",
            sink.contains("< timing.clockFillNanos && !calledOff())")
        )
    }

    /**
     * Nothing of a called-off round is kept, and the constant above all.
     *
     * A recording that stops in the middle of a schedule still correlates - the search finds
     * something at every slot it looks in - so the danger is not a crash, it is a number. Folded
     * into the stored calibration it would be carried into every session afterwards with nothing
     * anywhere to notice it by.
     */
    @Test
    fun aCalledOffRoundLeavesNothingBehind() {
        assertTrue(
            "a stopped run is analysed as though it had finished",
            runner.contains("if (calledOff()) return refused(CALLED_OFF)")
        )

        val sink = source("../core/src/main/java/com/soundmesh/product/SinkRound.kt")
        val afterRun = sink.substringAfter("val run = runner.run()")
        assertTrue(
            "the sink files and delivers a round that was called off",
            afterRun.indexOf("if (calledOff()) return calledOffHere()") in
                0 until afterRun.indexOf("AlignmentResultClient(")
        )

        val host = source("../core/src/main/java/com/soundmesh/product/HostRound.kt")
        assertTrue(
            "the pair host keeps a round it was told to stop",
            host.contains("pair-cancelled while chirping; nothing of this round is kept")
        )
        assertTrue(
            "the room host keeps a round it was told to stop",
            host.contains("room-cancelled while chirping; nothing of this round is kept")
        )
    }

    /**
     * The run one handset does to itself stops the same way a room's does.
     *
     * It is the longest thing this app ever asks of a quiet room - a minute and a half - and until
     * 2026-09-19 it was the one measurement with no way out but the back button, which left the
     * chirps playing to the end in a room whose screen had already moved on.
     */
    @Test
    fun theRunAHandsetDoesToItselfStopsTheSameWay() {
        assertTrue(
            "the recording is never told what would stop it",
            lead.contains("calibration.record(recordSeconds, calledOff)")
        )
        assertTrue(
            "the renderer plays each pass out to the instant it was given",
            lead.contains("if (calledOff()) renderer.stopNow()")
        )
        assertTrue(
            "the warm-up plays itself out",
            lead.contains("for (sequence in 0 until chunks) {\n            if (calledOff()) return")
        )
        assertTrue(
            "the passes after the press are played anyway",
            lead.contains("if (recordingFailure != null || calledOff()) break")
        )
        // The same danger as a called-off room: a recording that stops mid-schedule still
        // correlates, so what a stopped run produces is a number rather than a crash.
        assertTrue(
            "a stopped run is correlated and its answer offered as a measurement",
            lead.contains("return failed(CALLED_OFF)")
        )

        val screen = source("src/main/java/com/soundmesh/product/CalibrateActivity.kt")
        assertTrue(
            "the run is never told about the button",
            screen.contains("calledOff = { stopping }")
        )
        assertTrue(
            "leaving the screen leaves the run playing",
            screen.contains("stopping = true\n            putVolumeBack()")
        )
    }

    /**
     * The press reaches the other handsets, over the one channel a round never made them leave.
     *
     * And it reaches them past the guard that turns away everything else said to a measuring
     * handset. That guard is right about every other command and wrong about this one, which is
     * the only command that is about the round in flight rather than about starting something.
     */
    @Test
    fun theOtherHandsetsAreToldRatherThanLeftToFinish() {
        val host = source("src/main/java/com/soundmesh/product/PeerCalibrateActivity.kt")
        val press = host.substringAfter("private fun stopServing()")
        assertTrue(
            "the press never reaches the round in flight",
            press.contains("hostRoundInFlight?.callOff()")
        )
        val round = source("../core/src/main/java/com/soundmesh/product/HostRound.kt")
            .substringAfter("fun callOff()")
        assertTrue(
            "the press reaches only the handsets still waiting for a plan",
            round.contains("commands.send(RoomCommand.CALL_OFF)")
        )

        val standby = source("src/main/java/com/soundmesh/product/StandbyService.kt")
        val obey = standby.substringAfter("private fun obey(order: RoomOrder)")
        assertTrue(
            "the busy guard swallows the one command that is about being busy",
            obey.indexOf("RoomCommand.CALL_OFF") in
                0 until obey.indexOf("if (MeasuringNow.busy) return excuse(")
        )
    }

    /**
     * The button stays live once the chirps start, and says what pressing it will cost.
     *
     * Greyed was the honest answer while the press could not reach anybody. Now that it can, a
     * grey button is a room nobody can quieten, which is the thing that was reported.
     */
    @Test
    fun theButtonIsNeverDeadWhileARoundIsRunning() {
        val screen = source("src/main/java/com/soundmesh/product/PeerCalibrateScreen.kt")
        val control = screen.substringAfter("private fun StopControl(").substringBefore("\n}\n")
        assertFalse(
            "the stop button goes dead once the round is making a sound",
            control.contains("enabled =")
        )
        assertTrue(
            "nothing beside the button says what pressing it now costs",
            control.contains("R.string.pair_calibrate_under_way")
        )
    }
}
