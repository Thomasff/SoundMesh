package com.soundmesh.desktop

import com.soundmesh.core.RoomCommand
import com.soundmesh.core.RoomOrder
import com.soundmesh.probe.sync.RoomCommandClient
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HostSessionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun ports() = HostPorts(chunk = freeTcpPort(), clock = freeUdpPort(), command = freeTcpPort())

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
            assertTrue("the handset never appeared", eventually { host.status().phones == listOf("书房") })
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

    private companion object {
        const val PHONE = "a1b2c3d4e5f60718"
    }
}
