package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.RoomExcuse
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.WavFileWriter
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.RoundRecorder
import com.soundmesh.probe.sync.RoundSpeaker
import com.soundmesh.probe.sync.StoredListenerDistance
import com.soundmesh.probe.sync.StoredSeparation
import com.soundmesh.product.ArmSchedule
import com.soundmesh.product.RoundLine
import com.soundmesh.product.SinkRoundRequest
import java.io.File
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * This machine as the host of a measuring round, with desktop sinks standing by on its command
 * line the way handsets do - the round core's own, the audio stood in for.
 *
 * What the distances come to is HostRoundTest's business, where the air is simulated. What is
 * under test here is everything around it on this machine: that the devices standing by are told,
 * that what they say comes back against their row, that this machine will not measure without a
 * microphone or play while measuring, and where the listener is taken to sit.
 */
class HostMeasuringTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val second = 1_000_000_000L

    private val ports = HostPorts(
        chunk = freeTcpPort(), clock = freeUdpPort(), command = freeTcpPort(), spatial = freeTcpPort(),
        result = freeTcpPort(), plan = freeTcpPort(), room = freeTcpPort()
    )

    /** Short enough for a test: one chirp each, no clock fill. */
    private val short: (String?) -> ArmSchedule = { ArmSchedule(1, 2 * second, second / 2, 2 * second, 0L) }

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

    private class QuietSpeaker : RoundSpeaker {
        override fun start(endAtHostNanos: Long) {}
        override fun submit(chunk: AudioChunk) {}
        override fun stopNow() {}
        override fun finish(): String? = "{}"
    }

    private fun host(
        directory: File = folder.newFolder("host"),
        microphone: () -> Pair<MicrophoneProblem, String>? = { null }
    ) = HostSession(
        directory,
        ports,
        advertise = false,
        openSpeakers = FakeSpeakers()::open,
        retellMillis = 50L,
        microphoneProblem = microphone,
        roundRecorder = ::QuietRoom,
        roundSpeaker = { QuietSpeaker() },
        timingFor = short
    )

    private fun sink(directory: File, microphone: Pair<MicrophoneProblem, String>? = null) = SinkSession(
        directory,
        openSpeakers = FakeSpeakers()::open,
        called = "DESK",
        microphoneProblem = { microphone },
        roundRecorder = ::QuietRoom,
        roundRequest = { room -> SinkRoundRequest(room = room, timingFor = short) }
    )

    private fun SinkSession.startOnHost() =
        start("127.0.0.1", ports.chunk, ports.clock, ports.command, ports.spatial, ports.plan, ports.room, ports.result)

    private fun HostSession.idle() = eventually(60_000) { !status().measure.running }

    /**
     * The devices standing by are told, join, and the round comes to an answer on this machine's
     * window: two devices in the room, one pair between them - quiet recordings measure nothing.
     */
    @Test
    fun theDevicesStandingByAreToldAndTheRoundComesToAnAnswer() {
        val host = host()
        val sinkDir = folder.newFolder("sink")
        val sink = sink(sinkDir)
        try {
            host.open()
            sink.startOnHost()
            assertTrue(eventually(10_000) { host.status().phones.size == 1 })
            host.measureRoom()
            assertTrue("the sink was never in the round", eventually(20_000) { sink.status().stage == SinkStage.MEASURING })
            assertTrue(host.idle())

            val measure = host.status().measure
            assertEquals(RoundLine.RoomDone(2, 0, 1), measure.line)
            assertTrue("it said it measured a room", measure.roomMeasured)
            assertTrue("the sink heard no answer: ${sink.status().round}", eventually(10_000) {
                sink.status().round is RoundLine.RoomSinkDone
            })
        } finally {
            sink.stop()
            host.close()
        }
    }

    /** Why a device kept out is said against its row and among the round's lines, as on the handset. */
    @Test
    fun aDeviceThatCannotRecordIsSaidAgainstItsRow() {
        val host = host()
        val sinkDir = folder.newFolder("sink")
        val sink = sink(sinkDir, microphone = MicrophoneProblem.DENIED to "Activate failed: 0x80070005")
        try {
            host.open()
            sink.startOnHost()
            assertTrue(eventually(10_000) { host.status().phones.size == 1 })
            host.measureRoom()

            val sinkId = HostIdentity(sinkDir).current()
            assertTrue(eventually(10_000) { host.status().phones.single().excuse == RoomExcuse.NO_MICROPHONE })
            assertTrue(host.status().measure.heard.contains(sinkId to RoundLine.Excused(RoomExcuse.NO_MICROPHONE)))
            // Everybody told has said why not, and the round still waits for an ask, as the
            // handset host's does - so it is called off here rather than waited out.
            host.callOffMeasuring()
            assertTrue(host.idle())
        } finally {
            sink.stop()
            host.close()
        }
    }

    /**
     * This machine without a microphone does not start a round at all: the devices would chirp for
     * a host that hears none of it. Said as which of the problems it is.
     */
    @Test
    fun noRoundStartsWhenThisMachineCannotRecord() {
        val host = host(microphone = { MicrophoneProblem.NO_DEVICE to "GetDefaultAudioEndpoint" })
        val sinkDir = folder.newFolder("sink")
        val sink = sink(sinkDir)
        try {
            host.open()
            sink.startOnHost()
            assertTrue(eventually(10_000) { host.status().phones.size == 1 })
            host.measureRoom()
            assertTrue(host.idle())

            assertEquals(MicrophoneProblem.NO_DEVICE, host.status().measure.microphone)
            Thread.sleep(1_000)
            assertFalse("the sink was told to measure", sink.status().stage == SinkStage.MEASURING)
        } finally {
            sink.stop()
            host.close()
        }
    }

    /** Nothing plays while a round is recording the room - what it would measure is the music. */
    @Test
    fun nothingPlaysWhileARoundRuns() {
        val asked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val host = host(microphone = {
            asked.countDown()
            release.await(20, TimeUnit.SECONDS)
            MicrophoneProblem.OTHER to "held for the test"
        })
        val song = File(folder.root, "tone.wav").also { writeTone(it, seconds = 2) }
        try {
            host.open()
            host.measureRoom()
            assertTrue(asked.await(10, TimeUnit.SECONDS))
            host.play(song, alsoHere = false)
            Thread.sleep(500)
            assertFalse("it played while measuring", host.status().playing)
        } finally {
            release.countDown()
            host.close()
        }
    }

    /**
     * The listener is taken to sit half a metre from this machine - but only once anything has
     * been measured to them, so an unmeasured room is not told where they sit.
     */
    @Test
    fun theListenerIsHalfAMetreFromThisMachineOnceAnythingIsMeasured() {
        val directory = folder.newFolder("host")
        val first = host(directory)
        try {
            first.open()
            assertEquals(emptyMap<String, Double>(), first.status().room.listenerMetres)
        } finally {
            first.close()
        }

        StoredListenerDistance(directory, PHONE).write(2.0)
        val second = host(directory)
        try {
            second.open()
            val selfId = HostIdentity(directory).current()
            assertEquals(mapOf(PHONE to 2.0, selfId to HostSession.LISTENER_METRES), second.status().room.listenerMetres)
        } finally {
            second.close()
        }
    }

    /**
     * The drawing moved onto the measured shape when asked, and offered again once somebody drags
     * an icon - a drag is a fresh opinion for the fit to answer.
     *
     * One device and this machine is the room most people will have, and it has a shape only
     * because the listener is in it: this machine to the device, the listener to the device, and
     * the listener half a metre from this machine. What a round from here files is set up by hand.
     */
    @Test
    fun theDrawingIsFittedWhenAskedAndOfferedAgainAfterADrag() {
        val directory = folder.newFolder("host")
        val sinkDir = folder.newFolder("sink")
        val sinkId = HostIdentity(sinkDir).current()
        StoredSeparation(directory, sinkId).write(2.0)
        StoredListenerDistance(directory, sinkId).write(2.0)
        val host = host(directory)
        val sink = sink(sinkDir)
        try {
            host.open()
            sink.startOnHost()
            assertTrue(eventually(10_000) { host.status().room.measuredMetres.isNotEmpty() })
            assertFalse(host.status().room.fitted)

            host.fitRoom()
            assertTrue("not fitted", host.status().room.fitted)
            assertTrue("no scale, so nothing is held back", host.status().room.metresPerUnit > 0.0)

            val icon = host.status().room.icons.first { it.peerId == sinkId }
            host.moveIcon(icon.copy(x = icon.x * 0.5f))
            assertFalse("still says fitted after a drag", host.status().room.fitted)
        } finally {
            sink.stop()
            host.close()
        }
    }

    /** Called off while gathering, the round says so and nothing is measured. */
    @Test
    fun aRoundCalledOffWhileGatheringSaysSo() {
        val host = host()
        try {
            host.open()
            // Nobody standing by, so the round waits to be joined until it is called off.
            host.measureRoom()
            assertTrue(eventually(10_000) { host.status().measure.line is RoundLine.RoomToldNobody })
            host.callOffMeasuring()
            assertTrue(host.idle())
            assertEquals(RoundLine.RoomCalledOffHere, host.status().measure.line)
            assertFalse(host.status().measure.roomMeasured)
        } finally {
            host.close()
        }
    }

    private fun writeTone(file: File, seconds: Int) {
        val frames = seconds * 48_000
        val bytes = ByteArray(frames * 4)
        for (frame in 0 until frames) {
            val sample = (Math.sin(frame * 2 * Math.PI * 440 / 48_000) * 8000).toInt()
            for (channel in 0 until 2) {
                bytes[frame * 4 + channel * 2] = (sample and 0xFF).toByte()
                bytes[frame * 4 + channel * 2 + 1] = (sample shr 8).toByte()
            }
        }
        WavFileWriter(file, 48_000, 2).use { it.writePcm(bytes, bytes.size) }
    }

    private companion object {
        const val PHONE = "a1b2c3d4e5f60718"
    }
}
