package com.soundmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marker schedule crossing from the handset that wrote it to the machine that reads it.
 *
 * Both sides are here because the field names are the whole contract: the handset is the only
 * witness of the instant it aimed a marker at, and a reader that looks for a name the writer
 * stopped using finds nothing and says so as "no markers in this run".
 */
class MarkerPlayCodecTest {
    @Test
    fun aRunWithNoMarkersEncodesToAnEmptyArray() {
        assertEquals("[]", MarkerPlayCodec.encode(emptyList()))
    }

    @Test
    fun whatIsWrittenIsWhatIsRead() {
        val plays = listOf(
            MarkerPlay(index = 0, sequence = 100, playAtHostNanos = 123_456_789_012L),
            MarkerPlay(index = 1, sequence = 350, playAtHostNanos = 128_456_789_012L)
        )

        assertEquals(plays, MarkerPlayCodec.decode(MarkerPlayCodec.encode(plays)))
    }

    @Test
    fun theArrayIsFoundInsideAWholeRunReport() {
        // What the reader is actually handed: the run's whole sync.json, with the array in the
        // middle of it and more fields on either side.
        val report = "{\"schemaVersion\":1,\"role\":\"HOST\",\"markerPlays\":" +
            MarkerPlayCodec.encode(listOf(MarkerPlay(2, 600, 7L))) +
            ",\"hostChirpAtHostNanos\":99,\"renderer\":{\"played\":3}}"

        assertEquals(listOf(MarkerPlay(2, 600, 7L)), MarkerPlayCodec.decode(report))
    }

    @Test
    fun aReportWithoutTheFieldReadsAsNoMarkers() {
        // Distinguishable from a run that had markers only if this is empty rather than a guess:
        // every run before this field existed is such a report.
        assertTrue(MarkerPlayCodec.decode("{\"schemaVersion\":1,\"role\":\"HOST\"}").isEmpty())
    }

    @Test
    fun theOrderTheyWerePlayedInSurvives() {
        // The reader fits a line through these against their arrival, so a reordering would not
        // fail loudly - it would answer with a slope.
        val plays = (0 until 5).map { MarkerPlay(it, 100 + it * 250, 1_000L + it * 5_000L) }

        assertEquals(plays, MarkerPlayCodec.decode(MarkerPlayCodec.encode(plays)))
    }
}
