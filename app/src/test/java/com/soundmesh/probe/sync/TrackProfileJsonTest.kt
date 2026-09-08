package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The output path's own description of itself, which the alignment bias is now waiting on.
 *
 * Asserted on what the function produces rather than on how the renderer reads: a source-text
 * assertion here passed once against an unrelated line while the feature it named was not wired
 * up at all.
 */
class TrackProfileJsonTest {
    @Test
    fun carriesWhatTheFrameworkGrantedRatherThanWhatWasAskedFor() {
        val json = trackProfileJson(
            minBufferBytes = 3840,
            requestedBufferBytes = 7680,
            bufferSizeFrames = 2052,
            bufferCapacityFrames = 4104,
            performanceMode = 0,
            sampleRate = 48000,
            firstPendingFrames = 1917L
        )

        assertEquals(
            "{\"minBufferBytes\":3840,\"requestedBufferBytes\":7680," +
                "\"bufferSizeFrames\":2052,\"bufferCapacityFrames\":4104," +
                "\"performanceMode\":0,\"sampleRate\":48000," +
                "\"firstPendingFrames\":1917}",
            json
        )
    }

    /** A run whose timestamp path never answered has no first reading, and null is not zero. */
    @Test
    fun saysNothingRatherThanZeroWhenNoReadingEverCameBack() {
        val json = trackProfileJson(3840, 7680, 2052, 4104, 0, 48000, null)

        assertEquals(true, json.endsWith("\"firstPendingFrames\":null}"))
    }
}
