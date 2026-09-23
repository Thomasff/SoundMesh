package com.soundmesh.desktop

import com.soundmesh.core.DiscoveryFailure
import com.soundmesh.core.DiscoveryOutcome
import com.soundmesh.core.RoomCommand
import com.soundmesh.probe.sync.HostIdentity
import com.soundmesh.probe.sync.RoomCommandServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SinkSessionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun ports() = HostPorts(chunk = freeTcpPort(), clock = freeUdpPort(), command = freeTcpPort())

    private fun hostOpen(ports: HostPorts): HostSession =
        HostSession(folder.newFolder(), ports, advertise = false, openSpeakers = FakeSpeakers()::open).apply { open() }

    private fun SinkSession.startOn(ports: HostPorts) = start("127.0.0.1", ports.chunk, ports.clock, ports.command)

    /**
     * Standing by is what puts this machine on the host's list: the handsets have always been
     * counted on the command port, and a sink that only dialled audio and clock was playing along
     * while the host's screen said nobody was there.
     */
    @Test
    fun aSinkStandsByOnTheHostsCommandPortUnderItsOwnName() {
        val ports = ports()
        val host = hostOpen(ports)
        val identity = folder.newFolder()
        val sink = SinkSession(identity, openSpeakers = { throw AssertionError("opened the speakers with nothing playing") }, called = "DESK")
        try {
            sink.startOn(ports)
            assertTrue("never stood by: ${sink.status()}", eventually(10_000) { sink.status().stage == SinkStage.STANDING_BY })
            assertTrue("not on the host's list: ${host.status().phones}", eventually(5_000) { host.status().phones == listOf("DESK") })
            assertEquals(0, sink.status().played)
        } finally {
            sink.stop()
            host.close()
        }
        assertTrue("still on the list after stopping", eventually(5_000) { host.status().phones.isEmpty() })
        // The name on the list is the one the audio leg dials under, so a host can tell the two apart.
        assertTrue(HostIdentity(identity).current().isNotEmpty())
    }

    /** Play, stop, play again - and nobody touches this machine in between. */
    @Test
    fun aStandingSinkFollowsEveryPlayAndStandsByAgainAfterEveryStop() {
        val ports = ports()
        val host = hostOpen(ports)
        val speakers = FakeSpeakers()
        val sink = SinkSession(folder.newFolder(), openSpeakers = speakers::open)
        try {
            sink.startOn(ports)
            assertTrue(eventually(10_000) { sink.status().stage == SinkStage.STANDING_BY })
            assertTrue(eventually(5_000) { host.status().phones.size == 1 })

            host.play(writeTestWav(folder.newFile()), alsoHere = false)
            assertTrue("never played: ${sink.status()}", eventually(15_000) {
                sink.status().let { it.stage == SinkStage.PLAYING && it.played > 0 }
            })

            host.stopPlaying()
            assertTrue("did not stand by again: ${sink.status()}", eventually(5_000) { sink.status().stage == SinkStage.STANDING_BY })
            assertTrue("the speakers were left open between songs", speakers.closed)

            host.play(writeTestWav(folder.newFile()), alsoHere = false)
            assertTrue("did not follow the second play: ${sink.status()}", eventually(15_000) {
                sink.status().let { it.stage == SinkStage.PLAYING && it.played > 0 }
            })
        } finally {
            sink.stop()
            host.close()
        }
        assertEquals(SinkStage.IDLE, sink.status().stage)
    }

    /** A host already playing says "play" to whoever stands by late, so arriving mid-song works. */
    @Test
    fun aSinkThatArrivesMidSongJoinsIt() {
        val ports = ports()
        val host = hostOpen(ports)
        host.play(writeTestWav(folder.newFile()), alsoHere = false)
        val sink = SinkSession(folder.newFolder(), openSpeakers = FakeSpeakers()::open)
        try {
            sink.startOn(ports)
            assertTrue("never played: ${sink.status()}", eventually(15_000) {
                sink.status().let { it.stage == SinkStage.PLAYING && it.played > 0 }
            })
        } finally {
            sink.stop()
            host.close()
        }
    }

    /**
     * Chunks that stop with no "stop" said - a host that crashed, a link that went - are said as
     * the host having gone quiet, and a later "play" is obeyed rather than ignored as a repeat.
     *
     * The order comes from a bare command server and the audio from a host on other ports, so the
     * host's own "stop" never reaches this sink: that is the only way to take the chunks away
     * without saying anything.
     */
    @Test
    fun chunksThatStopUnannouncedAreSaidAndTheNextPlayIsObeyed() {
        val audio = ports()
        val host = hostOpen(audio)
        host.play(writeTestWav(folder.newFile()), alsoHere = false)
        val commandPort = freeTcpPort()
        val orders = RoomCommandServer(commandPort).apply { start() }
        val sink = SinkSession(folder.newFolder(), openSpeakers = FakeSpeakers()::open, silentAfterNanos = 500_000_000L)
        try {
            sink.start("127.0.0.1", audio.chunk, audio.clock, commandPort)
            assertTrue(eventually(10_000) { orders.standingBy() == 1 })
            orders.send(RoomCommand.PLAY)
            assertTrue(eventually(15_000) { sink.status().stage == SinkStage.PLAYING })

            // Closed rather than stopped: a handset host ends its audio socket with every stop, so
            // the sink's audio leg is gone and only starting it again brings the sound back.
            host.close()
            assertTrue("still says playing: ${sink.status()}", eventually(5_000) { sink.status().stage == SinkStage.HOST_SILENT })

            val again = hostOpen(audio)
            try {
                again.play(writeTestWav(folder.newFile()), alsoHere = false)
                orders.send(RoomCommand.PLAY)
                assertTrue("the next play was not followed: ${sink.status()}", eventually(15_000) {
                    sink.status().let { it.stage == SinkStage.PLAYING && it.played > 0 }
                })
            } finally {
                again.close()
            }
        } finally {
            sink.stop()
            orders.stop()
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

    /**
     * Told to play by a host whose clock never answers: nothing is played, the speakers are given
     * back, and this machine goes back to standing by with the reason kept for the screen.
     */
    @Test
    fun aPlayWhoseClockNeverAnswersIsSaidAndStandsByAgain() {
        val speakers = FakeSpeakers()
        val commandPort = freeTcpPort()
        val orders = RoomCommandServer(commandPort).apply { start() }
        val sink = SinkSession(folder.newFolder(), openSpeakers = speakers::open, clockWaitMillis = 1_000L)
        try {
            sink.start("127.0.0.1", freeTcpPort(), freeUdpPort(), commandPort)
            assertTrue(eventually(10_000) { orders.standingBy() == 1 })
            orders.send(RoomCommand.PLAY)
            assertTrue("never said the clock: ${sink.status()}", eventually(5_000) {
                sink.status().let { it.stage == SinkStage.STANDING_BY && it.problem != null }
            })
            assertTrue(eventually(2_000) { speakers.closed })
            assertFalse(sink.status().played > 0)
        } finally {
            sink.stop()
            orders.stop()
        }
    }

    /** A host that is not there yet is dialled again rather than given up on, and said meanwhile. */
    @Test
    fun aCommandPortNobodyHoldsIsSaidAndKeptTrying() {
        val sink = SinkSession(folder.newFolder(), openSpeakers = FakeSpeakers()::open)
        val port = freeTcpPort()
        try {
            sink.start("127.0.0.1", freeTcpPort(), freeUdpPort(), port)
            assertTrue(eventually(5_000) { sink.status().stage == SinkStage.REACHING })
            val orders = RoomCommandServer(port).apply { start() }
            try {
                assertTrue("never reached it once it was there: ${sink.status()}", eventually(10_000) {
                    sink.status().stage == SinkStage.STANDING_BY
                })
            } finally {
                orders.stop()
            }
        } finally {
            sink.stop()
        }
    }
}
