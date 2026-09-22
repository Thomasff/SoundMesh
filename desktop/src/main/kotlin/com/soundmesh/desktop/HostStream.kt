package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.TonePcmSource
import com.soundmesh.probe.sync.ChunkServer

/**
 * The audio half of a desktop host: hands chunks to whatever sinks have connected, on the timeline
 * the tone is generated against.
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
    private val source: TonePcmSource = TonePcmSource(),
    private val leadNanos: Long = DEFAULT_LEAD_NANOS
) {
    private val chunkServer = ChunkServer(port)

    fun start() = chunkServer.start()

    /** Sinks currently being sent audio. The host has to wait for at least one or it plays to nobody. */
    fun sinkCount(): Int = chunkServer.clientCount()

    /** Chunks a sink stopped keeping up for. Zero on a healthy link. */
    fun droppedChunks(): Int = chunkServer.droppedChunks()

    /**
     * Streams [chunks] chunks and returns once the last one has been handed over.
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
     */
    fun stream(chunks: Int) {
        val anchor = System.nanoTime() + leadNanos
        var frameIndex = 0L
        for (sequence in 0 until chunks) {
            val playAt = anchor + sequence * CHUNK_NANOS
            chunkServer.broadcast(AudioChunk(sequence, playAt, source.fill(frameIndex, ChunkCodec.FRAMES_PER_CHUNK)))
            frameIndex += ChunkCodec.FRAMES_PER_CHUNK
            sleepUntil(playAt + CHUNK_NANOS - leadNanos)
        }
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
    }
}
