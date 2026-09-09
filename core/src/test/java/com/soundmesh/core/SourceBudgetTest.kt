package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBudgetTest {
    @Test
    fun anOrdinarySongFits() {
        assertFalse(SourceBudget.tooLong(minutes(3.5), 44_100, 2))
    }

    @Test
    fun theLimitItselfFits() {
        assertFalse(SourceBudget.tooLong(seconds(SourceBudget.maxSeconds(44_100, 2)), 44_100, 2))
    }

    @Test
    fun aSecondPastTheLimitDoesNot() {
        val limit = SourceBudget.maxSeconds(44_100, 2)
        assertFalse(SourceBudget.tooLong(seconds(limit), 44_100, 2))
        assertTrue(SourceBudget.tooLong(seconds(limit + 1), 44_100, 2))
    }

    /**
     * MediaFormat's duration is microseconds and nothing in the name says so. Read as
     * milliseconds the limit becomes seven seconds, which refuses everything; read as nanoseconds
     * it becomes five days, which refuses nothing. Both failures are silent.
     */
    @Test
    fun theDurationIsReadAsMicroseconds() {
        assertFalse(SourceBudget.tooLong(minutes(1.0), 44_100, 2))
        assertTrue(SourceBudget.tooLong(minutes(20.0), 44_100, 2))
    }

    /** Not every container declares one. An unknown length is not a refusal - it is a truncation. */
    @Test
    fun anUndeclaredLengthIsNotARefusal() {
        assertFalse(SourceBudget.tooLong(null, 44_100, 2))
        assertFalse(SourceBudget.tooLong(0L, 44_100, 2))
        assertFalse(SourceBudget.tooLong(-1L, 44_100, 2))
    }

    /**
     * The one length that was measured rather than modelled, and it must not move: six minutes of
     * 44.1 kHz stereo is what was watched peaking at 291 MB, and every other length here is that
     * measurement divided by a ratio of the same model.
     *
     * This one is an identity and says so: the budget is defined as this length times this
     * format's cost, so dividing it back out returns 360 whatever the cost model says. What it
     * defends is the anchor - that nobody quietly retunes 360, or moves the anchor onto a format
     * nothing was ever measured on. The model itself is defended by the ratios below.
     */
    @Test
    fun theMeasuredFormatKeepsTheLengthItWasMeasuredAt() {
        assertEquals(360, SourceBudget.maxSeconds(44_100, 2))
    }

    /**
     * The whole point of the change. A song already in the renderer's format is handed straight
     * through, so it never holds a converted second copy of itself and never unpacks a channel to
     * float, and it can be a good deal longer for the same peak.
     */
    @Test
    fun aSongThatNeedsNoConversionIsAllowedToBeLonger() {
        assertTrue(SourceBudget.maxSeconds(48_000, 2) > SourceBudget.maxSeconds(44_100, 2))
    }

    /**
     * Two numbers appear in the words shown to whoever was refused, and this is what keeps them
     * true. If either changes, the string in strings.xml has to change with it.
     */
    @Test
    fun theTwoLengthsTheRefusalNamesAreTheOnesThisComputes() {
        assertEquals(6, SourceBudget.maxSeconds(44_100, 2) / 60)
        assertEquals(11, SourceBudget.maxSeconds(48_000, 2) / 60)
    }

    /** More samples a second is more bytes a second, all the way up the allowed range. */
    @Test
    fun aHigherRateIsAllowedLess() {
        assertTrue(SourceBudget.maxSeconds(96_000, 2) < SourceBudget.maxSeconds(44_100, 2))
    }

    /**
     * Mono is the one place where fewer bytes in buys less time, and it is worth a case of its
     * own: half the samples, but sharing them across two channels writes a second array that
     * stereo at this rate never writes. Treating mono as "stereo, cheaper" would allow it 690
     * seconds against a real cost that only covers 552.
     */
    @Test
    fun monoAtTheRendererRateIsAllowedLessThanStereo() {
        assertTrue(SourceBudget.maxSeconds(48_000, 1) < SourceBudget.maxSeconds(48_000, 2))
    }

    /**
     * A surround mix is refused for its channels, by a message that says so. Pricing its six
     * channels honestly would refuse an ordinary four minute one as too long first, and the
     * person reading that would go looking for a shorter copy of a file whose length was never
     * the problem.
     */
    @Test
    fun aSurroundMixIsPricedAsStereoSoItsOwnRefusalGetsThere() {
        assertEquals(SourceBudget.maxSeconds(48_000, 2), SourceBudget.maxSeconds(48_000, 6))
    }

    /**
     * A container that declares no channel count is charged the dearer of the two shapes it could
     * be. At the renderer's rate that is mono, which a guess of "stereo, obviously" would have got
     * wrong by 138 seconds in the direction that runs the heap out.
     */
    @Test
    fun anUndeclaredChannelCountIsChargedTheDearerShape() {
        assertEquals(SourceBudget.maxSeconds(48_000, 1), SourceBudget.maxSeconds(48_000, 0))
        assertEquals(SourceBudget.maxSeconds(44_100, 2), SourceBudget.maxSeconds(44_100, 0))
    }

    private fun minutes(count: Double): Long = (count * 60_000_000L).toLong()

    private fun seconds(count: Int): Long = count * 1_000_000L
}
