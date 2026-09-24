package com.soundmesh.desktop

import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomOrder
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SplitAxis
import com.soundmesh.probe.sync.RoomCommandClient
import com.soundmesh.probe.sync.SpatialFieldClient
import com.soundmesh.product.DEFAULT_REVERB
import com.soundmesh.product.EffectKind
import com.soundmesh.product.RoomIcon
import com.soundmesh.product.SourceSpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HostSessionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun ports() = HostPorts(chunk = freeTcpPort(), clock = freeUdpPort(), command = freeTcpPort(), spatial = freeTcpPort())

    private fun session(ports: HostPorts, speakers: FakeSpeakers = FakeSpeakers()) =
        HostSession(folder.root, ports, advertise = false, openSpeakers = speakers::open, retellMillis = 50L)

    private fun standBy(port: Int, heard: ArrayBlockingQueue<RoomOrder>, selfId: String = PHONE, called: String? = "书房"): RoomCommandClient =
        RoomCommandClient("127.0.0.1", port, selfId, null, called = called) { heard.offer(it) }.also { it.start() }

    /**
     * Picking host, then sink, then host again closes and reopens all three ports in one process,
     * and the window makes that one click each. A port still held by a thread in accept would make
     * the second host fail to start with nothing wrong on screen but a port number.
     */
    @Test
    fun beingTheHostAgainAndAgainReopensEveryPort() {
        val ports = ports()
        val host = session(ports)
        repeat(30) { round ->
            host.open()
            val status = host.status()
            assertNull("round $round: ${status.problem}", status.problem)
            assertTrue("round $round: not open", status.open)
            Socket("127.0.0.1", ports.command).close()
            Socket("127.0.0.1", ports.chunk).close()
            Socket("127.0.0.1", ports.spatial).close()
            host.close()
            assertFalse("round $round: still open after close", host.status().open)
        }
    }

    /**
     * A port somebody else holds is said by name, and nothing is left half open behind it: the
     * ports that did bind are given back, so the other host keeps working and this one can try
     * again once it has gone.
     */
    @Test
    fun aSecondHostOnTheSamePortsIsToldWhichPortIsTaken() {
        val ports = ports()
        val first = session(ports)
        val second = session(ports)
        try {
            first.open()
            second.open()
            val status = second.status()
            assertFalse(status.open)
            assertEquals(HostProblem.PortTaken(HostPort.COMMAND, ports.command), status.problem)
            Socket("127.0.0.1", ports.chunk).close()

            first.close()
            second.open()
            assertTrue("the second host could not start after the first had gone", second.status().open)
        } finally {
            first.close()
            second.close()
        }
    }

    /**
     * The one thing the desktop host could not do before: a handset standing by starts when the
     * host plays and stops when it stops. And by the time it hears "play" the audio port has to
     * answer, or it dials a refused connection and shows a failure.
     */
    @Test
    fun aHandsetStandingByIsToldToPlayAndThenToStop() {
        val ports = ports()
        val host = session(ports)
        val heard = ArrayBlockingQueue<RoomOrder>(8)
        host.open()
        val phone = standBy(ports.command, heard)
        try {
            assertTrue("the handset never appeared", eventually { host.status().phones.map { it.name } == listOf("书房") })
            host.play(writeTestWav(folder.newFile("clip.wav")), alsoHere = false)

            assertEquals(RoomOrder(RoomCommand.PLAY), heard.poll(5, TimeUnit.SECONDS))
            Socket("127.0.0.1", ports.chunk).close()
            assertTrue(host.status().playing)

            host.stopPlaying()
            assertEquals(RoomOrder(RoomCommand.STOP), heard.poll(5, TimeUnit.SECONDS))
            assertFalse(host.status().playing)
        } finally {
            phone.close()
            host.close()
        }
    }

    /**
     * The roster gives each device its own colour, apart from this machine's, and while the room
     * plays it says which one is not taking the audio - here one that was told to play and never
     * dialled it, the way a handset that dropped out looks from the host.
     */
    @Test
    fun theRosterColoursEachDeviceAndSaysWhichIsNotTakingTheAudio() {
        val ports = ports()
        val host = session(ports)
        val heard = ArrayBlockingQueue<RoomOrder>(8)
        host.open()
        val phone = standBy(ports.command, heard)
        try {
            assertTrue(eventually { host.status().phones.singleOrNull()?.place != null })
            val idle = host.status()
            val row = idle.phones.single()
            assertNotNull(idle.selfPlace)
            assertTrue("the handset has the host's colour", row.place != idle.selfPlace)
            assertFalse("stopped before anything played", row.stopped)

            host.play(writeTestWav(folder.newFile("clip.wav")), alsoHere = false)
            assertEquals(RoomOrder(RoomCommand.PLAY), heard.poll(5, TimeUnit.SECONDS))
            // Not straight away: at the start of every song nobody has dialled in yet.
            assertFalse("called stopped while still dialling in", host.status().phones.single().stopped)
            assertTrue("never said to be off the audio", eventually { host.status().phones.single().stopped })
        } finally {
            phone.close()
            host.close()
        }
    }

    /**
     * An effect chosen in the window reaches a device on the spatial channel as the handset host
     * would send it, and the drawing it carries has the host and the device standing by in it.
     */
    @Test
    fun aChosenEffectReachesADeviceOnTheSpatialChannel() {
        val ports = ports()
        val host = session(ports)
        val heard = ArrayBlockingQueue<RoomOrder>(8)
        val rules = LinkedBlockingQueue<SpatialField>()
        host.open()
        val phone = standBy(ports.command, heard)
        val drawing = SpatialFieldClient("127.0.0.1", ports.spatial, PHONE) { rules.offer(it) }.apply { start() }
        try {
            assertTrue(eventually { host.status().room.icons.size == 2 })
            host.setEffect(EffectKind.SPIN)
            val spun = generateSequence { rules.poll(5, TimeUnit.SECONDS) }.first { it.mode == SpatialMode.ROTATE }
            assertEquals(DEFAULT_REVERB.toDouble(), spun.reverb, 1e-6)
            assertTrue("the device is not in the drawing", spun.layout.contains(PHONE))
            assertEquals(2, spun.layout.positions.size)
        } finally {
            drawing.stop()
            phone.close()
            host.close()
        }
    }

    /**
     * What is done on the drawing reaches the room: a device dragged, a device handed the other
     * half of the split, and 自定义声音位置's dot dragged to one side.
     */
    @Test
    fun whatIsDoneOnTheDrawingReachesTheRule() {
        val ports = ports()
        val host = session(ports)
        val heard = ArrayBlockingQueue<RoomOrder>(8)
        val rules = LinkedBlockingQueue<SpatialField>()
        host.open()
        val phone = standBy(ports.command, heard)
        val drawing = SpatialFieldClient("127.0.0.1", ports.spatial, PHONE) { rules.offer(it) }.apply { start() }
        fun next(check: (SpatialField) -> Boolean): SpatialField =
            generateSequence { rules.poll(5, TimeUnit.SECONDS) }.first(check)
        try {
            assertTrue(eventually { host.status().room.icons.size == 2 })
            host.moveIcon(RoomIcon(PHONE, 0.8f, 0.5f))
            val moved = next { rule -> rule.layout.positions.any { it.peerId == PHONE && it.x > 0.25 } }
            assertEquals(0.0, moved.layout.positions.single { it.peerId == PHONE }.y, 1e-6)

            host.setSplit(SplitAxis.LOW_HIGH)
            host.togglePart(PHONE)
            val split = next { PHONE in it.otherHalfIds }
            assertEquals(SplitAxis.LOW_HIGH, split.splitAxis)
            assertEquals(1.0, split.separation, 1e-6)

            host.setEffect(EffectKind.PLACE)
            host.moveSource(SourceSpot(0.9f, 0.5f))
            val placed = next { it.mode == SpatialMode.PAN && it.pan > 0.4 }
            assertEquals("a moving source drops the split from the rule", 0.0, placed.separation, 1e-6)
        } finally {
            drawing.stop()
            phone.close()
            host.close()
        }
    }

    /**
     * The drawing outlives the window, as it outlives the handset host's screen: where a device
     * was put and which half it carried come back the next time this machine is the host, for a
     * device that was not in the room when it opened and only stood by afterwards.
     */
    @Test
    fun theDrawingIsThereTheNextTimeThisIsTheHost() {
        val ports = ports()
        val heard = ArrayBlockingQueue<RoomOrder>(8)
        val first = session(ports)
        first.open()
        val phone = standBy(ports.command, heard)
        try {
            assertTrue(eventually { first.status().room.icons.size == 2 })
            first.moveIcon(RoomIcon(PHONE, 0.8f, 0.5f))
            first.setSplit(SplitAxis.LOW_HIGH)
            first.togglePart(PHONE)
        } finally {
            phone.close()
            first.close()
        }

        val again = session(ports)
        again.open()
        val back = standBy(ports.command, heard)
        try {
            assertTrue(eventually { again.status().room.icons.size == 2 })
            val room = again.status().room
            val icon = room.icons.single { it.peerId == PHONE }
            assertEquals(0.8f, icon.x, 1e-6f)
            assertEquals(0.5f, icon.y, 1e-6f)
            assertEquals(SplitAxis.LOW_HIGH, room.splitAxis)
            assertTrue("the half it carried was forgotten", PHONE in room.otherHalfIds)
        } finally {
            back.close()
            again.close()
        }
    }

    /**
     * Between songs a handset that went stays on the drawing, hollow, where it stood - the handset
     * host's keptRoom - and one that said its own system does not exempt SoundMesh before it went
     * is the handset host's StandbyLook.KILLED. One that went without saying so is only hollow:
     * going on its own says nothing about why.
     */
    @Test
    fun aHandsetThatSaidItIsNotExemptAndWentIsDrawnKilled() {
        val ports = ports()
        val host = session(ports)
        val heard = ArrayBlockingQueue<RoomOrder>(8)
        host.open()
        val killed = standBy(ports.command, heard)
        val gone = standBy(ports.command, heard, selfId = OTHER_PHONE, called = "客厅")
        try {
            assertTrue(eventually { host.status().room.icons.size == 3 })
            assertTrue("never said it", eventually { killed.sayPower(false) })
            assertTrue(host.status().killedIds.isEmpty())
            killed.close()
            gone.close()
            assertTrue(
                "the two that went were not left hollow: ${host.status().room.silentIds}, standing ${host.status().phones.map { it.name }}",
                eventually { host.status().room.silentIds == setOf(PHONE, OTHER_PHONE) }
            )
            val status = host.status()
            assertEquals(3, status.room.icons.size)
            assertEquals(setOf(PHONE), status.killedIds)
        } finally {
            killed.close()
            gone.close()
            host.close()
        }
    }

    /**
     * 下一首 moves the list on and restarts the timeline, and what was queued of the old song is
     * thrown away - here on this machine's own output, which plays through the same playout a
     * sink does.
     */
    @Test
    fun theNextSongThrowsAwayWhatWasQueuedOfTheLast() {
        val ports = ports()
        val speakers = FakeSpeakers()
        val host = session(ports, speakers)
        host.open()
        try {
            val songs = listOf(writeTestWav(folder.newFile("a.wav")), writeTestWav(folder.newFile("b.wav")))
            host.play(songs, 0, alsoHere = true)
            assertTrue(eventually { host.status().playhead?.song == 0 && speakers.output.scheduled.size > 10 })
            assertEquals(0, speakers.output.drops)
            host.stepSong(1)
            assertTrue("never moved on: ${host.status().playhead}", eventually { host.status().playhead?.song == 1 })
            assertTrue("the old song's queue was kept", eventually { speakers.output.drops == 1 })
            assertEquals("b.wav", host.status().file)
        } finally {
            host.close()
        }
    }

    /**
     * The channel remembers nothing, so a handset that arrives while the room is playing hears
     * "play" only because the host says it again - once, not every time round.
     */
    @Test
    fun aHandsetThatArrivesWhilePlayingIsToldOnce() {
        val ports = ports()
        val host = session(ports)
        val heard = ArrayBlockingQueue<RoomOrder>(8)
        host.open()
        try {
            host.play(writeTestWav(folder.newFile("clip.wav")), alsoHere = false)
            assertTrue(eventually { host.status().playing })
            val phone = standBy(ports.command, heard)
            try {
                assertEquals(RoomOrder(RoomCommand.PLAY), heard.poll(5, TimeUnit.SECONDS))
                assertNull("told twice", heard.poll(500, TimeUnit.MILLISECONDS))
            } finally {
                phone.close()
            }
        } finally {
            host.close()
        }
    }

    /** A file that cannot be read is said, and no handset is told to play a stream that is not there. */
    @Test
    fun aFileThatCannotBeReadIsSaidAndNobodyIsToldToPlay() {
        val ports = ports()
        val host = session(ports)
        val heard = ArrayBlockingQueue<RoomOrder>(8)
        host.open()
        val phone = standBy(ports.command, heard)
        try {
            assertTrue(eventually { host.status().phones.size == 1 })
            val junk = folder.newFile("junk.wav").apply { writeText("not a wav") }
            host.play(junk, alsoHere = false)

            assertTrue(eventually { host.status().problem is HostProblem.FileUnreadable })
            assertFalse(host.status().playing)
            assertNull(heard.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            phone.close()
            host.close()
        }
    }

    /** This machine's own speakers play along, report their seams, and are let go of on stop. */
    @Test
    fun theLocalSpeakersPlayAlongAndAreClosedOnStop() {
        val ports = ports()
        val speakers = FakeSpeakers()
        val host = session(ports, speakers)
        host.open()
        try {
            host.play(writeTestWav(folder.newFile("clip.wav")), alsoHere = true)
            assertTrue(eventually { speakers.output.scheduled.size >= 5 })
            assertNotNull(host.status().localBand)
            host.stopPlaying()
            assertTrue("the speakers were left open", speakers.closed)
        } finally {
            host.close()
        }
    }

    /**
     * A stop is said again for a while, not once. Each command goes out on a write thread of its
     * own, so a PLAY and a STOP can reach a handset in either order; and a handset that has just
     * been told to play ignores a stop until its session is up. Said once, a quick stop can leave
     * a handset playing a stream that has ended. A new play ends the echo.
     */
    @Test
    fun aStopIsSaidAgainUntilANewPlay() {
        val ports = ports()
        val host = session(ports)
        val heard = ArrayBlockingQueue<RoomOrder>(64)
        host.open()
        val phone = standBy(ports.command, heard)
        try {
            assertTrue("the handset never appeared", eventually { host.status().phones.size == 1 })
            host.play(writeTestWav(folder.newFile("clip.wav")), alsoHere = false)
            assertEquals(RoomOrder(RoomCommand.PLAY), heard.poll(5, TimeUnit.SECONDS))

            host.stopPlaying()
            assertEquals(RoomOrder(RoomCommand.STOP), heard.poll(5, TimeUnit.SECONDS))
            assertEquals("the stop was said only once", RoomOrder(RoomCommand.STOP), heard.poll(2, TimeUnit.SECONDS))

            host.play(writeTestWav(folder.newFile("again.wav")), alsoHere = false)
            // A stop already on its way when play was pressed may still land; what matters is
            // that nothing says stop after the handset has been told to play.
            var order = heard.poll(5, TimeUnit.SECONDS)
            while (order == RoomOrder(RoomCommand.STOP)) order = heard.poll(5, TimeUnit.SECONDS)
            assertEquals(RoomOrder(RoomCommand.PLAY), order)
            assertNull("told to stop while playing", heard.poll(1, TimeUnit.SECONDS))
        } finally {
            phone.close()
            host.close()
        }
    }

    /**
     * A handset that opens a second command line under the same name replaces the first, and its
     * id never leaves the roster - so the host has to hear about the replacement, or it goes on
     * thinking the handset was told while the handset, back from out of range, stands by waiting.
     */
    @Test
    fun aHandsetThatReplacesItsLineIsToldToPlayAgain() {
        val ports = ports()
        val host = session(ports)
        val heard = ArrayBlockingQueue<RoomOrder>(8)
        val heardAgain = ArrayBlockingQueue<RoomOrder>(8)
        host.open()
        val first = standBy(ports.command, heard)
        var second: RoomCommandClient? = null
        try {
            assertTrue(eventually { host.status().phones.size == 1 })
            host.play(writeTestWav(folder.newFile("clip.wav")), alsoHere = false)
            assertEquals(RoomOrder(RoomCommand.PLAY), heard.poll(5, TimeUnit.SECONDS))

            second = standBy(ports.command, heardAgain)
            // Closed only once the host has dropped it for the newer line, so the id is never
            // absent from the roster - the case that matters. Before its own retry comes round.
            assertTrue("the older line was never replaced", eventually(2_000L) { !first.connected })
            first.close()

            assertEquals(RoomOrder(RoomCommand.PLAY), heardAgain.poll(5, TimeUnit.SECONDS))
        } finally {
            first.close()
            second?.close()
            host.close()
        }
    }

    /**
     * A stop whose wait for the player ran out, then a play, must not leave the old player
     * streaming beside the new one: two streams into one audio port, each counting from zero,
     * reach a handset as a sequence that keeps going backwards.
     */
    @Test
    fun aPlayerLeftBehindByAStopDoesNotStreamBesideTheNextOne() {
        val ports = ports()
        val stuck = FakeSpeakers()
        val next = FakeSpeakers()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val host = HostSession(folder.root, ports, advertise = false, retellMillis = 50L, openSpeakers = {
            if (calls.getAndIncrement() == 0) {
                release.await()
                stuck.open()
            } else {
                next.open()
            }
        })
        host.open()
        try {
            host.play(writeTestWav(folder.newFile("clip.wav")), alsoHere = true)
            assertTrue(eventually { calls.get() == 1 })
            // Longer than the stop waits for a player, so it gives up on this one.
            host.stopPlaying()
            host.play(writeTestWav(folder.newFile("again.wav")), alsoHere = true)
            assertTrue(eventually { next.output.scheduled.size >= 3 })

            release.countDown()
            assertTrue("the old player never let its speakers go", eventually { stuck.closed })
            assertEquals("the old player streamed beside the new one", 0, stuck.output.scheduled.size)
            assertTrue(host.status().playing)
        } finally {
            release.countDown()
            host.close()
        }
    }

    /**
     * A record that cannot be put on the network leaves no host behind: nothing could find it,
     * so a host that stayed open would stream to nobody with nothing on screen saying why. The
     * ports are given back so trying again can work.
     */
    @Test
    fun aRecordThatCannotBePutOnTheNetworkLeavesNoHostBehind() {
        val ports = ports()
        val host = HostSession(folder.root, ports, advertise = true, retellMillis = 50L,
            advertiser = { _, _, _ -> throw IllegalStateException("the responder said no") })
        try {
            host.open()
            val status = host.status()
            assertFalse(status.open)
            assertTrue("${status.problem}", status.problem is HostProblem.AdvertiseFailed)
            ServerSocket(ports.command).close()
            ServerSocket(ports.chunk).close()
        } finally {
            host.close()
        }
    }

    /**
     * A song plays once and ends by itself: every chunk of it is played, the handsets are told to
     * stop without anybody pressing anything - but not before the last chunk has been heard, which
     * is a lead after it was sent - and the window can tell an ending from a stop.
     */
    @Test
    fun aSongThatReachesItsEndStopsTheRoomByItself() {
        val ports = ports()
        val speakers = FakeSpeakers()
        val host = session(ports, speakers)
        val heard = ArrayBlockingQueue<RoomOrder>(64)
        host.open()
        val phone = standBy(ports.command, heard)
        try {
            assertTrue(eventually { host.status().phones.size == 1 })
            val pressed = System.nanoTime()
            host.play(writeTestWav(folder.newFile("short.wav"), seconds = 0.2), alsoHere = true)
            assertEquals(RoomOrder(RoomCommand.PLAY), heard.poll(5, TimeUnit.SECONDS))

            assertEquals(RoomOrder(RoomCommand.STOP), heard.poll(10, TimeUnit.SECONDS))
            val tookMillis = (System.nanoTime() - pressed) / 1_000_000
            assertTrue("stopped after $tookMillis ms, before the last chunk was heard", tookMillis >= 1_650)
            val status = host.status()
            assertFalse(status.playing)
            assertTrue(status.ended)
            assertEquals("every chunk of the song, and no more", 10, speakers.output.scheduled.size)
            assertTrue("the speakers were left open", eventually { speakers.closed })

            host.play(writeTestWav(folder.newFile("again.wav")), alsoHere = false)
            assertFalse("a new play still says the last one ended", host.status().ended)
        } finally {
            phone.close()
            host.close()
        }
    }

    /** Stop pressed while the last chunks are still to be heard is a stop, not an ending. */
    @Test
    fun aStopInTheLastSecondsIsAStopNotAnEnding() {
        val ports = ports()
        val speakers = FakeSpeakers()
        val host = session(ports, speakers)
        host.open()
        try {
            host.play(writeTestWav(folder.newFile("short.wav"), seconds = 0.2), alsoHere = true)
            assertTrue(eventually { speakers.output.scheduled.size == 10 })
            host.stopPlaying()
            val status = host.status()
            assertFalse(status.playing)
            assertFalse(status.ended)
            assertTrue("the speakers were left open", speakers.closed)
        } finally {
            host.close()
        }
    }

    /** A capture that hands over [SAMPLE] on every sample, a packet every ten milliseconds, until closed. */
    private class FakeCapture(private val onPcm: (ByteArray, Int) -> Unit) : CaptureHandle {
        @Volatile override var gain = 1f
        @Volatile var closed = false

        override fun start() {
            Thread {
                val packet = ByteArray(480 * 4)
                for (i in packet.indices step 2) {
                    packet[i] = (SAMPLE.toInt() and 0xFF).toByte()
                    packet[i + 1] = (SAMPLE.toInt() shr 8).toByte()
                }
                while (!closed) {
                    onPcm(packet, packet.size)
                    Thread.sleep(10)
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() {
            closed = true
        }
    }

    private fun capturingSession(ports: HostPorts, mixer: FakeMixer, captures: MutableList<FakeCapture>, pid: Long = MUSIC) =
        HostSession(
            folder.root, ports, advertise = false, openSpeakers = FakeSpeakers()::open, retellMillis = 50L,
            mixer = mixer,
            openCapture = { asked, onPcm ->
                if (asked != pid) error("no such process")
                FakeCapture(onPcm).also { captures.add(it) }
            }
        )

    /**
     * 抓取音频 on the desktop: the room is sent what the program plays, the program is turned
     * down here while it lasts with the gain that undoes it, and stop puts it back.
     */
    @Test
    fun aProgramsSoundIsSentAndItIsTurnedDownUntilStop() {
        val ports = ports()
        val mixer = FakeMixer().apply { add(MUSIC, "music", 0.6f) }
        val captures = java.util.concurrent.CopyOnWriteArrayList<FakeCapture>()
        val host = capturingSession(ports, mixer, captures)
        val received = java.util.concurrent.CopyOnWriteArrayList<com.soundmesh.core.AudioChunk>()
        host.open()
        val client = com.soundmesh.probe.sync.ChunkClient("127.0.0.1", ports.chunk) { received.add(it) }
        try {
            client.start()
            host.playApp(MUSIC, "music", alsoHere = false)
            assertTrue(eventually { host.status().capturing == "music" })
            // On the play thread, once the capture has opened.
            assertTrue("never turned down", eventually { mixer.level(MUSIC) == AppTurnDown.LEVEL })
            assertTrue("the gain does not undo it", eventually { kotlin.math.abs(captures.single().gain - 600f) < 0.01f })

            val heard = { chunk: com.soundmesh.core.AudioChunk ->
                (chunk.pcm.indices step 2).all { chunk.pcm[it] == (SAMPLE.toInt() and 0xFF).toByte() && chunk.pcm[it + 1] == (SAMPLE.toInt() shr 8).toByte() }
            }
            assertTrue("the program's sound never reached the room", eventually { received.any(heard) })

            host.stopPlaying()
            assertTrue("the capture was left open", captures.single().closed)
            assertEquals(0.6f, mixer.level(MUSIC))
            assertNull(host.status().capturing)
            assertTrue(host.status().heldDown.isEmpty())
        } finally {
            client.stop()
            host.close()
        }
    }

    /** A program that cannot be captured is said by name, and nothing about it is touched. */
    @Test
    fun aProgramThatCannotBeCapturedIsSaidAndLeftAlone() {
        val ports = ports()
        val mixer = FakeMixer().apply { add(MUSIC + 1, "other", 0.6f) }
        val host = capturingSession(ports, mixer, java.util.concurrent.CopyOnWriteArrayList())
        val heard = ArrayBlockingQueue<RoomOrder>(8)
        host.open()
        val phone = standBy(ports.command, heard)
        try {
            assertTrue(eventually { host.status().phones.size == 1 })
            host.playApp(MUSIC + 1, "other", alsoHere = false)
            assertTrue(eventually { host.status().problem is HostProblem.CaptureFailed })
            assertFalse(host.status().playing)
            assertEquals(0.6f, mixer.level(MUSIC + 1))
            assertNull(heard.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            phone.close()
            host.close()
        }
    }

    /** The code a handset scans names this host, the address the window chose, and the audio port. */
    @Test
    fun thePairingCodeNamesThisHostAndItsAudioPort() {
        val ports = ports()
        val host = session(ports)
        assertNull("a code before there is a host", host.pairingCode("192.168.0.150"))
        host.open()
        try {
            val code = com.soundmesh.core.PairingCodeCodec.decode(host.pairingCode("192.168.0.150")!!)
            assertEquals(host.status().selfId, code.hostId)
            assertEquals("192.168.0.150", code.address)
            assertEquals(ports.chunk, code.chunkPort)
        } finally {
            host.close()
        }
        assertNull("a code for a host that has gone", host.pairingCode("192.168.0.150"))
    }

    /** A program a host that died left turned down is put back when this machine is host again. */
    @Test
    fun openingPutsBackWhatADeadHostLeftDown() {
        val mixer = FakeMixer().apply { add(MUSIC, "music", 0.7f) }
        AppTurnDown(folder.root, mixer).turnDown(MUSIC, "music")
        val host = capturingSession(ports(), mixer, java.util.concurrent.CopyOnWriteArrayList())
        try {
            host.open()
            assertEquals(0.7f, mixer.level(MUSIC))
        } finally {
            host.close()
        }
    }

    private companion object {
        const val PHONE = "a1b2c3d4e5f60718"
        const val OTHER_PHONE = "f0e1d2c3b4a59687"
        const val MUSIC = 4242L
        const val SAMPLE: Short = 1234
    }
}
