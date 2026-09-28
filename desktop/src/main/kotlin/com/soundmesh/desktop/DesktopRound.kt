package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.core.Resampler
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.WavFileWriter
import com.soundmesh.probe.sync.RoundChunks
import com.soundmesh.probe.sync.RoundRecorder
import com.soundmesh.probe.sync.RoundSpeaker
import java.io.File

/**
 * A measuring round's sound on this machine: chunks put on the output's frame timeline at the
 * host instants they carry.
 *
 * Not [ChunkPlayout], whose job is the opposite one. A stream has to sound continuous, so the
 * playout butts chunks together and lets the clock only steer; a chirp has to leave at the instant
 * the schedule named, so here the first chunk of every run is placed exactly where the clock says
 * and only the chunks that follow it in sequence are butted on - which keeps each sweep whole
 * without a frame's wobble inside it. And where the playout drops a late chunk and plays on, a late
 * chirp is [failure]: it played, at the wrong instant, and the recording of it would read as a
 * confident wrong answer.
 *
 * Through [GainOutput], at SoundMesh's own volume, as a handset's chirp goes out at its media volume.
 */
class FrameRoundSpeaker(
    private val open: () -> Speakers,
    private val volume: SoftwareVolume,
    private val hostNanosNow: () -> Long
) : RoundSpeaker {
    private var speakers: Speakers? = null
    private var output: FrameOutput? = null
    @Volatile private var endAt = Long.MAX_VALUE
    @Volatile private var stopped = false

    // The run being butted together: the sequence expected next and the frame it starts on.
    private var nextSequence = Int.MIN_VALUE
    private var nextFrame = 0L

    private var played = 0
    private var late = 0
    private var lateChirps = 0

    @Volatile
    override var failure: String? = null
        private set

    override fun start(endAtHostNanos: Long) {
        endAt = endAtHostNanos
        val opened = open()
        speakers = opened
        output = GainOutput(opened.output, volume)
    }

    override fun submit(chunk: AudioChunk) {
        val out = output ?: return
        val samples = samplesOf(chunk.pcm)
        val frames = samples.size / CHANNELS
        val atFrame = if (chunk.sequence == nextSequence) {
            nextFrame
        } else {
            out.frameAtLocalNanos(chunk.playAtHostNanos - (hostNanosNow() - System.nanoTime()))
        }
        nextSequence = chunk.sequence + 1
        nextFrame = atFrame + frames
        if (out.schedule(samples, CHANNELS, atFrame)) {
            played++
            return
        }
        late++
        if (chunk.sequence >= RoundChunks.CHIRP_SEQUENCE_BASE) {
            lateChirps++
            if (failure == null) failure = "chirp ${chunk.sequence} missed its frame"
        }
    }

    override fun stopNow() {
        stopped = true
    }

    override fun finish(): String? {
        while (!stopped) {
            val remaining = endAt - hostNanosNow()
            if (remaining <= 0) break
            Thread.sleep(minOf(remaining / 1_000_000 + 1, POLL_MILLIS))
        }
        speakers?.close()
        speakers = null
        return "{\"played\":$played,\"late\":$late,\"lateChirps\":$lateChirps}"
    }

    private companion object {
        /** The whole pipeline is stereo, as ChunkPlayout says. */
        const val CHANNELS = 2

        /** The longest this waits for the end without looking at whether it was stopped. */
        const val POLL_MILLIS = 100L
    }
}

/**
 * A measuring round's recording on this machine: the default microphone, raw where it can be.
 *
 * Raw because the room measurements this project has run on a laptop were taken that way (RoomShot,
 * RoomPair), and the endpoint's own processing is what a measurement wants out of its way - the
 * handset asks for UNPROCESSED first for the same reason. A microphone that refuses raw is opened
 * without it, as a handset falls back to the next source rather than failing the round.
 *
 * [startedAtHostNanos] is read once capture has started, and it is a search hint only, exactly as
 * on the handset: the arrivals are pinned to samples by correlation.
 *
 * A microphone at 44.1 kHz is recorded as it is and saved at 48 kHz, see [atChirpRate]. A handset
 * has no such step: it asks AudioRecord for 48 kHz and Android is expected to convert underneath -
 * expected, not seen, since no handset here records at 44.1.
 */
class WasapiRoundRecorder(
    private val runStore: RunStore,
    private val caseId: String,
    private val hostNanosNow: () -> Long,
    private val openCapture: (raw: Boolean) -> WasapiCapture = { WasapiCapture(raw = it) }
) : RoundRecorder {
    @Volatile
    override var startedAtHostNanos: Long? = null
        private set

    /** Whether the capture that opened was raw, once [record] has run. */
    @Volatile
    var openedRaw: Boolean? = null
        private set

    override fun record(seconds: Int, stopped: () -> Boolean) {
        val capture = runCatching { openCapture(true) }.getOrNull()?.also { openedRaw = true }
            ?: openCapture(false).also { openedRaw = false }
        capture.use {
            check(MicrophoneCheck.recordsAt(it.format.sampleRate)) {
                "the microphone runs at ${it.format.sampleRate} Hz and the chirp is ${ChirpGenerator.SAMPLE_RATE} Hz"
            }
            it.start()
            startedAtHostNanos = runCatching(hostNanosNow).getOrNull()
            val deadline = System.nanoTime() + seconds * 1_000_000_000L
            while (System.nanoTime() < deadline && !stopped()) Thread.sleep(POLL_MILLIS)
            it.stop()
            val mono = atChirpRate(it.take().mono, it.format.sampleRate)
            writeMono(File(runStore.prepareRun(caseId), "calibration.wav"), mono)
        }
        // The reference must be the very samples that were played, as the handset saves it.
        writeMono(File(runStore.prepareRun(caseId), "chirp.wav"), ChirpGenerator.generateMono())
    }

    override fun discardRecording() {
        runCatching {
            val directory = runStore.prepareRun(caseId)
            File(directory, "calibration.wav").delete()
            File(directory, "chirp.wav").delete()
        }
    }

    private fun writeMono(file: File, samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            bytes[index * 2] = (samples[index].toInt() and 0xFF).toByte()
            bytes[index * 2 + 1] = (samples[index].toInt() shr 8).toByte()
        }
        WavFileWriter(file, ChirpGenerator.SAMPLE_RATE, 1).use { it.writePcm(bytes, bytes.size) }
    }

    private companion object {
        const val POLL_MILLIS = 50L
    }
}

/**
 * [mono] recorded at [sampleRate], at the chirp's rate: the same array when it already is, so every
 * recording made at 48 kHz stays sample for sample what it was.
 *
 * After the round rather than on the way in, because the analysis only ever counts samples from the
 * first one, and [Resampler]'s filter is centred - the first sample stays the first instant and
 * nothing moves but by its twenty-nanosecond phase rounding.
 */
fun atChirpRate(mono: ShortArray, sampleRate: Int): ShortArray {
    if (sampleRate == ChirpGenerator.SAMPLE_RATE) return mono
    val bytes = ByteArray(mono.size * 2)
    for (index in mono.indices) {
        bytes[index * 2] = (mono[index].toInt() and 0xFF).toByte()
        bytes[index * 2 + 1] = (mono[index].toInt() shr 8).toByte()
    }
    // Stereo is what the converter hands back; both channels are the one microphone.
    val stereo = Resampler.toStereo(bytes, sampleRate, 1, ChirpGenerator.SAMPLE_RATE)
    return ShortArray(stereo.size / 4) {
        ((stereo[it * 4].toInt() and 0xFF) or (stereo[it * 4 + 1].toInt() shl 8)).toShort()
    }
}

/**
 * Why this machine cannot record a round, in the ways that have a fix a person can make.
 *
 * NOT_48K is a rate a round does not record at, see [MicrophoneCheck.recordsAt]; it is named from
 * before 44.1 kHz was taken too.
 *
 * MUTED is a microphone that opens and hears nothing: the endpoint muted (a laptop's F4), its
 * level at zero, or silenced somewhere nothing reports - see [MicrophoneCheck.silenced].
 */
enum class MicrophoneProblem { NO_DEVICE, DENIED, NOT_48K, MUTED, OTHER }

/**
 * Whether a round could record here, asked by opening the microphone once and closing it.
 *
 * Asked before the round rather than found out inside it, because a round that cannot record still
 * takes a room's slot: the host would wait for a delivery that is never coming. Said instead, the
 * host's line for this machine reads "no microphone" at once, as it does for a handset without the
 * permission.
 *
 * DENIED is the answer for access refused, which is what Windows' microphone privacy switch is
 * expected to give. Expected, not seen: nobody has turned it off and watched.
 */
object MicrophoneCheck {
    fun problem(open: (raw: Boolean) -> WasapiCapture = { WasapiCapture(raw = it) }): Pair<MicrophoneProblem, String>? {
        val capture = try {
            runCatching { open(true) }.getOrNull() ?: open(false)
        } catch (e: Throwable) {
            val said = e.message ?: e.toString()
            return when {
                said.contains("GetDefaultAudioEndpoint") -> MicrophoneProblem.NO_DEVICE to said
                said.contains(ACCESS_DENIED) -> MicrophoneProblem.DENIED to said
                else -> MicrophoneProblem.OTHER to said
            }
        }
        capture.use {
            if (!recordsAt(it.format.sampleRate)) {
                return MicrophoneProblem.NOT_48K to "${it.format.sampleRate} Hz"
            }
            // Listened to only when the endpoint's own word does not already settle it, so a muted
            // microphone is said at once rather than after half a second of zeros.
            val level = it.volume
            val heard = if (level != null && (level.second || level.first <= 0f)) ShortArray(0) else listen(it)
            if (silenced(level, heard)) {
                val said = level?.let { (scalar, muted) -> "${"%.0f".format(scalar * 100)}%${if (muted) ", muted" else ""}" }
                return MicrophoneProblem.MUTED to "${said ?: "level unknown"}, ${heard.size} samples heard"
            }
        }
        return null
    }

    /**
     * Whether a round can record through a microphone at [sampleRate]: the chirp's own rate, or
     * 44.1 kHz put at it after the round by [atChirpRate]. Only 44.1 was replayed against the archive
     * (2026-09-28, see MicrophoneRateTest), so no other rate is taken on the strength of it.
     */
    fun recordsAt(sampleRate: Int): Boolean = sampleRate == ChirpGenerator.SAMPLE_RATE || sampleRate == 44_100

    /**
     * Whether a microphone that opened is one a round would hear nothing through.
     *
     * The endpoint's mute and level are read first because they name the cause; the recording is
     * what catches the rest - a switch that mutes below Windows, or a privacy setting that hands a
     * desktop app zeros rather than refusing it. Every sample exactly zero is not a threshold: on
     * this project's laptop a live microphone in a quiet room never came near it (see
     * MicrophoneCheckTest). Nothing heard at all is not taken as silence - that is a timing
     * question, and a round refused on it would be refused on a guess.
     */
    fun silenced(volume: Pair<Float, Boolean>?, heard: ShortArray): Boolean {
        if (volume != null && (volume.second || volume.first <= 0f)) return true
        return heard.isNotEmpty() && heard.all { it.toInt() == 0 }
    }

    private fun listen(capture: WasapiCapture): ShortArray {
        capture.start()
        Thread.sleep(LISTEN_MILLIS)
        capture.stop()
        return capture.take().mono
    }

    /** Long enough for a few dozen packets, short enough not to be felt before a round. */
    private const val LISTEN_MILLIS = 500L

    /** E_ACCESSDENIED as Wasapi.check prints it. */
    private const val ACCESS_DENIED = "0x80070005"
}
