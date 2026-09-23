package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.TonePcmSource
import com.soundmesh.probe.sync.ChunkServer

/**
 * The audio half of a desktop host: hands chunks to whatever sinks have connected, on the timeline
 * its source is read against.
 *
 * Nothing here plays anything. This machine has a renderer - [WasapiRenderer] - and will want to be
 * one of the things playing, but a host that streams and a host that also plays are two claims, and
 * only the first one has to be true for a handset across the room to be playing what this machine
 * sent it. That is what this is for.
 *
 * The clock leg is not in here either. It is a UDP server that answers whenever it is asked, with
 * no relationship to the audio timeline, and wiring the two together would only make each of them
 * harder to look at on its own. [Host] holds both.
 */
class HostStream(
    private val port: Int = ChunkCodec.DEFAULT_PORT,
    /**
     * Frames by absolute index: a tone, or a file through [FilePcmSource].
     *
     * A function rather than a type because that is the whole of what the two sources have in
     * common. Neither of them holds a position and neither knows this class exists, and an
     * interface here would only be a name for an argument list.
     */
    private val source: (Long, Int) -> ByteArray = TonePcmSource()::fill,
    private val leadNanos: Long = DEFAULT_LEAD_NANOS,
    /**
     * This machine's own speakers, or null to send without playing.
     *
     * The offset is fixed at zero and that is not a simplification: a host's clock IS the host
     * clock the instants are written on, so there is nothing to estimate. Which makes this the
     * one participant in the whole system with no clock term in its own playout - useful later,
     * because it means a residual measured here has network and estimator taken out of it.
     *
     * What it does not do is put this machine in step with a handset. Both ends convert an
     * instant to a frame and neither knows how far its own speaker sits behind that frame; the
     * two constants are unmeasured and they do not cancel.
     */
    private val localOutput: FrameOutput? = null,
    /**
     * The server the chunks go out on. Its own by default; one passed in stays the caller's to
     * start and stop, so that one bound port can outlive many streams.
     *
     * That is what [HostSession] needs it for. A host in the window stops and plays again far
     * more often than it stops being the host, and reopening a port a thread is still blocked in
     * accept on is the step that can fail - the command channel measured one stop in two hundred
     * leaving the port in LISTEN on Linux, and the chunk server has no guard against it. A port
     * that is never closed between two plays cannot be caught out that way.
     */
    private val chunkServer: ChunkServer = ChunkServer(port),
    /** What this machine does to a chunk before playing it itself: its own part of the room. */
    private val localShape: (AudioChunk) -> AudioChunk = { it }
) {
    private val localPlayout = localOutput?.let { ChunkPlayout(it) { 0L } }

    fun start() = chunkServer.start()

    /** Sinks currently being sent audio. The host has to wait for at least one or it plays to nobody. */
    fun sinkCount(): Int = chunkServer.clientCount()

    /** Chunks a sink stopped keeping up for. Zero on a healthy link. */
    fun droppedChunks(): Int = chunkServer.droppedChunks()

    /** What this machine played of what it sent, and how the two counts differ. */
    fun playedLocally(): Int = localPlayout?.played ?: 0
    fun lateLocally(): Int = localPlayout?.droppedLate ?: 0
    fun localSeams(): Int = localPlayout?.seams ?: 0
    fun worstLocalSeamFrames(): Int = localPlayout?.worstSeamFrames ?: 0
    fun localSeamBand(): String = localPlayout?.seamBand() ?: "not playing locally"
    fun localSeamShares(): String = localPlayout?.seamShares() ?: "not playing locally"

    @Volatile private var jumpWanted = false

    /** Times the timeline started again, asked for or because the source fell behind it. */
    @Volatile var jumps = 0
        private set

    /**
     * Starts the timeline again a lead from now, sequence from zero, on the next chunk - a new
     * song, a seek, a pause. Every sink throws away what it had queued when it sees the sequence
     * go back (ChunkPlayout.play), as a handset does, so the room hears the new place a lead
     * later rather than a lead of the old one first.
     *
     * The stream also does it by itself when the source took so long - a song being decoded -
     * that the next chunk would reach the sinks too late to be played: a gap of a lead, where
     * carrying on would be a run of chunks every sink throws away as late.
     */
    fun jump() {
        jumpWanted = true
    }

    /** Streams [chunks] chunks and returns once the last one has been handed over - see [streamWhile]. */
    fun stream(chunks: Int) {
        var sent = 0
        streamWhile { sent++ < chunks }
    }

    /**
     * Streams until [keepGoing] answers false, which it is asked before every chunk.
     *
     * Every instant comes off one anchor rather than off the clock each time round the loop. The
     * handset does it the other way and gets away with it, drifting a millisecond or two per
     * chunk; here the loop is paced by a sleep, and Windows sleeps in steps of 15.625 ms against
     * a chunk of 20, so a per-chunk reading of the clock would hand the sink a timeline that
     * jittered by most of a chunk. Nothing else about such a run would look wrong - every chunk
     * still arrives, in order, at the right average rate - which is exactly why the anchor is
     * pinned by a test rather than left to be noticed.
     *
     * The same 15.625 ms is why the pacing sleeps to an absolute instant instead of for a chunk's
     * worth of time: waking late is unavoidable and costs nothing at a lead of a second and a
     * half, but waking late repeatedly and adding up would walk the stream off its own timeline.
     *
     * Returns the instant the last chunk was stamped to play at, or null if none was sent - so a
     * caller that ran out of song can wait until it has been heard.
     */
    fun streamWhile(keepGoing: () -> Boolean): Long? {
        var anchor = System.nanoTime() + leadNanos
        var frameIndex = 0L
        var sequence = 0
        var lastPlayAt: Long? = null
        while (keepGoing()) {
            val pcm = source(frameIndex, ChunkCodec.FRAMES_PER_CHUNK)
            // After the read, so the jump lands on the first chunk read from the new place; the
            // one a read already had in hand slips through as twenty milliseconds of the old one,
            // as it does on the handset.
            // A third of the lead left: room for the network and a sink's scheduling. Picked, not measured.
            val late = anchor + sequence * CHUNK_NANOS - System.nanoTime() < leadNanos / 3
            if (jumpWanted || (late && sequence > 0)) {
                jumpWanted = false
                anchor = System.nanoTime() + leadNanos
                sequence = 0
                jumps++
            }
            val playAt = anchor + sequence * CHUNK_NANOS
            val chunk = AudioChunk(sequence, playAt, pcm)
            chunkServer.broadcast(chunk)
            // After the broadcast, so a slow local output cannot hold up the wire. The sinks are
            // across a room and this one is in the same process; whichever of them is behind, the
            // instant in the chunk is already fixed and neither is waiting on the other for it.
            localPlayout?.play(localShape(chunk))
            lastPlayAt = playAt
            frameIndex += ChunkCodec.FRAMES_PER_CHUNK
            sequence++
            sleepUntil(playAt + CHUNK_NANOS - leadNanos)
        }
        return lastPlayAt
    }

    fun stop() = chunkServer.stop()

    private fun sleepUntil(instantNanos: Long) {
        val remaining = instantNanos - System.nanoTime()
        if (remaining > 0) Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
    }

    companion object {
        /** One chunk's worth of time, from the rate the tone is generated at. */
        const val CHUNK_NANOS = ChunkCodec.FRAMES_PER_CHUNK * 1_000_000_000L / TonePcmSource.SAMPLE_RATE

        /**
         * How far ahead of the wire a chunk's instant is set, matching what the handset host sends.
         *
         * It is what a sink has to absorb the network, its own clock estimate and its buffer in, so
         * the two hosts having different ones would make a sink behave differently depending on
         * which machine it joined - the one difference nobody would look for.
         */
        const val DEFAULT_LEAD_NANOS = 1_500_000_000L

        /** One chunk of 16-bit stereo, which is what silence is sent as. */
        const val CHUNK_BYTES = ChunkCodec.FRAMES_PER_CHUNK * 4
    }
}
