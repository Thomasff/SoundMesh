package com.soundmesh.desktop

import com.soundmesh.core.DiscoveryFailure
import com.soundmesh.core.DiscoveryOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SinkSessionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun hostPlaying(ports: HostPorts): HostSession =
        HostSession(folder.newFolder(), ports, advertise = false, openSpeakers = FakeSpeakers()::open).apply {
            open()
            play(writeTestWav(folder.newFile()), alsoHere = false)
        }

    /** Told where the host is, the sink skips looking, settles the clock and plays what arrives. */
    @Test
    fun aSinkToldWhereTheHostIsPlaysWhatItSends() {
        val ports = HostPorts(chunk = freeTcpPort(), clock = freeUdpPort(), command = freeTcpPort())
        val host = hostPlaying(ports)
        val speakers = FakeSpeakers()
        val sink = SinkSession(folder.newFolder(), openSpeakers = speakers::open)
        try {
            sink.start("127.0.0.1", ports.chunk, ports.clock)
            assertTrue("never played: ${sink.status()}", eventually(15_000) {
                sink.status().let { it.stage == SinkStage.PLAYING && it.played > 0 }
            })
        } finally {
            sink.stop()
            host.close()
        }
        assertTrue("the speakers were left open", speakers.closed)
        assertEquals(SinkStage.IDLE, sink.status().stage)
    }

    /**
     * A host that stops leaves the socket quiet rather than closed as far as this end can tell,
     * so the sink says so from the chunks not arriving - the way the handset sink does.
     */
    @Test
    fun aHostThatStopsSendingIsSaidToHaveGoneQuiet() {
        val ports = HostPorts(chunk = freeTcpPort(), clock = freeUdpPort(), command = freeTcpPort())
        val host = hostPlaying(ports)
        val sink = SinkSession(folder.newFolder(), openSpeakers = FakeSpeakers()::open, silentAfterNanos = 500_000_000L)
        try {
            sink.start("127.0.0.1", ports.chunk, ports.clock)
            assertTrue(eventually(15_000) { sink.status().stage == SinkStage.PLAYING })
            host.stopPlaying()
            assertTrue("still says playing: ${sink.status()}", eventually(5_000) { sink.status().stage == SinkStage.HOST_SILENT })
        } finally {
            sink.stop()
            host.close()
        }
    }

    /** Nobody answering is said with the reason discovery gave, and the speakers are never opened. */
    @Test
    fun noHostAnsweringIsSaidWithoutOpeningTheSpeakers() {
        val sink = SinkSession(
            folder.newFolder(),
            openSpeakers = { throw AssertionError("opened the speakers for a room with no host") },
            discover = { DiscoveryOutcome(null, DiscoveryFailure.NOTHING_FOUND, 0, emptyList()) }
        )
        sink.start()
        assertTrue(eventually(5_000) { sink.status().stage == SinkStage.NOT_FOUND })
        assertEquals(DiscoveryFailure.NOTHING_FOUND, sink.status().failure)
    }

    /** A clock that never answers is said, nothing is played, and the speakers are given back. */
    @Test
    fun aHostWhoseClockNeverAnswersIsSaid() {
        val speakers = FakeSpeakers()
        val sink = SinkSession(folder.newFolder(), openSpeakers = speakers::open, clockWaitMillis = 1_000L)
        sink.start("127.0.0.1", freeTcpPort(), freeUdpPort())
        assertTrue(eventually(5_000) { sink.status().stage == SinkStage.NO_CLOCK })
        assertTrue(eventually(2_000) { speakers.closed })
        assertFalse(sink.status().played > 0)
    }
}
