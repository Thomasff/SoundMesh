package com.soundmesh.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release discipline and the reporting, which are the two things about a wake lock that can be
 * wrong without anything looking wrong: a leaked one shows up as a flat battery days later, and a
 * refused one shows up as a quiet night that reads like evidence.
 */
class CpuAwakeTest {
    private class Fake(
        val failsToAcquire: Boolean = false,
        val failsToRelease: Boolean = false
    ) : CpuHold {
        var acquired = 0
        var released = 0
        override fun acquire() {
            if (failsToAcquire) throw SecurityException("no WAKE_LOCK")
            acquired++
        }
        override fun release() {
            released++
            if (failsToRelease) throw IllegalStateException("under-locked")
        }
    }

    @Test
    fun theHoldIsTakenAndGivenBack() {
        val hold = Fake()
        val awake = CpuAwake(hold)

        assertTrue(awake.take())
        assertEquals(1, hold.acquired)
        awake.give()
        assertEquals(1, hold.released)
    }

    /**
     * The session service can be handed the same start intent twice - the system redelivers, and
     * the caller guards on ACTIVE rather than on this. Two acquires against one release is a
     * handset that never sleeps again until the process dies.
     */
    @Test
    fun takingItTwiceTakesOneLock() {
        val hold = Fake()
        val awake = CpuAwake(hold)

        awake.take()
        awake.take()
        awake.give()

        assertEquals("took the lock twice", 1, hold.acquired)
        assertEquals(1, hold.released)
    }

    /** A stop that arrives without a start must not release a lock nobody is holding. */
    @Test
    fun givingBackWhatWasNeverTakenDoesNothing() {
        val hold = Fake()

        CpuAwake(hold).give()

        assertEquals(0, hold.released)
    }

    @Test
    fun givingItBackTwiceReleasesOnce() {
        val hold = Fake()
        val awake = CpuAwake(hold)

        awake.take()
        awake.give()
        awake.give()

        assertEquals(1, hold.released)
    }

    /**
     * acquire needs WAKE_LOCK and is best effort, on the same terms as the radio hold. A refused
     * lock must be said out loud: otherwise a night with no dropout reads as "sleep was never the
     * problem" when the fix was never actually in force.
     */
    @Test
    fun aLockThatCouldNotBeTakenSaysSoAndTheSessionStillRuns() {
        val hold = Fake(failsToAcquire = true)
        var reported: Boolean? = null

        val awake = CpuAwake(hold)

        assertFalse(awake.take { reported = it })
        assertFalse("a lock that threw was reported as taken", reported!!)
        awake.give()
        assertEquals("released a lock it never took", 0, hold.released)
    }

    /** A handset with no power service at all still plays, and still says it is not being held. */
    @Test
    fun withNoLockAtAllTheSessionStillRunsAndSaysItWasNotHeld() {
        var reported: Boolean? = null

        val awake = CpuAwake(null)

        assertFalse(awake.take { reported = it })
        assertFalse(reported!!)
        awake.give()
    }

    /**
     * Releasing throws on a lock the system has already dropped underneath us. Stopping a session
     * is the last thing that happens on that thread and there is nothing left to catch it.
     */
    @Test
    fun aReleaseThatThrowsDoesNotTakeTheSessionDown() {
        val hold = Fake(failsToRelease = true)
        val awake = CpuAwake(hold)
        awake.take()

        awake.give()

        assertEquals(1, hold.released)
    }

    /** Told once per take, so a session that is restarted reports its own answer rather than the last one's. */
    @Test
    fun takingItAgainAfterGivingItBackReportsAgain() {
        val hold = Fake()
        val awake = CpuAwake(hold)
        val said = mutableListOf<Boolean>()

        awake.take { said.add(it) }
        awake.give()
        awake.take { said.add(it) }
        awake.give()

        assertEquals(listOf(true, true), said)
        assertEquals(2, hold.acquired)
        assertEquals(2, hold.released)
    }
}
