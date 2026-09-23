package com.soundmesh.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.Socket

class HostSessionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun ports() = HostPorts(chunk = freeTcpPort(), clock = freeUdpPort(), command = freeTcpPort())

    private fun session(ports: HostPorts, speakers: FakeSpeakers = FakeSpeakers()) =
        HostSession(folder.root, ports, advertise = false, openSpeakers = speakers::open, retellMillis = 50L)

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
}
