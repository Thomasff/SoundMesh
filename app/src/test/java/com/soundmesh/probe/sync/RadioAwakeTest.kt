package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The poking discipline: that it happens for as long as the run does and not one moment longer,
 * and that what it found is said out loud. A poke that quietly reaches nobody leaves the run
 * looking exactly like one with no poking in it - which is how 2026-09-13's first attempt spent a
 * whole install cycle proving nothing.
 */
class RadioAwakeTest {
    private val quickly = 20L

    @Test
    fun theRadioIsPokedWhileTheRunIsGoing() {
        val poked = CountDownLatch(3)

        val answer = keepingAwake({ poked.countDown(); true }, quickly) {
            assertTrue("the radio was not poked while the run was going", poked.await(3, TimeUnit.SECONDS))
            "answer"
        }

        assertEquals("answer", answer)
    }

    @Test
    fun thePokingStopsWhenTheRunEnds() {
        val pokes = AtomicInteger()
        val started = CountDownLatch(1)

        keepingAwake({ pokes.incrementAndGet(); started.countDown(); true }, quickly) {
            started.await(3, TimeUnit.SECONDS)
        }

        Thread.sleep(quickly * 5)
        val settled = pokes.get()
        Thread.sleep(quickly * 5)
        assertEquals("kept poking after the run ended", settled, pokes.get())
    }

    /** A run that failed still has to give the radio back, same as one that returned. */
    @Test
    fun thePokingStopsWhenTheRunThrows() {
        val pokes = AtomicInteger()
        val started = CountDownLatch(1)

        val thrown = runCatching {
            keepingAwake({ pokes.incrementAndGet(); started.countDown(); true }, quickly) {
                started.await(3, TimeUnit.SECONDS)
                throw IllegalStateException("the run failed")
            }
        }

        assertTrue(thrown.isFailure)
        Thread.sleep(quickly * 5)
        val settled = pokes.get()
        Thread.sleep(quickly * 5)
        assertEquals("kept poking after the run threw", settled, pokes.get())
    }

    /**
     * A handset that will not say who its router is still calibrates. The poke is what makes the
     * measurement trustworthy, not what makes it possible.
     */
    @Test
    fun withNothingToPokeTheRunStillHappens() {
        assertEquals("answer", keepingAwake(null, quickly) { "answer" })
    }

    /**
     * A router that stops answering must not end the poking: the next poke is the one that might
     * land, and the run has minutes left to go.
     */
    @Test
    fun aPokeThatFailsDoesNotStopTheOnesAfterIt() {
        val poked = CountDownLatch(3)

        keepingAwake({ poked.countDown(); throw java.io.IOException("the router did not answer") }, quickly) {
            assertTrue("one failed poke ended the poking", poked.await(3, TimeUnit.SECONDS))
        }
    }

    /**
     * Silence is the failure mode this whole file exists to prevent: a poke that reaches nobody
     * measures exactly like no poke at all, and the run reads as evidence against power save when
     * the arm under test never ran.
     */
    @Test
    fun aRouterThatAnsweredIsSaidSoOutLoud() {
        val said = ArrayList<Boolean?>()
        val told = CountDownLatch(1)

        keepingAwake({ true }, quickly, answered = { said.add(it); told.countDown() }) {
            told.await(3, TimeUnit.SECONDS)
        }

        assertEquals(listOf<Boolean?>(true), said)
    }

    @Test
    fun aRouterThatIgnoredUsIsSaidSoOutLoud() {
        val said = ArrayList<Boolean?>()
        val told = CountDownLatch(1)

        keepingAwake({ false }, quickly, answered = { said.add(it); told.countDown() }) {
            told.await(3, TimeUnit.SECONDS)
        }

        assertEquals(listOf<Boolean?>(false), said)
    }

    /** Nothing to poke is its own answer, and a different one from a router that stayed quiet. */
    @Test
    fun havingNobodyToPokeIsSaidSoOutLoud() {
        val said = ArrayList<Boolean?>()

        keepingAwake(null, quickly, answered = { said.add(it) }) { "answer" }

        assertEquals(1, said.size)
        assertNull("nothing to poke was reported as a router that stayed quiet", said[0])
    }

    /** Said once. A line per poke would be five hundred lines of the same sentence. */
    @Test
    fun whatWasFoundIsSaidOnce() {
        val said = AtomicInteger()
        val poked = CountDownLatch(4)

        keepingAwake({ poked.countDown(); true }, quickly, answered = { said.incrementAndGet() }) {
            poked.await(3, TimeUnit.SECONDS)
        }

        assertEquals(1, said.get())
    }
}
