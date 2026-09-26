package com.soundmesh.probe.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The waiting half of streaming playback, which is the half that has no codec in it.
 *
 * Everything here reads as arithmetic and is anything but: each of these answers is given to a
 * thread that is holding up the room while it waits. The counter in particular has no other
 * witness - a decoder that stumbles and catches up changes no other number in a run, because the
 * host reads a chunk a second before it is heard and the scheduler holds three seconds more.
 */
class ChunkQueueTest {
    private val pollMillis = 5L
    private val starvedMillis = 50L

    private fun queue(capacity: Int = 4) = ChunkQueue(capacity, pollMillis, starvedMillis)

    private fun chunk(mark: Int) = byteArrayOf(mark.toByte())

    @Test
    fun aChunkComesBackToWhoeverTakesIt() {
        val queue = queue()
        queue.put(chunk(1))
        assertArrayEquals(chunk(1), queue.take())
    }

    @Test
    fun theyComeBackInTheOrderTheyWentIn() {
        val queue = queue()
        queue.put(chunk(1))
        queue.put(chunk(2))
        assertArrayEquals(chunk(1), queue.take())
        assertArrayEquals(chunk(2), queue.take())
    }

    /** The renderer's thread asks for the next chunk forever; this is how it is let go. */
    @Test
    fun anEmptyQueueThatHasStoppedAnswersNothingRatherThanWaiting() {
        val queue = queue()
        queue.stop()
        assertNull(queue.take())
    }

    /**
     * Stopping is not discarding. The session is over when the caller stops asking, and cutting
     * off audio that is already decoded and already spoken for would be a stop everyone hears.
     */
    @Test
    fun whatIsAlreadyQueuedIsStillHandedOverAfterAStop() {
        val queue = queue()
        queue.put(chunk(1))
        queue.stop()
        assertArrayEquals(chunk(1), queue.take())
        assertNull(queue.take())
    }

    /**
     * The decoder's exception has to come out of the consumer's thread, because that thread is
     * the only one the session is watching. Left on the decoder thread it would be a room that
     * plays out its lead and then goes quiet with the session still calling itself PLAYING.
     */
    @Test
    fun theDecodersFailureIsWhatTheConsumerGets() {
        val queue = queue()
        val boom = SourceUnusable("SOURCE_FILE_NO_AUDIO")
        queue.fail(boom)
        val thrown = assertThrows(SourceUnusable::class.java) { queue.take() }
        assertEquals(boom, thrown)
        assertEquals(boom, queue.failure())
    }

    @Test
    fun aQueueThatKeptUpCountsNothing() {
        val queue = queue()
        repeat(4) { queue.put(chunk(it)) }
        repeat(4) { queue.take() }
        assertEquals(0, queue.lateChunks())
    }

    /**
     * The first chunk of all is the session starting, not the decoder falling behind. A counter
     * that reads one on every healthy session is a counter nobody looks at twice.
     */
    @Test
    fun waitingForTheVeryFirstChunkIsNotCountedAgainstTheDecoder() {
        val queue = queue()
        val producer = feeding(queue, chunks = 1, everyMillis = 20)
        assertArrayEquals(chunk(0), queue.take())
        producer.join()
        assertEquals(0, queue.lateChunks())
    }

    /** Once per wait, not once per poll: a longer stall is not a worse-looking one. */
    @Test
    fun oneWaitIsOneLateChunkNoMatterHowManyPollsItTook() {
        val queue = queue()
        queue.put(chunk(0))
        queue.take()
        val producer = feeding(queue, chunks = 1, everyMillis = 30)
        queue.take()
        producer.join()
        assertEquals(1, queue.lateChunks())
    }

    /**
     * **The one this class was pulled out for.** The first version of this counted starvation as
     * `lateChunks * pollMillis`, so a session where the decoder was momentarily late often enough
     * - spread over an hour, each wait recovered from - would eventually throw as though the
     * decoder had died. Nothing else in a run would have shown it: the count was correct, the
     * audio was correct, and then the source gave up.
     */
    @Test
    fun manySeparateWaitsNeverAddUpToStarvation() {
        val queue = queue()
        val rounds = 15
        val producer = feeding(queue, chunks = rounds, everyMillis = 15)
        repeat(rounds) { assertArrayEquals(chunk(it), queue.take()) }
        producer.join()
        // Ten is where the old arithmetic gave up: starvedMillis / pollMillis.
        assertTrue("late chunks: ${queue.lateChunks()}", queue.lateChunks() > 10)
    }

    /**
     * A decoder that has produced nothing for this long is not going to. Left to run, the wait
     * would hold the host's producer thread here for the rest of the session.
     */
    @Test
    fun aDecoderThatWentQuietIsGivenUpOn() {
        val queue = queue()
        val began = System.nanoTime()
        assertThrows(IllegalStateException::class.java) { queue.take() }
        assertTrue(System.nanoTime() - began >= starvedMillis * 1_000_000L)
    }

    @Test
    fun theFirstChunkIsWaitedForAndFound() {
        val queue = queue()
        val producer = feeding(queue, chunks = 1, everyMillis = 20)
        assertTrue(queue.awaitFirst(starvedMillis))
        producer.join()
    }

    @Test
    fun theFirstChunkThatNeverArrivesIsAnsweredWithFalse() {
        assertFalse(queue().awaitFirst(withinMillis = 20))
    }

    /**
     * So the caller can say why. A file with no audio track and a file whose decoder hung are the
     * same silence from out here, and only one of them has a code the screen knows how to say.
     */
    @Test
    fun aFailureEndsTheWaitForTheFirstChunkEarly() {
        val queue = queue()
        val boom = SourceUnusable("SOURCE_FILE_NO_AUDIO")
        val producer = Thread {
            Thread.sleep(10)
            queue.fail(boom)
        }
        producer.start()
        val began = System.nanoTime()
        assertFalse(queue.awaitFirst(withinMillis = 1_000))
        assertTrue(System.nanoTime() - began < 500_000_000L)
        assertEquals(boom, queue.failure())
        producer.join()
    }

    /**
     * The memory guarantee, and the only reason a forty minute song costs what a three minute one
     * costs: the decoder is stopped by the queue itself rather than by any rate it computes.
     */
    @Test
    fun aFullQueueHoldsTheDecoderWhereItStands() {
        val queue = queue(capacity = 2)
        queue.put(chunk(1))
        queue.put(chunk(2))
        val handedOver = AtomicBoolean(false)
        val producer = Thread {
            queue.put(chunk(3))
            handedOver.set(true)
        }
        producer.start()
        Thread.sleep(30)
        assertFalse(handedOver.get())
        queue.take()
        producer.join(1_000)
        assertTrue(handedOver.get())
    }

    /**
     * A seek throws away three seconds of a song nobody is going to hear now.
     *
     * The queue is the deeper half of what is in flight - three seconds of decoded audio against
     * the host's own second of lead - so without this a listener dragging a slider would wait four
     * seconds to hear the new place, and hear the old one for all of it.
     */
    @Test
    fun discardingThrowsAwayWhatWasDecodedAhead() {
        val queue = queue()
        queue.put(chunk(1))
        queue.put(chunk(2))
        assertEquals(2, queue.depth)
        queue.discard()
        assertEquals(0, queue.depth)
    }

    /** Discarding is not stopping: what the decoder hands over next is played as usual. */
    @Test
    fun aDiscardedQueueStillTakesWhatComesNext() {
        val queue = queue()
        queue.put(chunk(1))
        queue.discard()
        queue.put(chunk(9))
        assertArrayEquals(chunk(9), queue.take())
    }

    /**
     * The depth is how far ahead of the listener the decoder is, which is how a playhead is worked
     * out: what has been decoded, less what is still waiting here.
     */
    @Test
    fun theDepthIsHowMuchIsWaiting() {
        val queue = queue()
        assertEquals(0, queue.depth)
        queue.put(chunk(1))
        queue.put(chunk(2))
        queue.take()
        assertEquals(1, queue.depth)
    }

    /** A thread that hands over [chunks] chunks, marked 0 upwards, one every [everyMillis]. */
    private fun feeding(queue: ChunkQueue, chunks: Int, everyMillis: Long): Thread {
        val thread = Thread {
            repeat(chunks) {
                Thread.sleep(everyMillis)
                queue.put(chunk(it))
            }
        }
        thread.start()
        return thread
    }
}
