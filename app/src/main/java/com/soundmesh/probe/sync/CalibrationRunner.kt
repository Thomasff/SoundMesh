package com.soundmesh.probe.sync

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.WavFileWriter
import java.io.File

/**
 * Which capture path the calibration recording opens.
 *
 * [MIC] is what every measurement so far was taken on, and it is the one that runs the vendor's
 * own processing chain - noise suppression and, on a multi-microphone handset, beamforming - whose
 * configuration lives in the vendor partition and differs between chip platforms. Two properties of
 * that chain matter here and neither is a fixed latency the calibration constant could absorb:
 * the adaptive stages have a convergence period whose group delay moves while they settle, and
 * beamforming is by construction direction-dependent. The two chirps land about one and one and a
 * half seconds into a recording, so both sit inside that settling window and the delay applied to
 * them differs, which does not cancel in the difference.
 *
 * [UNPROCESSED] is the one Android's CDD requires to add no processing delay at all, so it is the
 * first choice; not every device implements it. [VOICE_RECOGNITION] is the documented fallback -
 * AAudio's own default, described as lowest latency on most platforms.
 */
enum class CalibrationAudioSource(val androidSource: Int) {
    MIC(MediaRecorder.AudioSource.MIC),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED);

    companion object {
        /** The requested source, or [MIC] for anything unrecognised - never a silent failure. */
        fun parse(name: String?): CalibrationAudioSource =
            entries.firstOrNull { it.name == name } ?: MIC
    }
}

/**
 * Records the room so the PC can measure, and saves the reference the measurement correlates
 * against.
 *
 * Playing the chirp is deliberately not this class's job: both devices inject it into their own
 * PlaybackScheduler instead, so it travels the same scheduling, output depth compensation and
 * drift correction path the music does. A chirp played here on a private AudioTrack would have
 * measured clock sync plus raw play() latency and left the pipeline being validated untested.
 */
class CalibrationRunner(
    private val runStore: RunStore,
    private val caseId: String,
    private val requestedSource: CalibrationAudioSource = CalibrationAudioSource.MIC,
    private val hostNanosNow: (() -> Long)? = null
) {
    /**
     * The host instant the recording opened at, once [record] has started it.
     *
     * Without it the file is a recording that never says when it began, and the only safe way to
     * find a chirp in one is to search the whole interval - 2.88 million lags for a 60 second
     * schedule, 22 seconds of correlation on a PC. With it the search collapses to the uncertainty
     * between this reading and the first captured sample. See CalibrationWindow.
     *
     * Read after `startRecording()` rather than before it, so the open cost sits outside the
     * uncertainty rather than inside it. It is a search hint and never an input to a measurement:
     * arrivals stay pinned to the sample by correlation, so half a second of accuracy is ample and
     * no capture timestamp API has to be trusted for it.
     */
    @Volatile
    var startedAtHostNanos: Long? = null
        private set
    /**
     * The source that actually opened, once [record] has run. Not the same as the requested one:
     * UNPROCESSED is optional on Android, so a run that asks for it may still have been recorded on
     * the fallback, and a measurement has to say which path produced it rather than let the
     * intent's request stand in for it.
     */
    @Volatile
    var openedSource: CalibrationAudioSource? = null
        private set

    /**
     * Records for [seconds], or until [stopped] answers true.
     *
     * The predicate is what makes a round stoppable. Everything else about a calibration is
     * scheduled: the recording runs to an instant worked out before anything opened, and until
     * this existed there was nothing anywhere to ask whether the run was still wanted.
     */
    fun record(seconds: Int, stopped: () -> Boolean = { false }) {
        val minimum = AudioRecord.getMinBufferSize(ChirpGenerator.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minimum, 65536)
        val record = open(requestedSource, bufferBytes)
            ?: open(CalibrationAudioSource.VOICE_RECOGNITION, bufferBytes)
            ?: open(CalibrationAudioSource.MIC, bufferBytes)
            ?: throw IllegalStateException("no capture source would open")
        val target = File(runStore.prepareRun(caseId), "calibration.wav")
        WavFileWriter(target, ChirpGenerator.SAMPLE_RATE, 1).use { writer ->
            record.startRecording()
            // A run that cannot read the host clock is still a run worth recording, so a failure
            // here costs the cheap search window and nothing else.
            startedAtHostNanos = hostNanosNow?.let { runCatching(it).getOrNull() }
            val buffer = ByteArray(8192)
            val deadline = System.nanoTime() + seconds * 1_000_000_000L
            while (System.nanoTime() < deadline && !stopped()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) writer.writePcm(buffer, read)
            }
            record.stop()
        }
        record.release()
        // The reference must be the very bytes that were played, so it is saved rather than
        // reimplemented on the PC. Two generators drifting apart would corrupt every measurement.
        val chirp = ChirpGenerator.generateMono()
        val bytes = ByteArray(chirp.size * 2)
        for (index in chirp.indices) {
            bytes[index * 2] = (chirp[index].toInt() and 0xFF).toByte()
            bytes[index * 2 + 1] = (chirp[index].toInt() shr 8).toByte()
        }
        WavFileWriter(File(runStore.prepareRun(caseId), "chirp.wav"), ChirpGenerator.SAMPLE_RATE, 1)
            .use { it.writePcm(bytes, bytes.size) }
    }

    /**
     * Opens [source], or returns null if the device refuses it.
     *
     * An AudioRecord for an unsupported source constructs without throwing and then sits in
     * STATE_UNINITIALIZED, so the state has to be checked rather than the constructor trusted -
     * otherwise startRecording throws mid-run and the whole calibration is lost to a source that
     * was only ever optional.
     */
    private fun open(source: CalibrationAudioSource, bufferBytes: Int): AudioRecord? {
        val record = runCatching {
            AudioRecord(source.androidSource, ChirpGenerator.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        }.getOrNull() ?: return null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return null
        }
        openedSource = source
        return record
    }
}
