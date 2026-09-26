package com.soundmesh.desktop

/**
 * What a capture hands over, cut into the chunks the room is sent.
 *
 * The capture delivers packets on the sound card's clock and the stream asks for chunks on this
 * machine's, so the two never quite agree. [nextChunk] blocks until a whole chunk is there, which
 * is the handset's arrangement (CaptureChunkSource): the capture is the only pace a live source
 * has, and the stream's own sleep only takes up whatever the wait did not. A capture running
 * slow therefore eats into the stream's lead, and the stream starts again once too little is left
 * - see [HostStream].
 *
 * Two things the handset does not have to answer. A program with nothing playing delivers
 * nothing, and a stream blocked on it would fall silent and then behind: after [starvedNanos]
 * without a packet the chunk is made up with silence instead, at once for as long as nothing
 * comes. And a capture running fast would hold more and more, so beyond [maxBacklogBytes] the
 * oldest is thrown away - a delay nobody hears, since the program's own copy is kept off the
 * speakers, but one without an end.
 */
class CaptureFeed(
    private val chunkBytes: Int = HostStream.CHUNK_BYTES,
    private val starvedNanos: Long = STARVED_NANOS,
    private val maxBacklogBytes: Int = MAX_BACKLOG_BYTES,
    private val now: () -> Long = System::nanoTime
) {
    private val lock = Object()
    private val ring = ByteArray(maxBacklogBytes)
    private var start = 0
    private var size = 0
    private var lastPushNanos = now()
    private var closed = false

    /** Chunks that had to be made up, whole or in part, with silence. */
    var paddedChunks = 0
        private set

    /** Bytes thrown away because the capture had got too far ahead. */
    var droppedBytes = 0L
        private set

    /** What is held and not yet handed over, as time: how much older than the capture the stream runs. */
    fun backlogNanos(): Long = synchronized(lock) { size / FRAME_BYTES * 1_000_000_000L / SAMPLE_RATE }

    /** Called on the capture's thread with interleaved 16-bit frames. */
    fun push(bytes: ByteArray, length: Int) = synchronized(lock) {
        var from = 0
        var left = length
        // More than the whole ring at once: only its newest part can be kept.
        if (left > ring.size) {
            droppedBytes += left - ring.size
            from = left - ring.size
            left = ring.size
        }
        val over = size + left - ring.size
        if (over > 0) {
            // Whole frames, so left and right stay where they were.
            val drop = (over + FRAME_BYTES - 1) / FRAME_BYTES * FRAME_BYTES
            start = (start + drop) % ring.size
            size -= drop
            droppedBytes += drop
        }
        var at = (start + size) % ring.size
        while (left > 0) {
            val run = minOf(left, ring.size - at)
            System.arraycopy(bytes, from, ring, at, run)
            from += run
            left -= run
            at = (at + run) % ring.size
            size += run
        }
        lastPushNanos = now()
        lock.notifyAll()
    }

    /** One chunk: whole as soon as there is one, made up with silence once the capture has starved. */
    fun nextChunk(): ByteArray {
        synchronized(lock) {
            while (true) {
                if (size >= chunkBytes) return take(chunkBytes)
                val starvedFor = now() - lastPushNanos
                if (closed || starvedFor >= starvedNanos) {
                    paddedChunks++
                    return take(size - size % FRAME_BYTES)
                }
                val waitNanos = starvedNanos - starvedFor
                lock.wait(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
            }
        }
    }

    /** Wakes a stream waiting on a capture that has gone; everything after is silence. */
    fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }

    /** [count] bytes from the front, the rest of a chunk left as silence. Under [lock]. */
    private fun take(count: Int): ByteArray {
        val chunk = ByteArray(chunkBytes)
        var copied = 0
        while (copied < count) {
            val run = minOf(count - copied, ring.size - start)
            System.arraycopy(ring, start, chunk, copied, run)
            copied += run
            start = (start + run) % ring.size
            size -= run
        }
        return chunk
    }

    companion object {
        private const val FRAME_BYTES = 4
        private const val SAMPLE_RATE = 48_000L

        /**
         * Long enough to cover the gap between two packets - the engine delivers every ten
         * milliseconds - several times over, short enough that a stream waiting this long once is
         * nothing against its lead. Picked, not measured.
         */
        const val STARVED_NANOS = 50_000_000L

        /** Half a second of stereo 16-bit at 48 kHz. Picked, not measured. */
        const val MAX_BACKLOG_BYTES = 48_000 * FRAME_BYTES / 2
    }
}
