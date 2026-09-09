package com.soundmesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversion fed in pieces has to come out as the same song it comes out as fed whole.
 *
 * That is the whole claim, and it is stronger than it looks. The filter reads sixteen samples
 * either side of every output frame, and the output instant of frame n is worked out from n
 * itself rather than by stepping - so a piece boundary is somewhere the naive version would
 * either invent samples that are not there yet or restart its counting. Either shows up as a step
 * in the waveform fifty times a second, which is audible, and neither would fail a test that only
 * checked that a tone kept its pitch.
 *
 * Compared against [Resampler.toStereo] rather than against a fixed digest, because that function
 * is what every archived measurement went through - and [ResamplerWaveformTest] is what stops it
 * from drifting underneath this.
 */
class StreamingResamplerTest {
    /**
     * Sizes chosen so that some of them cut a frame in half: a stereo frame is four bytes, so 1,
     * 3, 5 and 997 all end mid-frame, and a piece that ends mid-frame is the case where bytes have
     * to be carried rather than samples.
     */
    private val pieceSizes = listOf(1, 3, 4, 5, 64, 997, 4096, 1 shl 20)

    @Test
    fun everyPieceSizeMakesTheSameSongAtARateChange() {
        assertPiecesAgree(44_100, 2)
    }

    @Test
    fun everyPieceSizeMakesTheSameSongComingUpFromHalfRate() {
        assertPiecesAgree(22_050, 1)
    }

    @Test
    fun everyPieceSizeMakesTheSameSongComingDownFromDoubleRate() {
        assertPiecesAgree(96_000, 2)
    }

    /** The share path, which has no filter and so no history - and must still be fed in pieces. */
    @Test
    fun everyPieceSizeMakesTheSameSongWhenOnlyTheChannelsChange() {
        assertPiecesAgree(48_000, 1)
    }

    /** And the path that does nothing at all, which still has to hand back what it was given. */
    @Test
    fun everyPieceSizeMakesTheSameSongWhenNothingNeedsDoing() {
        assertPiecesAgree(48_000, 2)
    }

    /**
     * A source is under no obligation to hand over whole frames. MediaCodec's output buffers are
     * whatever the decoder felt like producing, and one that ended mid-frame would otherwise lose
     * half a sample at every boundary - quiet, and wrong by a different amount every time.
     */
    @Test
    fun aPieceThatEndsInTheMiddleOfAFrameLosesNothing() {
        val pcm = noise(44_100, 2, 2000)
        assertArrayEquals(
            Resampler.toStereo(pcm, 44_100, 2, 48_000),
            streamed(pcm, 44_100, 2, 3)
        )
    }

    /**
     * Nothing may be emitted before the samples it is made of have arrived.
     *
     * The alternative is to clamp at the edge of whatever piece is in hand, which is exactly what
     * the whole-buffer version does at the true end of the song - correct there, and a fabricated
     * sample anywhere else. So the last few frames must still be waiting when the input runs out.
     */
    @Test
    fun theTailIsNotInventedBeforeTheSongEnds() {
        val pcm = noise(44_100, 2, 2000)
        val stream = StreamingResampler(44_100, 2, 48_000)
        val early = stream.write(pcm)
        val whole = Resampler.toStereo(pcm, 44_100, 2, 48_000)
        assertTrue("nothing should be held back: ${early.size} of ${whole.size}", early.size < whole.size)
        assertArrayEquals(whole, early + stream.finish())
    }

    /** A song nobody chose is not a crash. */
    @Test
    fun anEmptyStreamIsAnEmptySong() {
        val stream = StreamingResampler(44_100, 2, 48_000)
        assertEquals(0, stream.write(ByteArray(0)).size)
        assertEquals(0, stream.finish().size)
    }

    /**
     * The point of the class. Whatever it is fed, what it keeps is the filter's own reach plus the
     * piece in hand - not the song. Asserted against a song far larger than that, because "it
     * streams" is precisely the claim that a longer song does not cost more.
     */
    @Test
    fun aLongerSongDoesNotCostMoreToConvert() {
        val stream = StreamingResampler(44_100, 2, 48_000)
        val piece = noise(44_100, 2, 500)
        var worst = 0
        repeat(200) {
            stream.write(piece)
            worst = maxOf(worst, stream.heldFrames)
        }
        assertTrue("held $worst frames of 100000", worst < 600)
    }

    private fun assertPiecesAgree(rate: Int, channels: Int) {
        val pcm = noise(rate, channels, 3000)
        val whole = Resampler.toStereo(pcm, rate, channels, 48_000)
        for (size in pieceSizes) {
            assertArrayEquals("pieces of $size bytes at $rate/$channels", whole, streamed(pcm, rate, channels, size))
        }
    }

    private fun streamed(pcm: ByteArray, rate: Int, channels: Int, pieceSize: Int): ByteArray {
        val stream = StreamingResampler(rate, channels, 48_000)
        var out = ByteArray(0)
        var at = 0
        while (at < pcm.size) {
            val end = minOf(at + pieceSize, pcm.size)
            out += stream.write(pcm.copyOfRange(at, end))
            at = end
        }
        return out + stream.finish()
    }

    /** Full scale pseudo-random samples: a tone only visits a handful of the 512 filter phases. */
    private fun noise(rate: Int, channels: Int, frames: Int): ByteArray {
        val pcm = ByteArray(frames * channels * 2)
        var state = rate.toLong() * 31 + channels
        for (i in 0 until frames * channels) {
            state = (state * 6364136223846793005L + 1442695040888963407L) ushr 1
            val value = (state and 0xFFFF).toInt() - 32768
            pcm[i * 2] = (value and 0xFF).toByte()
            pcm[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return pcm
    }
}
