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

    @Test
    fun answersTheSameSeriesTheOfflineReplayIsPinnedTo() {
        val estimator = ClockOffsetEstimator()
        val answers = series.mapNotNull { exchange ->
            estimator.record(exchange)
            estimator.estimate(exchange.t4)?.let { "${it.offsetNanos}/${it.driftPpm}/${it.sampleCount}/${it.uncertaintyNanos}" }
        }

        // One of the nine windows is rejected by the drift plausibility guard, so eight answers
        // come back from sixteen exchanges - the rejection is part of what the replay must match.
        assertEquals(
            listOf(
                "3000212145/-57.95153383458646/8/5254534",
                "3000212145/-57.95153383458646/8/5254534",
                "3000100251/-249.96854634146342/8/4326904",
                "3000290991/-58.45035519125683/8/4326904",
                "3000065112/-226.5201791411043/8/4326904",
                "3000065112/-226.5201791411043/8/4326904",
                "2999870830/-96.44137162162163/8/4326904",
                "3000564558/-69.76448192771085/8/4326904"
            ),
            answers
        )
    }
}
