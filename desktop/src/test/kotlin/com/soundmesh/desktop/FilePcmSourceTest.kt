package com.soundmesh.desktop

import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.TonePcmSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem


class FilePcmSourceTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aFileAlreadyInTheTargetFormatComesBackByteForByte() {
        // The resampler's guarantee, which this source has to carry rather than quietly break:
        // every archived measurement was made against 48 kHz stereo, and a file that is already
        // that must arrive at the wire as the bytes on disk.
        val pcm = ramp(frames = ChunkCodec.FRAMES_PER_CHUNK, channels = 2)
        val source = FilePcmSource.open(write(pcm, rate = 48000, channels = 2))

        assertEquals(ChunkCodec.FRAMES_PER_CHUNK, source.frameCount)
        assertArrayEquals(pcm, source.fill(0, ChunkCodec.FRAMES_PER_CHUNK))
    }

    @Test
    fun theRangesAskedForJoinOntoOneAnother() {
        // The host asks for one chunk at a time by absolute frame index and never says where it
        // is in the file. Two neighbouring asks have to come back as one run of audio.
        val pcm = ramp(frames = ChunkCodec.FRAMES_PER_CHUNK * 2, channels = 2)
        val source = FilePcmSource.open(write(pcm, rate = 48000, channels = 2))

        val first = source.fill(0, ChunkCodec.FRAMES_PER_CHUNK)
        val second = source.fill(ChunkCodec.FRAMES_PER_CHUNK.toLong(), ChunkCodec.FRAMES_PER_CHUNK)

        assertArrayEquals(pcm, first + second)
    }

    @Test
    fun theAudioLoopsSoEveryFrameIndexHasAnAnswer() {
        // A run is as long as somebody asks for and a file is as long as it is. Looping is also
        // what the handset source does, and the seam where it wraps is left in on purpose: both
        // ends emit that transient from the same chunk at the same instant, which is far easier
        // to hear misalignment in than sustained music is.
        val pcm = ramp(frames = ChunkCodec.FRAMES_PER_CHUNK, channels = 2)
        val source = FilePcmSource.open(write(pcm, rate = 48000, channels = 2))

        val firstTimeRound = source.fill(0, ChunkCodec.FRAMES_PER_CHUNK)
        val secondTimeRound = source.fill(ChunkCodec.FRAMES_PER_CHUNK.toLong(), ChunkCodec.FRAMES_PER_CHUNK)
        val muchLater = source.fill(ChunkCodec.FRAMES_PER_CHUNK * 1000L, ChunkCodec.FRAMES_PER_CHUNK)

        assertArrayEquals(firstTimeRound, secondTimeRound)
        assertArrayEquals(firstTimeRound, muchLater)
    }

    @Test
    fun theLoopIsAWholeNumberOfChunksSoTheWrapLandsOnAChunkBoundary() {
        // Five frames of tail would put the wrap in the middle of a chunk, and then the transient
        // the ear test relies on would be at a different offset in each run instead of at the
        // start of a chunk both machines were sent.
        val frames = ChunkCodec.FRAMES_PER_CHUNK * 2 + 5
        val source = FilePcmSource.open(write(ramp(frames, channels = 2), rate = 48000, channels = 2))

        assertEquals(ChunkCodec.FRAMES_PER_CHUNK * 2, source.frameCount)
        assertArrayEquals(
            source.fill(0, ChunkCodec.FRAMES_PER_CHUNK),
            source.fill(ChunkCodec.FRAMES_PER_CHUNK * 2L, ChunkCodec.FRAMES_PER_CHUNK)
        )
    }

    @Test
    fun aFileShorterThanOneChunkIsRefusedRatherThanLoopedFaster() {
        val short = write(ramp(frames = ChunkCodec.FRAMES_PER_CHUNK - 1, channels = 2), rate = 48000, channels = 2)

        val thrown = runCatching { FilePcmSource.open(short) }.exceptionOrNull()

        assertTrue("expected a refusal, got $thrown", thrown is IllegalArgumentException)
        assertTrue("the message should name the length: ${thrown?.message}", thrown!!.message!!.contains("20 ms"))
    }

    @Test
    fun aMonoFileAtAnotherRateArrivesAsStereoAtTheRendererRate() {
        // Both halves of the conversion at once, because they are one call into the resampler the
        // handset already uses: half the rate doubles the frames, and one channel becomes two.
        val frames = ChunkCodec.FRAMES_PER_CHUNK * 2
        val source = FilePcmSource.open(write(ramp(frames, channels = 1), rate = 24000, channels = 1))

        assertEquals(frames * 2, source.frameCount)
        val filled = source.fill(0, ChunkCodec.FRAMES_PER_CHUNK)
        assertEquals(ChunkCodec.FRAMES_PER_CHUNK * 2 * 2, filled.size)
    }

    @Test
    fun aFileThatIsNotSixteenBitIsNormalisedRatherThanRefused() {
        // Eight bit is unsigned in a WAV and sixteen is signed, which is a conversion nobody
        // should have to know about here. What matters is that it opens and that silence at the
        // eight bit midpoint arrives as silence rather than as a large constant offset.
        val midpoint = ByteArray(ChunkCodec.FRAMES_PER_CHUNK * 2) { 0x80.toByte() }
        val source = FilePcmSource.open(write(midpoint, rate = 48000, channels = 2, bits = 8))

        assertEquals(ChunkCodec.FRAMES_PER_CHUNK, source.frameCount)
        assertArrayEquals(ByteArray(ChunkCodec.FRAMES_PER_CHUNK * 2 * 2), source.fill(0, ChunkCodec.FRAMES_PER_CHUNK))
    }

    /** A sawtooth over the whole range, so every frame differs from its neighbours. */
    private fun ramp(frames: Int, channels: Int): ByteArray {
        val pcm = ByteArray(frames * channels * 2)
        for (sample in 0 until frames * channels) {
            val value = (sample * 37 % 30000 - 15000).toShort().toInt()
            pcm[sample * 2] = (value and 0xFF).toByte()
            pcm[sample * 2 + 1] = (value shr 8).toByte()
        }
        return pcm
    }

    private fun write(pcm: ByteArray, rate: Int, channels: Int, bits: Int = 16): File {
        val format = AudioFormat(rate.toFloat(), bits, channels, bits != 8, false)
        val file = folder.newFile("source-$rate-$channels-$bits-${pcm.size}.wav")
        AudioInputStream(
            ByteArrayInputStream(pcm), format, (pcm.size / format.frameSize).toLong()
        ).use { AudioSystem.write(it, AudioFileFormat.Type.WAVE, file) }
        return file
    }

    @Test
    fun theSourceHandsTheHostTheSameShapeTheToneDoes() {
        // HostStream takes whichever of the two it was given, so the one thing that must not
        // differ is the shape of an answer: this many frames of stereo, at this frame index.
        val source = FilePcmSource.open(
            write(ramp(ChunkCodec.FRAMES_PER_CHUNK, channels = 2), rate = 48000, channels = 2)
        )

        val fromFile = source.fill(0, ChunkCodec.FRAMES_PER_CHUNK)
        val fromTone = TonePcmSource().fill(0, ChunkCodec.FRAMES_PER_CHUNK)

        assertEquals(fromTone.size, fromFile.size)
    }
}
