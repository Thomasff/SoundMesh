package com.soundmesh.product

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomExcuse
import com.soundmesh.core.RoomOrder
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.WavFileWriter
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.RoundRecorder
import com.soundmesh.probe.sync.RoundSpeaker
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.probe.sync.StoredListenerDistance
import com.soundmesh.probe.sync.StoredRoomField
import com.soundmesh.probe.sync.StoredSeparation
import java.io.File
import java.net.DatagramSocket
import java.net.ServerSocket
import java.util.Collections
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A host's round and two sinks' rounds, all core's own, run against each other on this machine's
 * loopback with the air stood in for.
 *
 * The air is the one thing faked: every speaker writes what it plays onto one shared table at the
 * host instant it was scheduled for, and every recorder, when its window closes, hears everybody
 * else's sound late by the distance between them over the speed of sound. Everything else is the
 * product - the clock exchange, the plan, the chirps, the correlation, the combining, the filing -
 * so the distances that come out are the round's answer, not the test's.
 *
 * Three devices at the corners of a right triangle: the host at the right angle, 2 m to one sink,
 * 1.5 m to the other, and the sinks 2.5 m apart.
 */
class HostRoundTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val second = 1_000_000_000L

    /** Short enough for a test: two chirps each, no clock fill, a two-second lead. */
    private val short: (String?) -> ArmSchedule = { ArmSchedule(2, 2 * second, second / 2, 2 * second, 0L) }

    private class Air(private val places: Map<String, Pair<Double, Double>>) {
        private val sounds = Collections.synchronizedList(ArrayList<Pair<String, AudioChunk>>())

        /** Devices whose speaker plays nothing, as one turned right down would. */
        val muted: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

        fun speaker(id: String): RoundSpeaker = object : RoundSpeaker {
            override fun start(endAtHostNanos: Long) {}
            override fun submit(chunk: AudioChunk) {
                if (id !in muted) sounds.add(id to chunk)
            }
            override fun stopNow() {}
            override fun finish(): String? = "{}"
        }

        fun recorder(id: String, store: RunStore, caseId: String, hostNanosNow: () -> Long): RoundRecorder =
            object : RoundRecorder {
                override var startedAtHostNanos: Long? = null

                override fun record(seconds: Int, stopped: () -> Boolean) {
                    val opened = hostNanosNow()
                    startedAtHostNanos = opened
                    val deadline = System.nanoTime() + seconds * 1_000_000_000L
                    while (System.nanoTime() < deadline && !stopped()) Thread.sleep(20)
                    write(File(store.prepareRun(caseId), "calibration.wav"), hear(id, opened, seconds))
                    write(File(store.prepareRun(caseId), "chirp.wav"), ChirpGenerator.generateMono())
                }

                override fun discardRecording() {}
            }

        /** Everything [id] would have heard from [opened] on, each sound late by its flight. */
        private fun hear(id: String, opened: Long, seconds: Int): ShortArray {
            val rate = ChirpGenerator.SAMPLE_RATE
            val mix = DoubleArray(seconds * rate)
            val noise = Random(id.hashCode().toLong())
            for (index in mix.indices) mix[index] = noise.nextDouble() * 60 - 30
            val here = places.getValue(id)
            for ((from, chunk) in synchronized(sounds) { ArrayList(sounds) }) {
                val there = places.getValue(from)
                val metres = hypot(here.first - there.first, here.second - there.second)
                // Its own speaker loudest, as a handset's is; nothing here depends on that.
                val gain = 0.3 / maxOf(metres, 0.3)
                val at = Math.round((chunk.playAtHostNanos - opened + metres / SPEED_OF_SOUND * 1e9) * rate / 1e9)
                val frames = chunk.pcm.size / 4
                for (frame in 0 until frames) {
                    val index = at + frame
                    if (index < 0 || index >= mix.size) continue
                    val left = (chunk.pcm[frame * 4].toInt() and 0xFF) or (chunk.pcm[frame * 4 + 1].toInt() shl 8)
                    mix[index.toInt()] += left.toShort() * gain
                }
            }
            return ShortArray(mix.size) { mix[it].coerceIn(-32768.0, 32767.0).toInt().toShort() }
        }

        private fun write(file: File, samples: ShortArray) {
            val bytes = ByteArray(samples.size * 2)
            for (index in samples.indices) {
                bytes[index * 2] = (samples[index].toInt() and 0xFF).toByte()
                bytes[index * 2 + 1] = (samples[index].toInt() shr 8).toByte()
            }
            WavFileWriter(file, ChirpGenerator.SAMPLE_RATE, 1).use { it.writePcm(bytes, bytes.size) }
        }

        companion object {
            const val SPEED_OF_SOUND = 343.0
        }
    }

    /** What the host round said, kept for the assertions. */
    private class Heard : HostRoundReport {
        val lines = Collections.synchronizedList(ArrayList<RoundLine>())
        val perDevice = Collections.synchronizedMap(LinkedHashMap<String, RoundLine>())
        @Volatile var measured: List<String>? = null
        @Volatile var underWay = false
        var onUnderWay: () -> Unit = {}
        var onSay: (RoundLine) -> Unit = {}

        override fun say(line: RoundLine, untilLocalNanos: Long?) {
            lines.add(line)
            onSay(line)
        }
        override fun heard(peerId: String, line: RoundLine) {
            perDevice[peerId] = line
        }
        override fun forgetHeard() = perDevice.clear()
        override fun underWay() {
            underWay = true
            onUnderWay()
        }
        override fun measured(peerIds: List<String>) {
            measured = peerIds
        }
    }

    private val dials = RoundDials(
        clock = DatagramSocket(0).use { it.localPort },
        result = ServerSocket(0).use { it.localPort },
        plan = ServerSocket(0).use { it.localPort },
        room = ServerSocket(0).use { it.localPort },
        command = ServerSocket(0).use { it.localPort }
    )

    private val hostDir by lazy { folder.newFolder("host") }
    private val oneDir by lazy { folder.newFolder("one") }
    private val twoDir by lazy { folder.newFolder("two") }
    private val hostId by lazy { HostIdentity(hostDir).current() }
    private val one by lazy { HostIdentity(oneDir).current() }
    private val two by lazy { HostIdentity(twoDir).current() }
    private val air by lazy { Air(mapOf(hostId to (0.0 to 0.0), one to (2.0 to 0.0), two to (0.0 to 1.5))) }

    /** Called off by the host, as a sink hears it over the standing line. */
    @Volatile private var calledOffBySinks = false
    private val sinkThreads = ArrayList<Thread>()
    private val sinkLines = Collections.synchronizedMap(HashMap<String, RoundLine>())

    private fun sink(directory: File, id: String, room: Boolean): Thread = thread(name = "sink-$id") {
        SinkRound(
            filesDir = directory,
            request = SinkRoundRequest(room = room, timingFor = short),
            host = { RoundHost("127.0.0.1", hostId) },
            dials = dials,
            radioHeld = { false },
            calledOff = { calledOffBySinks },
            report = object : SinkRoundReport {
                override fun say(line: RoundLine, untilLocalNanos: Long?) {
                    sinkLines[id] = line
                }
            },
            keepsRecording = false,
            recorder = { store, caseId, hostNanosNow, _ -> air.recorder(id, store, caseId, hostNanosNow) },
            speaker = { _, _ -> air.speaker(id) }
        ).run()
    }.also { sinkThreads.add(it) }

    /** The standing line, which here starts the sinks' rounds where a handset's would obey. */
    private inner class Line(private val excuses: Map<String, RoomExcuse> = emptyMap()) : HostCommands {
        val said = Collections.synchronizedList(ArrayList<RoomCommand>())
        private var listener: ((String, RoomExcuse) -> Unit)? = null

        override fun send(command: RoomCommand): Int {
            said.add(command)
            when (command) {
                RoomCommand.MEASURE_ROOM, RoomCommand.MEASURE_OVERHEAD -> {
                    for ((directory, id) in listOf(oneDir to one, twoDir to two)) {
                        val excuse = excuses[id]
                        if (excuse != null) listener?.invoke(id, excuse) else sink(directory, id, room = true)
                    }
                    return 2
                }
                RoomCommand.CALL_OFF -> calledOffBySinks = true
                else -> Unit
            }
            return 2
        }

        override fun sendTo(peerId: String, order: RoomOrder): Boolean {
            said.add(order.command)
            excuses[peerId]?.let { excuse ->
                listener?.invoke(peerId, excuse)
                return true
            }
            if (order.command == RoomCommand.MEASURE_PAIR) sink(if (peerId == one) oneDir else twoDir, peerId, room = false)
            if (order.command == RoomCommand.MEASURE_ROOM) sink(if (peerId == one) oneDir else twoDir, peerId, room = true)
            return true
        }

        override fun standingBy(): Int = 2
        override fun forgetExcuses() {}
        override fun listenForExcuses(listener: ((String, RoomExcuse) -> Unit)?) {
            this.listener = listener
        }
    }

    private fun round(line: HostCommands, place: HostPlace, report: Heard, calledOff: () -> Boolean = { false }) =
        HostRound(
            filesDir = hostDir,
            hostId = hostId,
            commands = line,
            dials = dials,
            place = place,
            timingFor = short,
            calledOff = calledOff,
            report = report,
            keepsRecording = false,
            recorder = { store, caseId, hostNanosNow, _ -> air.recorder(hostId, store, caseId, hostNanosNow) },
            speaker = { _, _ -> air.speaker(hostId) }
        )

    /** A pair from the field whichever way round it was keyed. */
    private fun Map<Pair<String, String>, Double>.between(a: String, b: String): Double? = this[a to b] ?: this[b to a]

    private fun joinSinks() = sinkThreads.forEach { it.join(60_000) }

    private fun near(expected: Double, actual: Double?, what: String) {
        assertNotNull("$what was not measured", actual)
        assertEquals(what, expected, actual!!, 0.05)
    }

    @Test
    fun aRoomMeasuredFromTheListenersSeatIsFiledAsSeparationsAndAsTheListenersDistances() {
        val report = Heard()
        val measured = round(Line(), HostPlace.LISTENING, report).room()
        joinSinks()

        assertTrue("the room was not measured: ${report.lines}", measured)
        // In the order the sinks happened to ask, with the host last - the slot convention.
        assertEquals(setOf(one, two), report.measured?.dropLast(1)?.toSet())
        assertEquals(hostId, report.measured?.last())
        assertEquals(RoundLine.RoomDone(3, 3, 3), report.lines.last())

        val field = StoredRoomField(hostDir).read()
        near(2.0, field.between(hostId, one), "host to one in the field")
        near(1.5, field.between(hostId, two), "host to two in the field")
        near(2.5, field.between(one, two), "one to two in the field")
        near(2.0, StoredSeparation(hostDir, one).read(), "the separation to one")
        near(1.5, StoredSeparation(hostDir, two).read(), "the separation to two")
        // The host is where the listener sits, so its distances are the listener's as well.
        near(2.0, StoredListenerDistance(hostDir, one).read(), "the listener's distance to one")
        near(1.5, StoredListenerDistance(hostDir, two).read(), "the listener's distance to two")
        assertTrue("a sink did not hear the room's answer: $sinkLines", sinkLines.values.all {
            it is RoundLine.RoomSinkDone || it is RoundLine.RoomSinkApproximate
        })
    }

    @Test
    fun aHandsetHostWhereItPlaysFilesSeparationsOnly() {
        val measured = round(Line(), HostPlace.PLAYING, Heard()).room()
        joinSinks()

        assertTrue(measured)
        near(2.0, StoredSeparation(hostDir, one).read(), "the separation to one")
        assertEquals(emptyMap<String, Double>(), StoredListenerDistance.all(hostDir))
    }

    @Test
    fun aHandsetHeldOverTheListenerFilesTheListenersDistancesOnly() {
        val line = Line()
        val measured = round(line, HostPlace.OVERHEAD, Heard()).room()
        joinSinks()

        assertTrue(measured)
        assertEquals(listOf(RoomCommand.MEASURE_OVERHEAD), line.said.take(1))
        near(2.0, StoredListenerDistance(hostDir, one).read(), "the listener's distance to one")
        assertEquals(null, StoredSeparation(hostDir, one).read())
        // The pair between the two sinks is an ordinary separation, whoever is held up.
        val field = StoredRoomField(hostDir).read()
        near(2.5, field.between(one, two), "one to two in the field")
        assertEquals("the held handset's own pairs went into the field", null, field.between(hostId, one))
    }

    @Test
    fun aPairIsServedToTheNamedDeviceWhichKeepsTheConstant() {
        val report = Heard()
        val line = Line()
        val result = round(line, HostPlace.LISTENING, report).pair(one)
        joinSinks()

        assertEquals(RoundResult.SERVED, result)
        assertEquals(listOf(RoomCommand.MEASURE_PAIR), line.said)
        assertTrue("the pair's line: ${report.perDevice}", report.perDevice[one] is RoundLine.HostDone)
        assertNotNull("the sink kept no constant: $sinkLines", StoredCalibration(oneDir, hostId).read())
        near(2.0, StoredSeparation(hostDir, one).read(), "the separation to one")
        near(2.0, StoredListenerDistance(hostDir, one).read(), "the listener's distance to one")
    }

    @Test
    fun anExcuseIsSaidAgainstTheDeviceAndTheRoomGoesOnWithoutIt() {
        val report = Heard()
        val measured = round(Line(excuses = mapOf(two to RoomExcuse.NO_MICROPHONE)), HostPlace.LISTENING, report).room()
        joinSinks()

        assertTrue(measured)
        assertEquals(RoundLine.Excused(RoomExcuse.NO_MICROPHONE), report.perDevice[two])
        assertEquals(listOf(one, hostId), report.measured)
        near(2.0, StoredSeparation(hostDir, one).read(), "the separation to one")
    }

    /**
     * Run on a thread of its own and given [seconds] to finish, so that a round still waiting out
     * PLAN_WAIT_MILLIS fails the test rather than holding it for five minutes.
     */
    private fun <T> within(seconds: Long, what: String, body: () -> T): T {
        var answer: Result<T>? = null
        val runner = thread(isDaemon = true) { answer = runCatching(body) }
        runner.join(seconds * 1000)
        assertFalse("$what was still waiting after $seconds s", runner.isAlive)
        return answer!!.getOrThrow()
    }

    /** Everybody told said why not, so nobody is coming: the round ends at once with their reasons. */
    @Test
    fun aRoomWhereEverybodyToldSaidWhyNotEndsAtOnce() {
        val report = Heard()
        val line = Line(excuses = mapOf(one to RoomExcuse.MIC_MUTED, two to RoomExcuse.NO_MICROPHONE))
        val measured = within(10, "the room") { round(line, HostPlace.LISTENING, report).room() }

        assertFalse(measured)
        assertEquals(RoundLine.Excused(RoomExcuse.MIC_MUTED), report.perDevice[one])
        assertEquals(RoundLine.Excused(RoomExcuse.NO_MICROPHONE), report.perDevice[two])
        assertTrue("a failure was said over the reasons: ${report.lines}", report.lines.none { it is RoundLine.Failed })
        assertFalse(report.underWay)
    }

    /** The check's answer is the reasons, against each row, and nothing is said as not heard. */
    @Test
    fun aSoundCheckWhereEverybodySaidWhyNotAnswersWithTheirReasons() {
        val line = Line(excuses = mapOf(one to RoomExcuse.MIC_MUTED, two to RoomExcuse.ASLEEP))
        val check = within(10, "the sound check") { round(line, HostPlace.PLAYING, Heard()).soundCheck() }

        assertNotNull("a check everybody refused answered nothing", check)
        assertEquals(mapOf(one to RoomExcuse.MIC_MUTED, two to RoomExcuse.ASLEEP), check!!.excuses)
        assertEquals(emptyList<String>(), check.tookPart)
        assertEquals(
            mapOf(one to "MIC_MUTED", two to "ASLEEP"),
            unheardLines(check, listOf(hostId, one, two), { it.name }, "not heard")
        )
    }

    @Test
    fun aPairWhoseDeviceSaidWhyNotEndsAtOnceAndSaysItAgainstThatDevice() {
        val report = Heard()
        val line = Line(excuses = mapOf(one to RoomExcuse.MIC_MUTED))
        val result = within(10, "the pair") { round(line, HostPlace.PLAYING, report).pair(one) }

        assertEquals(RoundResult.NOBODY_ASKED, result)
        assertEquals(RoundLine.Excused(RoomExcuse.MIC_MUTED), report.perDevice[one])
        assertTrue("a failure was said over the reason: ${report.lines}", report.lines.none { it is RoundLine.Failed })
    }

    @Test
    fun aSoundCheckHearsEveryDeviceAndFilesNothing() {
        val check = round(Line(), HostPlace.PLAYING, Heard()).soundCheck()
        joinSinks()

        assertNotNull(check)
        assertEquals(setOf(one, two, hostId), check!!.heard)
        assertEquals(hostId, check.tookPart.last())
        assertEquals(emptyMap<Pair<String, String>, Double>(), StoredRoomField(hostDir).read())
        assertEquals(null, StoredSeparation(hostDir, one).read())
        // The sinks were answered, and offered nothing to keep.
        assertTrue("a sink did not hear the answer: $sinkLines", sinkLines.values.all { it is RoundLine.RoomSinkDone })
    }

    @Test
    fun aSoundCheckDoesNotHearADeviceThatMadeNoSound() {
        air.muted += two
        val check = round(Line(), HostPlace.PLAYING, Heard()).soundCheck()
        joinSinks()

        assertEquals(setOf(one, hostId), check!!.heard)
        assertTrue(two in check.tookPart)
    }

    /** Nothing it heard can be placed without its own chirp, so it names nobody as heard. */
    @Test
    fun aHostThatCannotHearItselfHearsNobody() {
        air.muted += hostId
        val check = round(Line(), HostPlace.PLAYING, Heard()).soundCheck()
        joinSinks()

        assertEquals(emptySet<String>(), check!!.heard)
    }

    /** The two-device check is a room of two, told by name: the other sink is not asked. */
    @Test
    fun aSoundCheckOfOneDeviceAsksThatOneAlone() {
        val line = Line()
        val check = round(line, HostPlace.PLAYING, Heard()).soundCheck(one)
        joinSinks()

        assertEquals(listOf(RoomCommand.MEASURE_ROOM), line.said)
        assertEquals(listOf(one, hostId), check!!.tookPart)
        assertEquals(setOf(one, hostId), check.heard)
    }

    @Test
    fun aRoundCalledOffOnceItIsChirpingKeepsNothing() {
        val stopping = AtomicBoolean(false)
        val report = Heard()
        val line = Line()
        val round = round(line, HostPlace.LISTENING, report) { stopping.get() }
        report.onUnderWay = {
            stopping.set(true)
            round.callOff()
        }
        val measured = round.room()
        joinSinks()

        assertFalse(measured)
        assertTrue(line.said.contains(RoomCommand.CALL_OFF))
        assertEquals(emptyMap<Pair<String, String>, Double>(), StoredRoomField(hostDir).read())
        assertEquals(null, StoredSeparation(hostDir, one).read())
        assertEquals(null, report.measured)
    }

    /**
     * Pressed in the moment between telling the room and starting to gather: the press closes the
     * plan socket first, and the gathering then finds none. That is the button working, and it is
     * said as a round called off - not as PLAN_UNBOUND, a round that broke (seen 09-24).
     */
    @Test
    fun aRoundCalledOffBeforeItGathersSaysSoRatherThanThatItBroke() {
        val stopping = AtomicBoolean(false)
        val report = Heard()
        val nobody = object : HostCommands {
            override fun send(command: RoomCommand): Int = 0
            override fun sendTo(peerId: String, order: RoomOrder): Boolean = false
            override fun standingBy(): Int = 1
            override fun forgetExcuses() {}
            override fun listenForExcuses(listener: ((String, RoomExcuse) -> Unit)?) {}
        }
        val round = round(nobody, HostPlace.LISTENING, report) { stopping.get() }
        report.onSay = { line ->
            if (line == RoundLine.RoomToldNobody) {
                stopping.set(true)
                round.callOff()
            }
        }
        assertFalse(round.room())
        assertEquals(RoundLine.RoomCalledOffHere, report.lines.last())
    }

    @Test
    fun aRoundCalledOffWhileGatheringSaysSoAndMeasuresNothing() {
        val report = Heard()
        // Nobody answers the call, so the round is still gathering when it is called off.
        val silent = object : HostCommands {
            override fun send(command: RoomCommand): Int = if (command == RoomCommand.CALL_OFF) 2 else 2
            override fun sendTo(peerId: String, order: RoomOrder): Boolean = true
            override fun standingBy(): Int = 2
            override fun forgetExcuses() {}
            override fun listenForExcuses(listener: ((String, RoomExcuse) -> Unit)?) {}
        }
        val round = round(silent, HostPlace.LISTENING, report)
        thread {
            Thread.sleep(1_500)
            round.callOff()
        }
        assertFalse(round.room())
        assertEquals(RoundLine.RoomCalledOffHere, report.lines.last())
        assertFalse(report.underWay)
    }
}
