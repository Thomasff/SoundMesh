package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The queue between whoever wants to say something and the socket it goes out on.
 *
 * All of it is about which thread does the writing. Android refuses a socket write made on the
 * thread that draws the screen - it throws rather than blocking - and every timed thing this
 * handset says was started from a screen or from a command that arrived on one.
 */
class ThingsToSayTest {
    /** The one that matters: the caller hands it over and somebody else does the writing. */
    @Test
    fun saysThingsOnAThreadOfItsOwn() {
        val said = ArrayBlockingQueue<Thread>(4)
        val line = ThingsToSay("test") { said.offer(Thread.currentThread()) }
        line.start()
        try {
            assertTrue(line.say("here"))
            assertNotEquals(Thread.currentThread(), said.poll(2, TimeUnit.SECONDS))
        } finally {
            line.close()
        }
    }

    @Test
    fun saysThemInTheOrderTheyWereAsked() {
        val said = ArrayBlockingQueue<String>(8)
        val line = ThingsToSay("test") { said.offer(it) }
        line.start()
        try {
            assertTrue(line.say("one"))
            assertTrue(line.say("two"))
            assertTrue(line.say("three"))
            assertEquals("one", said.poll(2, TimeUnit.SECONDS))
            assertEquals("two", said.poll(2, TimeUnit.SECONDS))
            assertEquals("three", said.poll(2, TimeUnit.SECONDS))
        } finally {
            line.close()
        }
    }

    /**
     * A write to a handset that walked out of the network does not fail, it waits - for minutes,
     * in the kernel. So the queue in front of it has to have an end, and saying so is the only
     * way the caller ever hears about a line that has stopped moving.
     */
    @Test
    fun refusesWhatThereIsNoRoomLeftFor() {
        val writing = CountDownLatch(1)
        val stuck = CountDownLatch(1)
        val line = ThingsToSay("test", room = 2) {
            writing.countDown()
            stuck.await(5, TimeUnit.SECONDS)
        }
        line.start()
        try {
            assertTrue(line.say("first"))
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            assertTrue(line.say("second"))
            assertTrue(line.say("third"))
            assertFalse(line.say("fourth"))
        } finally {
            stuck.countDown()
            line.close()
        }
    }

    @Test
    fun saysNothingOnceItIsClosed() {
        val said = ArrayBlockingQueue<String>(4)
        val line = ThingsToSay("test") { said.offer(it) }
        line.start()
        line.close()
        assertFalse(line.say("here"))
    }

    /** What was queued for a socket that has just died is not worth saying on the next one. */
    @Test
    fun forgetsWhatWasQueuedForALineThatDied() {
        val writing = CountDownLatch(1)
        val stuck = CountDownLatch(1)
        val said = ArrayBlockingQueue<String>(8)
        val line = ThingsToSay("test") {
            writing.countDown()
            stuck.await(5, TimeUnit.SECONDS)
            said.offer(it)
        }
        line.start()
        try {
            assertTrue(line.say("first"))
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            assertTrue(line.say("second"))
            line.forget()
            stuck.countDown()
            assertEquals("first", said.poll(2, TimeUnit.SECONDS))
            assertNull(said.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            line.close()
        }
    }
}
