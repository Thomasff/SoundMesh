package com.soundmesh.product

import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SplitAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What of the screen reaches the rule, and what does not.
 *
 * Three settings on the room screen are dropped on the way here in some modes and carried in
 * others, and every one of them is a place where the screen and the sound can quietly disagree -
 * a handset still showing "低音" while the rule it is playing has no split in it at all. None of
 * that could be checked while this lived inside an Activity, which is why it no longer does.
 */
class RoomRuleTest {
    private val a = "a1b2c3d4e5f60718"
    private val b = "0918273645abcdef"

    private fun room(): List<RoomIcon> = listOf(RoomIcon(a, 0.3f, 0.3f), RoomIcon(b, 0.7f, 0.3f))

    private fun stateOn(mode: SpatialMode): RoomState = RoomState(
        icons = room(),
        mode = mode,
        separation = 1f,
        splitAxis = SplitAxis.LOW_HIGH,
        otherHalfIds = setOf(b),
        retreat = 0.5f,
        reverb = 0.8f,
        periodSeconds = 11
    )

    /** Nothing to place a sound in until there is a drawing, and that is not an error. */
    @Test
    fun aRoomWithNoHandsetsInItHasNoRule() {
        assertNull(ruleOf(RoomState()))
    }

    /**
     * The split reaches the rule in the two modes that stand still and is dropped in the two that
     * move a source - while the screen goes on holding it either way.
     *
     * Both halves are asserted here rather than only the dropping. A rule that dropped it
     * everywhere would sound like a split that does nothing, and a state that lost it would cost
     * somebody the assignment they made by hand the moment they tried the rotation.
     */
    @Test
    fun theContentSplitReachesOnlyTheModesThatStandStill() {
        for (mode in listOf(SpatialMode.UNISON, SpatialMode.SPLIT)) {
            assertEquals(1.0, ruleOf(stateOn(mode))!!.separation, 0.0)
        }
        for (mode in listOf(SpatialMode.ROTATE, SpatialMode.PAN)) {
            assertEquals(0.0, ruleOf(stateOn(mode))!!.separation, 0.0)
            // Still on the screen. The rule is what forgets it, not the state.
            assertEquals(setOf(b), ruleOf(stateOn(mode))!!.otherHalfIds)
        }
    }

    /**
     * How far off the source is reaches the rule only where there is a dot to put it back with.
     *
     * Every other mode draws no source dot, so a room left quiet by one would be a room nothing on
     * screen could undo - see SpatialPanel's sourceSpotOf, which draws it under the same condition.
     */
    @Test
    fun howFarOffTheSourceIsReachesOnlyTheModeThatDrawsTheDot() {
        assertEquals(0.5, ruleOf(stateOn(SpatialMode.PAN))!!.retreat, 0.0)
        for (mode in listOf(SpatialMode.UNISON, SpatialMode.SPLIT, SpatialMode.ROTATE)) {
            assertEquals(0.0, ruleOf(stateOn(mode))!!.retreat, 0.0)
        }
    }

    /** The room itself reaches every mode, because a room is a room whatever is being played. */
    @Test
    fun theReverberationReachesEveryMode() {
        for (mode in SpatialMode.entries) {
            assertEquals(0.8, ruleOf(stateOn(mode))!!.reverb, 1e-6)
        }
    }

    /** And the speed the rotation is played at gets there as a time, in the unit the rule counts in. */
    @Test
    fun theSpinSpeedReachesTheRuleAsATime() {
        assertEquals(11_000_000_000L, ruleOf(stateOn(SpatialMode.ROTATE))!!.periodNanos)
    }

    /**
     * A period of nothing is floored rather than refused. The rule demands a positive one, and a
     * state that somehow held zero would throw out of a constructor rather than showing anybody
     * anything - which is the one outcome worse than a circuit that turns quickly.
     */
    @Test
    fun aPeriodOfNothingIsFlooredRatherThanThrown() {
        val stalled = stateOn(SpatialMode.ROTATE).copy(periodSeconds = 0)

        assertTrue(ruleOf(stalled)!!.periodNanos > 0L)
    }

    /** Compensation off sends no scale, which is exactly what an unmeasured room sends. */
    @Test
    fun switchingOffTheDelayCompensationSendsNoScaleAtAll() {
        val measured = stateOn(SpatialMode.SPLIT).copy(metresPerUnit = 1.4)

        assertEquals(1.4, ruleOf(measured)!!.metresPerUnit, 0.0)
        assertEquals(0.0, ruleOf(measured.copy(delayCompensation = false))!!.metresPerUnit, 0.0)
    }
}
