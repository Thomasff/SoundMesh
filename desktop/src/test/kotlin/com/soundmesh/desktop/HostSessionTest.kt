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
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

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

    private companion object {
        const val PHONE = "a1b2c3d4e5f60718"
    }
}
