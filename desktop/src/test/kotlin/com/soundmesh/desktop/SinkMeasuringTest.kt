package com.soundmesh.desktop

import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationReply
import com.soundmesh.core.PairingCode
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomExcuse
import com.soundmesh.core.RoomReply
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.WavFileWriter
import com.soundmesh.probe.sync.AlignmentResultServer
import com.soundmesh.probe.sync.CalibrationPlanServer
import com.soundmesh.probe.sync.Carried
import com.soundmesh.probe.sync.ClockSyncServer
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.probe.sync.RoomCommandServer
import com.soundmesh.probe.sync.RoomResultServer
import com.soundmesh.probe.sync.RoundRecorder
import com.soundmesh.probe.sync.StoredApproximateCalibration
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.product.ArmSchedule
import com.soundmesh.product.RoundLine
import com.soundmesh.product.SinkRoundRequest
import java.io.File
import java.util.Random
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * This machine in a handset host's measuring round, the host played by core's own servers - the
 * very classes a handset host runs - on ports of the test's choosing.
 *
 * The audio is stood in for: speakers that accept anything, a microphone that hears quiet noise.
 * What is under test is everything around it: that the round is joined, refused, called off and
 * answered as a handset's is, and that what it leaves is filed under the right host and played by.
 */
class SinkMeasuringTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val second = 1_000_000_000L

    /** A handset host's servers, as its measuring screen opens them. */
    private class PhoneHost(val hostId: String = "0123456789abcdef") : AutoCloseable {
        val clock = freeUdpPort()
        val command = freeTcpPort()
        val plan = freeTcpPort()
        val room = freeTcpPort()
        val result = freeTcpPort()
        val chunk = freeTcpPort()
        val spatial = freeTcpPort()
        val clockServer = ClockSyncServer(clock).apply { start() }
        val commands = RoomCommandServer(command, hostId).apply { start() }
        val plans = CalibrationPlanServer(plan).apply { start() }
        val rooms = RoomResultServer(room).apply { start() }
        val results = AlignmentResultServer(result).apply { start() }

        override fun close() {
            results.stop()
            rooms.stop()
            plans.stop()
            commands.stop()
            clockServer.stop()
        }
    }

    /** A microphone that hears quiet noise for as long as it is asked to. */
    private class QuietRoom(
        private val store: RunStore,
        private val caseId: String,
        private val hostNanosNow: () -> Long
    ) : RoundRecorder {
        override var startedAtHostNanos: Long? = null

        override fun record(seconds: Int, stopped: () -> Boolean) {
            startedAtHostNanos = hostNanosNow()
            val random = Random(3)
            val bytes = ByteArray(seconds * 48_000 * 2)
            for (index in bytes.indices step 2) {
                val sample = (random.nextDouble() * 60 - 30).toInt()
                bytes[index] = (sample and 0xFF).toByte()
                bytes[index + 1] = (sample shr 8).toByte()
            }
            WavFileWriter(File(store.prepareRun(caseId), "calibration.wav"), 48_000, 1)
                .use { it.writePcm(bytes, bytes.size) }
        }

        override fun discardRecording() {}
    }

    /** A pair round short enough for a test: one chirp each, no clock fill. */
    private val shortPair: (Boolean) -> SinkRoundRequest = { room ->
        SinkRoundRequest(room = room, timingFor = { ArmSchedule(1, 2 * second, second / 2, 2 * second, 0L) })
    }

    private fun sink(directory: File, microphone: Pair<MicrophoneProblem, String>? = null) = SinkSession(
        directory,
        openSpeakers = FakeSpeakers()::open,
        called = "DESK",
        microphoneProblem = { microphone },
        roundRecorder = ::QuietRoom,
        roundRequest = shortPair
    )

    private fun SinkSession.startOn(host: PhoneHost) =
        start("127.0.0.1", host.chunk, host.clock, host.command, host.spatial, host.plan, host.room, host.result)

    /** Serves one room: every asker gets a slot after the host's, and every delivery the same offer. */
    private fun serveRoom(host: PhoneHost, firstChirpIn: Long = 3 * second, offerMicros: Long? = 1200) = thread {
        host.plans.awaitRoom(firstWaitMillis = 20_000, settleMillis = 300, roomWindowMillis = 20_000, enough = { it >= 1 }) { asks ->
            CalibrationPlan(
                "C94", host.hostId, System.nanoTime() + firstChirpIn, second / 2, 1, 2 * second,
                listOf(host.hostId) + asks.map { it.sinkId }
            )
        }
        host.rooms.awaitRoom(expected = 1, timeoutMillis = 60_000) { deliveries ->
            deliveries.associate { it.senderId to RoomReply(2, 1, offerMicros) }
        }
    }

    /**
     * A room round the host started: joined, answered, and the host's offer kept because nothing
     * measured stands in its way - filed under the id the plan named, since this machine was given
     * an address and nothing else, and remembered as that address's host.
     */
    @Test
    fun aRoomRoundIsJoinedAndTheOfferKeptUnderTheHostThePlanNamed() {
        PhoneHost().use { host ->
            val directory = folder.newFolder()
            val sink = sink(directory)
            try {
                sink.startOn(host)
                assertTrue(eventually(10_000) { host.commands.standingBy() == 1 })
                val served = serveRoom(host)
                host.commands.send(RoomCommand.MEASURE_ROOM)

                assertTrue("never measured: ${sink.status()}", eventually(10_000) { sink.status().stage == SinkStage.MEASURING })
                assertTrue("no answer: ${sink.status().round}", eventually(40_000) { sink.status().round is RoundLine.RoomSinkApproximate })
                served.join(10_000)

                assertEquals(1200L, StoredApproximateCalibration(directory, host.hostId).read())
                assertEquals(host.hostId, PairedHost(directory).read()?.hostId)
                // Said to the host once the line is dialled again, as the handset's dialIfChanged does.
                val sinkId = HostIdentity(directory).current()
                assertTrue(
                    "the host never heard what this machine now carries: ${host.commands.carrying()}",
                    eventually(10_000) { host.commands.carrying()[sinkId] == Carried.APPROXIMATE }
                )
            } finally {
                sink.stop()
            }
        }
    }

    /**
     * A pair round folds the host's answer into the constant, and the guess a room left goes -
     * then the playing is shifted by it, and the host is told this machine carries a measurement.
     */
    @Test
    fun aPairRoundStoresTheConstantAndThePlayingUsesIt() {
        PhoneHost().use { host ->
            val directory = folder.newFolder()
            PairedHost(directory).write(PairingCode(host.hostId, "127.0.0.1", host.chunk))
            StoredApproximateCalibration(directory, host.hostId).write(900)
            val sink = sink(directory)
            try {
                sink.startOn(host)
                assertTrue(eventually(10_000) { host.commands.standingBy() == 1 })
                val sinkId = HostIdentity(directory).current()
                assertTrue(eventually(5_000) { host.commands.carrying()[sinkId] == Carried.APPROXIMATE })
                val served = thread {
                    host.plans.awaitRequest(20_000) { ask ->
                        CalibrationPlan(ask.caseId, host.hostId, System.nanoTime() + 3 * second, second / 2, 1, 2 * second)
                    }
                    host.results.awaitResult(60_000) { CalibrationReply(1500, 1500, true) }
                }
                host.commands.sendTo(sinkId, com.soundmesh.core.RoomOrder(RoomCommand.MEASURE_PAIR))

                assertTrue("no answer: ${sink.status().round}", eventually(40_000) { sink.status().round is RoundLine.Done })
                served.join(10_000)
                assertEquals(1500L, StoredCalibration(directory, host.hostId).read()?.micros)
                assertNull(StoredApproximateCalibration(directory, host.hostId).read())
                assertTrue(eventually(10_000) { host.commands.carrying()[sinkId] == Carried.SOMETHING })

                host.commands.send(RoomCommand.PLAY)
                assertTrue("not played by: ${sink.status()}", eventually(10_000) { sink.status().alignmentMillis == 1.5 })
            } finally {
                sink.stop()
            }
        }
    }

    /** Measured over approximate, as the handset sink reads them, with both on disk. */
    @Test
    fun theMeasurementOutranksTheApproximationWhenBothAreThere() {
        PhoneHost().use { host ->
            val directory = folder.newFolder()
            PairedHost(directory).write(PairingCode(host.hostId, "127.0.0.1", host.chunk))
            StoredCalibration(directory, host.hostId).write(1500, 1)
            StoredApproximateCalibration(directory, host.hostId).write(900)
            val sink = sink(directory)
            try {
                sink.startOn(host)
                assertTrue(eventually(10_000) { host.commands.standingBy() == 1 })
                host.commands.send(RoomCommand.PLAY)
                assertTrue("played by ${sink.status().alignmentMillis}", eventually(10_000) { sink.status().alignmentMillis == 1.5 })
            } finally {
                sink.stop()
            }
        }
    }

    /** A machine that cannot record says so at once, rather than taking a slot the room waits on. */
    @Test
    fun aMachineThatCannotRecordSaysNoMicrophone() {
        PhoneHost().use { host ->
            val directory = folder.newFolder()
            val sink = sink(directory, microphone = MicrophoneProblem.DENIED to "Activate failed: 0x80070005")
            try {
                sink.startOn(host)
                assertTrue(eventually(10_000) { host.commands.standingBy() == 1 })
                host.commands.send(RoomCommand.MEASURE_ROOM)

                val sinkId = HostIdentity(directory).current()
                assertTrue(eventually(10_000) { host.commands.excuses()[sinkId] == RoomExcuse.NO_MICROPHONE })
                assertEquals(MicrophoneProblem.DENIED, sink.status().microphone)
            } finally {
                sink.stop()
            }
        }
    }

    /**
     * In a round, anything but a call-off is answered "busy"; a call-off ends the round with nothing
     * of it kept, and this machine is back standing by.
     */
    @Test
    fun aRoundIsBusyToEverythingButACallOffWhichEndsIt() {
        PhoneHost().use { host ->
            val directory = folder.newFolder()
            val sink = sink(directory)
            try {
                sink.startOn(host)
                assertTrue(eventually(10_000) { host.commands.standingBy() == 1 })
                // The first chirp far off, so the round is still waiting for it when it is called off.
                serveRoom(host, firstChirpIn = 60 * second)
                host.commands.send(RoomCommand.MEASURE_ROOM)
                assertTrue("never ran: ${sink.status().round}", eventually(20_000) { sink.status().round == RoundLine.Running })

                val sinkId = HostIdentity(directory).current()
                host.commands.send(RoomCommand.PLAY)
                assertTrue(eventually(10_000) { host.commands.excuses()[sinkId] == RoomExcuse.BUSY })

                host.commands.send(RoomCommand.CALL_OFF)
                assertTrue("not called off: ${sink.status().round}", eventually(10_000) { sink.status().round == RoundLine.CalledOff })
                assertTrue(eventually(10_000) { sink.status().stage == SinkStage.STANDING_BY })
                assertNull(StoredApproximateCalibration(directory, host.hostId).read())
            } finally {
                sink.stop()
            }
        }
    }
}
