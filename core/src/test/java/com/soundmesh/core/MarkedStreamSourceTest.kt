package com.soundmesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkedStreamSourceTest {
    private val framesPerChunk = 960

    private fun source(
        strideChunks: Int = 250,
        firstMarkerChunk: Int = 100
    ) = MarkedStreamSource(strideChunks, firstMarkerChunk, framesPerChunk)

    @Test
    fun ordinaryChunksAreTheToneUntouched() {
        val marked = source()
        val tone = TonePcmSource()

        // A marker is six chunks long, so 100..105 and 350..355 are the sweep, not the tone.
        for (sequence in listOf(0, 1, 99, 106, 200, 349, 356)) {
            assertArrayEquals(
                "sequence $sequence should be plain tone",
                tone.fill(sequence.toLong() * framesPerChunk, framesPerChunk),
                marked.chunkAt(sequence)
            )
            assertNull("sequence $sequence should not be a marker", marked.markerAt(sequence))
        }
    }

    @Test
    fun aMarkerIsTheSameSweepTheChirpPathSubmits() {
        val marked = source()
        val sweep = ChirpGenerator.generateStereoChunks(framesPerChunk)

        // Byte for byte, not merely "a chirp": the point of the experiment is that the sweep is
        // held fixed while the path it travels on changes, so any difference here would make the
        // streamed reading incomparable with every chirp reading in the archive.
        sweep.forEachIndexed { within, expected ->
            assertArrayEquals(
                "marker chunk $within",
                expected,
                marked.chunkAt(100 + within)
            )
            assertEquals(MarkedStreamSource.Marker(0, within), marked.markerAt(100 + within))
        }
    }

    @Test
    fun theSweepIsNotSilentSoTheIdentityCheckCanFail() {
        // Without this, assertArrayEquals above would also pass on an all-zero payload, and a
        // source that emitted silence on the marker sequences would look correct.
        val marked = source()
        val silence = ByteArray(framesPerChunk * ChirpGenerator.CHANNELS * 2)

        assertNotEquals(silence.toList(), marked.chunkAt(100).toList())
    }

    @Test
    fun theFirstMarkerWaitsOutTheSettlingRegion() {
        // The archive's jumps all sat in the first 1.27 s of a run. A marker released in there
        // would measure the acquisition transient and be reported as a placement error.
        val marked = source(firstMarkerChunk = 100)

        assertNull(marked.markerAt(99))
        assertEquals(MarkedStreamSource.Marker(0, 0), marked.markerAt(100))
    }

    @Test
    fun laterMarkersLandOnTheStride() {
        val marked = source(strideChunks = 250, firstMarkerChunk = 100)

        assertEquals(MarkedStreamSource.Marker(1, 0), marked.markerAt(350))
        assertEquals(MarkedStreamSource.Marker(1, 5), marked.markerAt(355))
        assertNull(marked.markerAt(356))
        assertEquals(MarkedStreamSource.Marker(4, 0), marked.markerAt(1100))
    }

    @Test
    fun theSweepCarriesTheWholeMarkerAndNothingAfterIt() {
        val marked = source()
        val sweepChunks = ChirpGenerator.generateStereoChunks(framesPerChunk).size

        assertEquals(sweepChunks, marked.markerChunkCount)
        assertEquals(MarkedStreamSource.Marker(0, sweepChunks - 1), marked.markerAt(100 + sweepChunks - 1))
        assertNull(marked.markerAt(100 + sweepChunks))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aStrideThatWouldRunTwoMarkersTogetherIsRefused() {
        // Adjacent markers would leave the correlator two sweeps with no silence between them,
        // which is the failure that made a global maximum pick the wrong repeat once already.
        MarkedStreamSource(ChirpGenerator.generateStereoChunks(framesPerChunk).size, 100, framesPerChunk)
    }

    @Test
    fun onlyTheFirstChunkOfAMarkerNamesTheMarker() {
        // The instant a marker was scheduled for is the instant of its first chunk. Every other
        // chunk of the sweep is a later instant, and attributing a reading to one of those is off
        // by up to the whole sweep.
        val marked = source(strideChunks = 250, firstMarkerChunk = 100)

        assertEquals(0, marked.markerStartIndex(100))
        assertNull(marked.markerStartIndex(101))
        assertNull(marked.markerStartIndex(105))
        assertNull(marked.markerStartIndex(99))
        assertEquals(1, marked.markerStartIndex(350))
        assertNull(marked.markerStartIndex(351))
        assertEquals(4, marked.markerStartIndex(1100))
    }

    @Test
    fun theSweepIsOfferedForStampingOverAChunkThatAlreadyHasContent() {
        // A sink has no source of its own - it plays chunks the host sends it - so the only way
        // it can put a sweep on the streamed release path is to write one over a chunk it
        // received. It needs the same sweep bytes on the same grid, and nothing else.
        val marked = source(strideChunks = 250, firstMarkerChunk = 100)

        assertNull(marked.markerPcm(99))
        assertNull(marked.markerPcm(106))
        assertArrayEquals(marked.chunkAt(100), marked.markerPcm(100))
        assertArrayEquals(marked.chunkAt(101), marked.markerPcm(101))
        assertArrayEquals(marked.chunkAt(350), marked.markerPcm(350))
    }

    @Test
    fun twoPhasesPutTheirSweepsOnChunksNeitherShares() {
        // The host and the sink stamp the same stride at different phases so that the two
        // emissions can be told apart in one recording. A phase that let them collide would give
        // a correlator two arrivals at one instant, which is the one shape it cannot read.
        val host = source(strideChunks = 250, firstMarkerChunk = 100)
        val sink = source(
            strideChunks = 250,
            firstMarkerChunk = MarkedStreamSource.facingPhase(100, 250)
        )

        for (sequence in 0..1500) {
            assertFalse(
                "sequence $sequence carries a sweep on both sides",
                host.markerPcm(sequence) != null && sink.markerPcm(sequence) != null
            )
        }
        assertNotNull(sink.markerPcm(225))
        assertNull(host.markerPcm(225))
    }

    @Test
    fun theFacingPhaseLeavesRoomForTheSweepOnEitherSideOfIt() {
        // Half a stride, and the half matters: the two sweeps have to sit as far from each other
        // as the grid allows, because what the analysis reads is which of the two arrivals is
        // which. Anything closer than a sweep's own length would let them touch.
        for (stride in 20..500) {
            val marked = source(strideChunks = stride, firstMarkerChunk = 0)
            val gap = MarkedStreamSource.facingPhase(0, stride)
            assertTrue(
                "stride $stride puts the two sweeps $gap chunks apart",
                gap >= marked.markerChunkCount && stride - gap >= marked.markerChunkCount
            )
        }
    }
}
