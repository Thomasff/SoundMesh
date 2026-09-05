package com.soundmesh.session

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read as text, because a HostSession cannot be built off a device: it binds two sockets and owns
 * a renderer that opens an AudioTrack. What is guarded here is one line whose defect was silent -
 * it produced audio, on both handsets, that simply clicked - and expensive to find, so it is worth
 * a test that at least says when it comes back. The behaviour itself is covered by ChunkTimeline.
 */
class HostSessionTest {
    private val source = File("src/main/java/com/soundmesh/session/HostSession.kt").readText()

    @Test
    fun aChunkIsDueWhenTheTimelineSaysRatherThanWhenItArrived() {
        assertTrue(source.contains("timeline.accept(System.nanoTime()) + LEAD_NANOS"))
        assertFalse(source.contains("AudioChunk(sequence, System.nanoTime() + LEAD_NANOS"))
    }

    @Test
    fun theProducerIsPacedByTheSameTimelineItStamps() {
        assertTrue(source.contains("timeline.nextDueNanos()"))
    }
}
