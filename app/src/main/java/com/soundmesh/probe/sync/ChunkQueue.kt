package com.soundmesh.probe.sync

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The handoff between a thread that decodes a song and the thread that plays it.
 *
 * This is the half of [StreamingChunkSource] that has no codec in it, which is the only reason it
 * is a class of its own: everything a decoder does can be looked at on a handset and nowhere
 * else, while everything here - how long to wait, when to give up, what counts as falling behind
 * - is arithmetic over a queue and can be looked at anywhere. The one bug this code has already
 * had was in that arithmetic.
 *
 * Being bounded is the memory guarantee. [put] blocks once the decoder is a full queue ahead, so
 * there is no rate to compute and no clock to read, and a forty minute song costs exactly what a
 * three minute one costs.
 */
internal class ChunkQueue(
    capacity: Int,
    private val pollMillis: Long,
    private val starvedMillis: Long
) {
    private val ready = ArrayBlockingQueue<ByteArray>(capacity)

    @Volatile private var stopping = false
    @Volatile private var failure: Throwable? = null
    @Volatile private var everDelivered = false
    @Volatile private var lateChunks = 0

    /** Hands a chunk over, waiting while the consumer catches up. */
    fun put(chunk: ByteArray) = ready.put(chunk)

    /**
     * One chunk, or null once this queue has stopped and emptied.
     *
     * Blocks while the decoder catches up, and throws whatever the decoder threw - that thread is
     * the only one the session is watching. A failure left on the decoder's own thread would be a
     * room that plays out its lead and then goes quiet, with the session still calling itself
     * PLAYING.
     */
    fun take(): ByteArray? {
        var waitedMillis = 0L
        while (true) {
            val chunk = ready.poll(pollMillis, TimeUnit.MILLISECONDS)
            if (chunk != null) {
                everDelivered = true
                return chunk
            }
            failure?.let { throw it }
            if (stopping) return null
            // Once per wait, not once per poll, and never for the first chunk of all: waiting for
            // that one is the session starting rather than the decoder falling behind, and a
            // counter that reads one on every healthy session is a counter nobody looks at twice.
            if (waitedMillis == 0L && everDelivered) lateChunks++
            waitedMillis += pollMillis
            // How long *this* wait has run, not how many waits there have been. Counting the
            // latter would make a session that stumbled often enough and recovered every time
            // give up as though the decoder had died.
            if (waitedMillis > starvedMillis) {
                throw IllegalStateException("the decoder has produced nothing for $starvedMillis ms")
            }
        }
    }

    /**
     * Waits for the very first chunk, and answers whether one arrived.
     *
     * A failure ends the wait early so the caller can say which failure it was: a file with no
     * audio track and a decoder that hung are the same silence from out here, and only one of
     * them has a code the screen knows how to say.
     */
    fun awaitFirst(withinMillis: Long): Boolean {
        val deadline = System.nanoTime() + withinMillis * 1_000_000L
        while (ready.isEmpty() && failure == null) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(FIRST_CHUNK_POLL_MILLIS)
        }
        return ready.isNotEmpty()
    }

    /**
     * Throws away everything decoded but not yet handed over.
     *
     * The deeper half of what a seek has to get rid of: three seconds here against the host's own
     * second of lead. Without it a listener dragging a slider would wait four seconds to
     * hear the new place and hear the old one throughout.
     */
    fun discard() = ready.clear()

    /**
     * How many chunks are waiting, which is how far ahead of the listener the decoder is.
     *
     * Read to work out a playhead: what the decoder has reached, less what is still sitting here,
     * is what the consumer is about to be handed.
     */
    val depth: Int get() = ready.size

    /** How many chunks the consumer had to wait for. Zero on a session that kept up. */
    fun lateChunks(): Int = lateChunks

    /** Why the decoder stopped, if it stopped for a reason. */
    fun failure(): Throwable? = failure

    fun fail(error: Throwable) {
        failure = error
    }

    /**
     * Stopping is not discarding: what is already decoded is still handed over. The session ends
     * when the consumer stops asking.
     */
    fun stop() {
        stopping = true
    }

    val stopped: Boolean get() = stopping

    private companion object {
        /**
         * Tighter than [pollMillis] because nothing is playing yet - this wait is the pause
         * between choosing a song and hearing it.
         */
        const val FIRST_CHUNK_POLL_MILLIS = 5L
    }
}
