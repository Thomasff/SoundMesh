package com.soundmesh.probe.sync

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.CalibrationPlan
import com.soundmesh.core.CalibrationRole
import com.soundmesh.core.ChirpGenerator
import com.soundmesh.probe.RunStore
import com.soundmesh.probe.WavFileWriter
import java.io.File
import java.nio.file.Files
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One whole round through the runner that is now the same copy on a handset and a desktop, with
 * the platform's two halves stood in for: a speaker that plays nothing, a recorder that writes a
 * room whose timing is known.
 *
 * The analysis has its own tests; this is about the runner putting the recorder, the speaker and
 * the analysis together the way both platforms now rely on.
 */
class PeerCalibrationRunnerRoundTest {
    private val second = 1_000_000_000L
    private val rate = ChirpGenerator.SAMPLE_RATE
    private val stagger = second / 2
    private val interval = 2 * second
    private val staggerFrames = (stagger * rate / second).toInt()

    /** Host time running twenty times fast, so the runner's waits pass in a twentieth of the time. */
    private val origin = System.nanoTime()
    private val hostNanosNow = { origin + (System.nanoTime() - origin) * 20 }

    private class QuietSpeaker(override val failure: String? = null) : RoundSpeaker {
        val chunks = ArrayList<AudioChunk>()
        override fun start(endAtHostNanos: Long) {}
        override fun submit(chunk: AudioChunk) { synchronized(chunks) { chunks.add(chunk) } }
        override fun stopNow() {}
        override fun finish(): String? = "{}"
    }

    /**
     * Writes a room: every slot's chirp where the plan puts it, [lateFrames] late for [lateSlot],
     * the recording opening [leadNanos] before the first chirp.
     */
    private inner class RoomRecorder(
        private val runStore: RunStore,
        private val caseId: String,
        private val plan: CalibrationPlan,
        private val ownSlot: Int,
        private val lateSlot: Int,
        private val lateFrames: Int
    ) : RoundRecorder {
        private val leadNanos = second
        override var startedAtHostNanos: Long? = null

        override fun record(seconds: Int, stopped: () -> Boolean) {
            startedAtHostNanos = plan.firstChirpAtHostNanos - leadNanos
            val chirp = ChirpGenerator.generateMono()
            val slots = plan.slotIds.size
            val span = leadNanos + plan.repeats * interval + slots * stagger + 2 * second
            val out = ShortArray((span * rate / second).toInt())
            val noise = Random(7)
            for (index in out.indices) out[index] = (noise.nextDouble() * 80 - 40).toInt().toShort()
            val leadFrames = (leadNanos * rate / second).toInt()
            val gapFrames = (interval * rate / second).toInt()
            for (repeat in 0 until plan.repeats) {
                for (slot in 0 until slots) {
                    val at = leadFrames + repeat * gapFrames + slot * staggerFrames +
                        if (slot == lateSlot) lateFrames else 0
                    val gain = if (slot == ownSlot) 0.8 else 0.2
                    for (index in chirp.indices) {
                        out[at + index] = (out[at + index] + chirp[index] * gain).toInt().toShort()
                    }
                }
            }
            val bytes = ByteArray(out.size * 2)
            for (index in out.indices) {
                bytes[index * 2] = (out[index].toInt() and 0xFF).toByte()
                bytes[index * 2 + 1] = (out[index].toInt() shr 8).toByte()
            }
            WavFileWriter(File(runStore.prepareRun(caseId), "calibration.wav"), rate, 1)
                .use { it.writePcm(bytes, bytes.size) }
        }

        override fun discardRecording() {}
    }

    private fun roomRun(late: Int, speaker: QuietSpeaker): PeerCalibrationRun {
        val directory = Files.createTempDirectory("round").toFile()
        val store = RunStore(directory)
        val plan = CalibrationPlan(
            caseId = "C94",
            hostId = "host",
            firstChirpAtHostNanos = hostNanosNow() + 3 * second,
            staggerNanos = stagger,
            repeats = 2,
            intervalNanos = interval,
            slotIds = listOf("host", "sink")
        )
        return PeerCalibrationRunner(
            runStore = store,
            caseId = plan.caseId,
            role = CalibrationRole.SINK,
            plan = plan,
            ownSlot = 1,
            hostNanosNow = hostNanosNow,
            recorder = RoomRecorder(store, plan.caseId, plan, ownSlot = 1, lateSlot = 0, lateFrames = late),
            speaker = { speaker }
        ).run()
    }

    @Test
    fun aRoomReadsTheOtherSlotWhereTheRecordingPutIt() {
        val speaker = QuietSpeaker()
        val run = roomRun(late = 147, speaker = speaker)

        assertNull(run.json, run.refusal)
        assertEquals(2, run.arrivalsByRepeat.size)
        for (repeat in run.arrivalsByRepeat) {
            val host = assertNotNullAndGet(repeat[0])
            val own = assertNotNullAndGet(repeat[1])
            // The host's slot sits one stagger before this one, and 147 frames late on top.
            val apart = own.index - host.index
            assertTrue("read $apart frames apart", kotlin.math.abs(apart - (staggerFrames - 147)) <= 2)
        }
        // The speaker was handed this device's chirps, and only in its own slot's band.
        assertTrue(speaker.chunks.any { it.sequence >= RoundChunks.CHIRP_SEQUENCE_BASE })
    }

    @Test
    fun aSpeakerThatMissedAChirpRefusesTheRound() {
        val run = roomRun(late = 0, speaker = QuietSpeaker(failure = "chirp 1000000 missed its frame"))

        assertNotNull(run.refusal)
        assertTrue(run.refusal!!, run.refusal!!.contains("the sound could not be placed"))
        assertTrue(run.arrivalsByRepeat.isEmpty())
    }

    private fun assertNotNullAndGet(arrival: com.soundmesh.core.ChirpArrival?): com.soundmesh.core.ChirpArrival {
        assertNotNull(arrival)
        return arrival!!
    }
}
