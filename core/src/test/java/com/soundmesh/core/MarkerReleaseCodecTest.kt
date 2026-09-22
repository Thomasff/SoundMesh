package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the handset actually did with each marker, crossing to the machine that reads it.
 *
 * [MarkerPlayCodec] carries the schedule - the instant a marker was aimed at. This carries the
 * other end of the same stride: the instant the chunk carrying it was handed to the output, and
 * what the conversion from schedule to output was made of at that moment. A sink converts every
 * chunk through a clock estimate that moves within a run (0.8-2.1 ms measured over four rounds),
 * so its schedule-to-emission is not one fixed map and cannot be read by fitting a line through
 * arrivals. With these, it is read directly instead of inferred.
 */
class MarkerReleaseCodecTest {
    @Test
    fun aRunThatReleasedNoMarkersEncodesToAnEmptyArray() {
        assertEquals("[]", MarkerReleaseCodec.encode(emptyList()))
    }

    @Test
    fun whatIsWrittenIsWhatIsRead() {
        val releases = listOf(
            MarkerRelease(
                sequence = 150,
                localNanos = 123_456_789_012L,
                offsetNanos = -34_611_000_000L,
                depthNanos = 41_666_666L,
                trimFrames = 12,
                filteredErrorFrames = -3
            ),
            MarkerRelease(
                sequence = 400,
                localNanos = 128_456_789_012L,
                offsetNanos = -34_610_100_000L,
                depthNanos = 40_000_000L,
                trimFrames = 0,
                filteredErrorFrames = 5
            )
        )

        assertEquals(releases, MarkerReleaseCodec.decode(MarkerReleaseCodec.encode(releases)))
    }

    @Test
    fun theArrayIsFoundAfterTheOtherArraysAWholeRunReportCarries() {
        // Where it really sits: inside the renderer's object, with chirpPlays and the estimate
        // history already written above it. A reader that took the report's first bracket would
        // be reading one of those.
        val release = MarkerRelease(200, 7L, 8L, 9L, 1, 2)
        val report = "{\"schemaVersion\":1,\"role\":\"SINK\",\"markerPlays\":[{\"index\":0}]," +
            "\"renderer\":{\"chirpPlays\":[{\"repeat\":0}],\"markerReleases\":" +
            MarkerReleaseCodec.encode(listOf(release)) + ",\"played\":3}}"

        assertEquals(listOf(release), MarkerReleaseCodec.decode(report))
    }

    @Test
    fun aReportWithoutTheFieldReadsAsNoReleases() {
        // Every round taken before this field existed is such a report, and the four rounds of
        // O21 are the ones this question is being asked about.
        val report = "{\"schemaVersion\":1,\"role\":\"SINK\",\"renderer\":{\"chirpPlays\":[{\"repeat\":0}]}}"

        assertTrue(MarkerReleaseCodec.decode(report).isEmpty())
    }

    @Test
    fun theOrderTheyWereReleasedInSurvives() {
        // The whole reading is a residual per marker against its own schedule, in the order the
        // run made them: a reordering would not fail, it would answer with a different drift.
        val releases = (0 until 5).map {
            MarkerRelease(100 + it * 250, 1_000L + it * 5_000L, -34_611_000_000L + it * 400L, 41_000_000L, it, -it)
        }

        assertEquals(releases, MarkerReleaseCodec.decode(MarkerReleaseCodec.encode(releases)))
    }

    @Test
    fun negativeOffsetsAndErrorsSurviveTheirSigns() {
        // The pairing correction this pair carries is about -34.6 ms and the drift loop's filtered
        // error swings both ways. A pattern that only matched digits would read the sign off and
        // report a clock 34.6 ms the other way, which is a plausible-looking number.
        val release = MarkerRelease(250, 5L, -34_611_000_000L, 40_000_000L, 0, -17)

        assertEquals(listOf(release), MarkerReleaseCodec.decode(MarkerReleaseCodec.encode(listOf(release))))
    }

    @Test
    fun thePatternHasNoBareClosingBraceInIt() {
        // Android's regex is ICU underneath and refuses a bare `}`; desktop Java accepts it. The
        // JVM tests here cannot tell the two apart, so what is asserted is the escaping itself -
        // MarkerPlayCodec learned this by throwing on the handset at the one moment a finished
        // round had nothing left to do but write its report.
        val pattern = MarkerReleaseCodec.entryPattern()

        assertTrue(pattern.isNotEmpty())
        assertTrue(Regex("(?<!\\\\)\\}").find(pattern) == null)
        assertTrue(Regex("(?<!\\\\)\\{").find(pattern) == null)
    }
}
