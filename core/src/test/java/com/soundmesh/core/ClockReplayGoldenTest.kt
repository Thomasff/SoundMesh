package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins what this estimator answers on a fixed series, so an offline replay of a recorded run can be
 * checked against it rather than trusted.
 *
 * A candidate estimator design is now meant to be scored offline, on exchanges a real run recorded,
 * against the design that is shipped. That comparison is only worth anything if the replay computes
 * what this class computes - and a replay that is subtly wrong produces a ranking that looks
 * entirely normal. The same series and the same expected values live in tools/test/clock-replay.test.mjs.
 */
class ClockReplayGoldenTest {
    private val series = listOf(
        ClockExchange(1000000000L, 4007261232L, 4007561232L, 1011979747L),
        ClockExchange(1500000000L, 4507089868L, 4507389868L, 1517326917L),
        ClockExchange(2000000000L, 5007475692L, 5007775692L, 2015120679L),
        ClockExchange(2500000000L, 5503431584L, 5503731584L, 2512746961L),
        ClockExchange(3000000000L, 6007718191L, 6008018191L, 3016578467L),
        ClockExchange(3500000000L, 6507985355L, 6508285355L, 3512917289L),
        ClockExchange(4000000000L, 7008431169L, 7008731169L, 4012188850L),
        ClockExchange(4500000000L, 7506728973L, 7507028973L, 4510809069L),
        ClockExchange(5000000000L, 8003771587L, 8004071587L, 5012272175L),
        ClockExchange(5500000000L, 8509080451L, 8509380451L, 5518022351L),
        ClockExchange(6000000000L, 9003010709L, 9003310709L, 6008953809L),
        ClockExchange(6500000000L, 9507980665L, 9508280665L, 6513078772L),
        ClockExchange(7000000000L, 10004374538L, 10004674538L, 7009480582L),
        ClockExchange(7500000000L, 10509499795L, 10509799795L, 7518615912L),
        ClockExchange(8000000000L, 11005514152L, 11005814152L, 8011083396L),
        ClockExchange(8500000000L, 11508727666L, 11509027666L, 8512239478L)
    )

    private fun answers(estimator: ClockOffsetEstimator): List<String> =
        series.mapNotNull { exchange ->
            estimator.record(exchange)
            estimator.estimate(exchange.t4)?.let { "${it.offsetNanos}/${it.driftPpm}/${it.sampleCount}/${it.uncertaintyNanos}" }
        }

    @Test
    fun answersTheSameSeriesTheOfflineReplayIsPinnedTo() {
        // Sixteen exchanges never fill the shipped window, so this pins the filling path: the
        // best-of cut keeps a fraction of what is held rather than a frozen eight, which here is
        // one. Seven answers are missing because the window has not reached MIN_SAMPLES, and one
        // more because two exchanges tie and the pair is refused.
        assertEquals(
            listOf(
                "3001474438/0.0/1/5254534",
                "3001474438/0.0/1/5254534",
                "3001474438/0.0/1/5254534",
                "2998683804/0.0/1/4326904",
                "2998683804/0.0/1/4326904",
                "2998683804/0.0/1/4326904",
                "2998683804/0.0/1/4326904",
                "2998683804/0.0/1/4326904"
            ),
            answers(ClockOffsetEstimator())
        )
    }

    @Test
    fun answersTheSameSeriesTheOfflineReplayIsPinnedToOnceTheWindowIsFull() {
        // The same series through a window it does fill, so the eight point fit, the drift the
        // slope reports and the plausibility guard are all exercised - none of which the filling
        // path above reaches. Ten of the sixteen windows are refused as impossible drift.
        assertEquals(
            listOf(
                "2999856526/462.92228571428575/8/5254534",
                "3000061884/249.0694761904762/8/5254534",
                "2999889191/20.13233333333333/8/4326904",
                "3000437088/-270.70528571428576/8/4326904",
                "3000295890/-323.41478571428576/8/4326904",
                "3000000354/117.14769047619048/8/4326904"
            ),
            answers(ClockOffsetEstimator(windowSize = 8, bestCount = 8))
        )
    }

    /**
     * The rule section 26 replaced, pinned on the same series so the offline replay can score it.
     *
     * Not the case above: that one caps the window at eight as well, which is a third behaviour
     * neither rule has. Here the window is the shipped sixty-four and only the count is frozen,
     * so every exchange the filling window holds is kept - eight from the first fit onwards,
     * where the fraction keeps one. Hardware agreed on 2026-09-10 across eighteen runs.
     */
    @Test
    fun answersTheSameSeriesTheOfflineReplayIsPinnedToUnderTheRuleSection26Replaced() {
        assertEquals(
            listOf(8, 8, 8, 8, 8, 8, 8, 8),
            answers(ClockOffsetEstimator(keepFractionWhileFilling = false))
                .map { it.substringAfter("/").substringAfter("/").substringBefore("/").toInt() }
        )
        assertEquals(
            listOf(1, 1, 1, 1, 1, 1, 1, 1),
            answers(ClockOffsetEstimator())
                .map { it.substringAfter("/").substringAfter("/").substringBefore("/").toInt() }
        )
    }
}
