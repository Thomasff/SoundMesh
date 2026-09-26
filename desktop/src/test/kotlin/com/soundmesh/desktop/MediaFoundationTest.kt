package com.soundmesh.desktop

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
import kotlin.math.abs
import kotlin.math.sin

class MediaFoundationTest {
    @get:Rule
    val folder = TemporaryFolder()

    /**
     * The judge with no grey area: a WAV read through Media Foundation is byte for byte what
     * javax.sound reads, at a rate and channel count other than the stream's so neither side could
     * have been converting on the quiet.
     */
    @Test
    fun aWavComesOutByteForByteWhatJavaSoundReads() {
        val wav = writeWav(folder.newFile("tone.wav"), tone(44_100, 2.0), rate = 44_100, channels = 1)
        val expected = AudioSystem.getAudioInputStream(wav).use { it.readBytes() }

        val decoded = MediaFoundation.decode(wav, maxSeconds = 60)

        assertEquals(44_100, decoded.sampleRate)
        assertEquals(1, decoded.channels)
        assertArrayEquals(expected, decoded.pcm)
    }

    /**
     * An MP3 that Windows' own encoder wrote:
     * two seconds of 440 Hz, 44.1 kHz mono. It keeps its rate and channels - the resampling is
     * core's - and its pitch. The encoder pads the start by some tens of milliseconds, which both
     * ends of a room receive as the same bytes, so the length is only bounded.
     */
    @Test
    fun anMp3KeepsItsRateItsChannelsAndItsPitch() {
        val decoded = MediaFoundation.decode(fixture(), maxSeconds = 60)

        assertEquals(44_100, decoded.sampleRate)
        assertEquals(1, decoded.channels)
        val frames = decoded.pcm.size / 2
        assertTrue("$frames frames", frames in 88_200..92_200)
        assertEquals(440.0, pitchOfLoudPart(decoded.pcm, 44_100), 0.5)
    }

    @Test
    fun aFileThatIsNotSoundSaysWhatCanBeOpened() {
        val junk = folder.newFile("junk.mp3").apply { writeText("not a song") }
        val thrown = runCatching { MediaFoundation.decode(junk, maxSeconds = 60) }.exceptionOrNull()
        assertTrue("$thrown", thrown is IllegalArgumentException && thrown.message!!.contains("not in a format"))
    }

    /** More channels than a stereo pipeline can place is refused, not folded down by guesswork. */
    @Test
    fun moreThanTwoChannelsIsRefused() {
        val wav = writeWav(folder.newFile("six.wav"), ByteArray(48_000 * 6 * 2), rate = 48_000, channels = 6)
        val thrown = runCatching { MediaFoundation.decode(wav, maxSeconds = 60) }.exceptionOrNull()
        assertTrue("$thrown", thrown is IllegalArgumentException && thrown.message!!.contains("channels"))
    }

    /** Held whole in memory, so the length is bounded while reading rather than after. */
    @Test
    fun aFileLongerThanTheLimitIsRefused() {
        val thrown = runCatching { MediaFoundation.decode(fixture(), maxSeconds = 1) }.exceptionOrNull()
        assertTrue("$thrown", thrown is IllegalArgumentException && thrown.message!!.contains("longer than"))
    }

    /** The reader holds the file open; once decoded, the person can move or delete it. */
    @Test
    fun theFileIsLetGoOfOnceDecoded() {
        val copy = fixture()
        MediaFoundation.decode(copy, maxSeconds = 60)
        assertTrue("the file is still held open", copy.delete())
    }

    private fun fixture(): File {
        val resource = File(javaClass.getResource("/tone-44k-mono-440.mp3")!!.toURI())
        return resource.copyTo(folder.newFile("tone.mp3"), overwrite = true)
    }

    private fun tone(rate: Int, seconds: Double): ByteArray {
        val frames = (rate * seconds).toInt()
        val pcm = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val v = (12_000 * sin(2 * Math.PI * 440.0 * i / rate)).toInt()
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = (v shr 8).toByte()
        }
        return pcm
    }

    private fun writeWav(file: File, pcm: ByteArray, rate: Int, channels: Int): File {
        val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
        AudioInputStream(ByteArrayInputStream(pcm), format, (pcm.size / (channels * 2)).toLong())
            .use { AudioSystem.write(it, AudioFileFormat.Type.WAVE, file) }
        return file
    }

    /** Upward zero crossings, interpolated, over the part louder than the encoder's padding. */
    private fun pitchOfLoudPart(pcm: ByteArray, rate: Int): Double {
        fun s(i: Int) = ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort().toInt()
        val frames = pcm.size / 2
        val loud = (0 until frames).filter { abs(s(it)) > 8_000 }
        val crossings = ArrayList<Double>()
        for (i in loud.first() + 2_000 until loud.last() - 2_000) {
            if (s(i - 1) < 0 && s(i) >= 0) crossings.add(i - s(i).toDouble() / (s(i) - s(i - 1)))
        }
        return (crossings.size - 1) * rate / (crossings.last() - crossings.first())
    }
}
