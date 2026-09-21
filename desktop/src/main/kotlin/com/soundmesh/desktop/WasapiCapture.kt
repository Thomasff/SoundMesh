package com.soundmesh.desktop

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * One packet as the engine handed it over: how much, when, and whether it can be believed.
 *
 * [qpcPosition] is in the same ticks as [WasapiRenderer.now], converted from the hundred-nanosecond
 * units the API reports in. Keeping every packet's stamp rather than only the first is what lets a
 * recording be checked for drift against the clock that stamped it, which is the same discipline
 * the render side is measured with.
 */
data class CapturePacket(
    val devicePosition: Long,
    val qpcPosition: Long,
    val frames: Int,
    val flags: Int,
    /** Where this packet's first frame ended up in [Recording.mono]. */
    val atIndex: Int
)

/**
 * A recording and everything needed to say when each of its samples happened.
 *
 * The timeline is built on [CapturePacket.atIndex] and the packet's own stamp - on where a
 * packet's samples actually sit in [mono] - and not on the engine's device position counter. The
 * two are the same thing only if every jump in that counter means audio was really lost, and on
 * this endpoint they are not: see [WasapiCapture.fillGaps].
 */
data class Recording(
    val mono: ShortArray,
    val firstDevicePosition: Long,
    val packets: List<CapturePacket>,
    /** How many frames the position counter claimed were missing. Zero on a clean run. */
    val skippedFrames: Long,
    /** Whether those frames were written into [mono] as zeros. */
    val filled: Boolean,
    val format: MixFormat,
    val qpcFrequency: Long
) {
    private val anchor: CapturePacket
        get() = packets.firstOrNull { it.flags and Wasapi.BUFFERFLAGS_TIMESTAMP_ERROR == 0 }
            ?: error("no packet carried a usable timestamp")

    /**
     * When the engine counted the frame at [index], on the QPC clock.
     *
     * Read off the first usable packet and counted forward at the nominal rate rather than fitted
     * across the run. Deliberate: a fit would absorb a real rate error into the answer, and
     * whether one exists is a thing to report rather than to remove. [stampResidualTicks] is what
     * says how far the two disagree.
     */
    fun qpcAt(index: Int): Long {
        val a = anchor
        val ahead = (index - a.atIndex).toLong()
        return a.qpcPosition + Math.round(ahead.toDouble() / format.sampleRate * qpcFrequency)
    }

    /** The inverse of [qpcAt]: which sample of [mono] the engine counted at [qpc]. */
    fun indexAt(qpc: Long): Int {
        val a = anchor
        val ahead = qpc - a.qpcPosition
        return a.atIndex + Math.round(ahead.toDouble() / qpcFrequency * format.sampleRate).toInt()
    }

    /**
     * The same as [qpcAt], with the line fitted across every packet instead of pinned to the first.
     *
     * The two differ by however wrong one reading is, which is the only way to tell an unstable
     * anchor from an unstable stream. Nothing in the product should need this; an experiment
     * deciding which reading to trust does.
     */
    fun fittedQpcAt(index: Int): Long {
        val usable = packets.filter { it.flags and Wasapi.BUFFERFLAGS_TIMESTAMP_ERROR == 0 }
        val n = usable.size.toDouble()
        val mx = usable.sumOf { it.atIndex.toDouble() } / n
        val my = usable.sumOf { it.qpcPosition.toDouble() } / n
        var sxx = 0.0
        var sxy = 0.0
        for (p in usable) {
            val dx = p.atIndex - mx
            sxx += dx * dx
            sxy += dx * (p.qpcPosition - my)
        }
        val slope = sxy / sxx
        return Math.round(my + slope * (index - mx))
    }

    /**
     * How far every packet's own stamp sits from where [qpcAt] puts it, in ticks.
     *
     * The capture side's version of the residual the render side is judged by. A recording whose
     * packets scatter by more than the render stream's does is not a ruler anything should be
     * measured against.
     */
    fun stampResidualTicks(): DoubleArray = packets
        .filter { it.flags and Wasapi.BUFFERFLAGS_TIMESTAMP_ERROR == 0 }
        .map { (it.qpcPosition - qpcAt(it.atIndex)).toDouble() }
        .toDoubleArray()

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * The machine's ear, in either of the two places it can be put.
 *
 * `loopback = true` opens the *render* endpoint and hands back the mix the engine is about to give
 * the hardware: the same samples that were scheduled, with no speaker, no air and no microphone in
 * between. That is the arrangement in which the right answer is known before the run, which is the
 * only kind of check this layer can be held to on one machine.
 *
 * `loopback = false` opens the default capture endpoint - a real microphone, with a room in front
 * of it. Nothing here knows the difference; everything downstream does, because only one of the two
 * carries an unknown constant.
 */
class WasapiCapture(
    private val loopback: Boolean = false,
    bufferMillis: Long = 2_000L,
    /**
     * Whether a jump in the engine's device position counter is written into the recording as that
     * many zeros.
     *
     * False, which is the opposite of what this class was first written to do, and the change was
     * forced by a criterion rather than chosen. Filling is right only when a jump means frames
     * were really lost. On this endpoint's loopback stream the jumps are one to fifty frames,
     * arrive a few times a second, and are not lost audio: filling them pushed everything after
     * each one later, so four chirps 38400 frames apart came back 38400, 38431 and 38430 apart,
     * and the chirp a fill landed inside correlated 44% weaker than its neighbours.
     *
     * [Recording.skippedFrames] still reports what the counter claimed, because a jump of a whole
     * packet would be a different thing from these and should not be silently discarded too.
     */
    private val fillGaps: Boolean = false
) : AutoCloseable {

    private val arena: Arena = Arena.ofShared()
    private val out: MemorySegment = arena.allocate(8, 8)
    private val out2: MemorySegment = arena.allocate(8, 8)
    private val data: MemorySegment = arena.allocate(8, 8)
    private val frames: MemorySegment = arena.allocate(8, 8)
    private val flags: MemorySegment = arena.allocate(8, 8)
    private val devicePosition: MemorySegment = arena.allocate(8, 8)
    private val qpcPosition: MemorySegment = arena.allocate(8, 8)

    private val client: MemorySegment
    private val capture: MemorySegment

    val format: MixFormat
    val qpcFrequency: Long
    val bufferFrames: Int

    private val lock = Any()
    private var samples = ShortArray(INITIAL_CAPACITY)
    private var count = 0
    private var firstDevicePosition = -1L
    private var nextExpected = -1L
    private var skipped = 0L
    private val packets = ArrayList<CapturePacket>()

    @Volatile private var running = false
    private var reader: Thread? = null

    init {
        Wasapi.coInitialize()

        val enumerator = run {
            Wasapi.check(
                Wasapi.coCreateInstance(
                    Wasapi.guid(arena, Wasapi.CLSID_MM_DEVICE_ENUMERATOR),
                    Wasapi.guid(arena, Wasapi.IID_IMM_DEVICE_ENUMERATOR),
                    out
                ),
                "CoCreateInstance(MMDeviceEnumerator)"
            )
            out.get(Wasapi.PTR, 0)
        }

        // Loopback listens on the render endpoint. Asking for the capture endpoint and setting the
        // flag would open the microphone and quietly return the microphone's own idea of a mix.
        val dataFlow = if (loopback) Wasapi.DATAFLOW_RENDER else Wasapi.DATAFLOW_CAPTURE
        val device = run {
            Wasapi.check(
                Wasapi.getDefaultAudioEndpoint(enumerator, dataFlow, Wasapi.ROLE_MULTIMEDIA, out),
                "GetDefaultAudioEndpoint"
            )
            out.get(Wasapi.PTR, 0)
        }

        Wasapi.check(
            Wasapi.activate(device, Wasapi.guid(arena, Wasapi.IID_IAUDIO_CLIENT), out),
            "Activate(IAudioClient)"
        )
        client = out.get(Wasapi.PTR, 0)

        Wasapi.check(Wasapi.getMixFormat(client, out), "GetMixFormat")
        val mix = out.get(Wasapi.PTR, 0)
        format = readWaveFormat(mix)

        Wasapi.check(
            Wasapi.initialize(
                client,
                Wasapi.SHARE_MODE_SHARED,
                if (loopback) Wasapi.STREAMFLAGS_LOOPBACK else 0,
                bufferMillis * 10_000L,
                0,
                mix
            ),
            "Initialize"
        )
        Wasapi.coTaskMemFree(mix)

        Wasapi.check(Wasapi.getBufferSize(client, out), "GetBufferSize")
        bufferFrames = out.get(Wasapi.I32, 0)

        Wasapi.check(
            Wasapi.getService(client, Wasapi.guid(arena, Wasapi.IID_IAUDIO_CAPTURE_CLIENT), out),
            "GetService(IAudioCaptureClient)"
        )
        capture = out.get(Wasapi.PTR, 0)

        qpcFrequency = Wasapi.qpcFrequency(out2)
    }

    fun start() {
        check(!running) { "already started" }
        running = true
        Wasapi.check(Wasapi.start(client), "Start")
        reader = Thread(::readerLoop, "wasapi-capture").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running) return
        running = false
        reader?.join(2000)
        reader = null
        Wasapi.stop(client)
    }

    /** Everything heard so far, as one array with its timeline attached. Safe to call while running. */
    fun take(): Recording = synchronized(lock) {
        check(firstDevicePosition >= 0) { "nothing was captured" }
        Recording(
            mono = samples.copyOf(count),
            firstDevicePosition = firstDevicePosition,
            packets = ArrayList(packets),
            skippedFrames = skipped,
            filled = fillGaps,
            format = format,
            qpcFrequency = qpcFrequency
        )
    }

    override fun close() {
        stop()
        arena.close()
    }

    // --------------------------------------------------------------------- reader

    private fun readerLoop() {
        Wasapi.coInitialize()
        while (running) {
            // Drained to empty each pass: one GetBuffer hands over one packet, and a loop that
            // took a single packet per sleep would fall behind whenever the engine delivered two.
            while (running && drainOne()) Unit
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        // One last pass, because stop() is asked for after the interesting part has been played
        // and the tail of it is still sitting in the engine's buffer.
        while (drainOne()) Unit
    }

    /** Takes one packet if there is one. Returns whether there might be another behind it. */
    private fun drainOne(): Boolean {
        val hr = Wasapi.captureGetBuffer(capture, data, frames, flags, devicePosition, qpcPosition)
        if (hr == Wasapi.S_BUFFER_EMPTY) return false
        if (hr < 0) return false
        val frameCount = frames.get(Wasapi.I32, 0)
        if (frameCount <= 0) {
            Wasapi.captureReleaseBuffer(capture, frameCount)
            return false
        }
        val packetFlags = flags.get(Wasapi.I32, 0)
        val position = devicePosition.get(Wasapi.I64, 0)
        // The API reports in hundred-nanosecond units, not in the counter's own ticks. On this
        // machine the frequency happens to be 10 MHz and the two are equal, which is exactly the
        // kind of coincidence that hides a missing conversion until the code meets another
        // machine.
        val stamp = hnsToTicks(qpcPosition.get(Wasapi.I64, 0))
        val silent = packetFlags and Wasapi.BUFFERFLAGS_SILENT != 0

        synchronized(lock) {
            if (firstDevicePosition < 0) {
                firstDevicePosition = position
                nextExpected = position
            }
            if (position > nextExpected) {
                val gap = position - nextExpected
                skipped += gap
                if (fillGaps) appendZeros(gap.toInt())
            }
            val atIndex = count
            if (silent) {
                appendZeros(frameCount)
            } else {
                val block = data.get(Wasapi.PTR, 0)
                    .reinterpret(frameCount.toLong() * format.blockAlign)
                appendFrames(block, frameCount)
            }
            nextExpected = position + frameCount
            packets.add(CapturePacket(position, stamp, frameCount, packetFlags, atIndex))
        }
        Wasapi.captureReleaseBuffer(capture, frameCount)
        return true
    }

    private fun hnsToTicks(hns: Long): Long =
        if (qpcFrequency == HNS_PER_SECOND) hns
        else Math.round(hns.toDouble() / HNS_PER_SECOND * qpcFrequency)

    /**
     * Appends [frameCount] frames, averaged down to one channel.
     *
     * Averaged rather than taking the first channel, because a real capture endpoint is often a
     * two-microphone array and one of the two can sit behind the screen hinge. The renderer puts
     * the same samples on every channel, so on loopback the average is that signal unchanged.
     */
    private fun appendFrames(block: MemorySegment, frameCount: Int) {
        val channels = format.channels
        ensure(count + frameCount)
        for (frame in 0 until frameCount) {
            var total = 0.0
            for (channel in 0 until channels) {
                val slot = (frame.toLong() * channels + channel)
                total += if (format.isFloat) {
                    block.get(Wasapi.F32, slot * 4).toDouble() * Short.MAX_VALUE
                } else {
                    block.get(Wasapi.I16, slot * 2).toDouble()
                }
            }
            val value = total / channels
            samples[count + frame] = value.coerceIn(MIN_SAMPLE, MAX_SAMPLE).toInt().toShort()
        }
        count += frameCount
    }

    private fun appendZeros(frameCount: Int) {
        ensure(count + frameCount)
        java.util.Arrays.fill(samples, count, count + frameCount, 0.toShort())
        count += frameCount
    }

    private fun ensure(needed: Int) {
        if (needed <= samples.size) return
        var size = samples.size
        while (size < needed) size *= 2
        samples = samples.copyOf(size)
    }

    private companion object {
        /** A second at 48 kHz, doubled from here; a recording is minutes at most. */
        const val INITIAL_CAPACITY = 48_000

        /** Well inside the engine's default period, so no packet waits a period to be noticed. */
        const val POLL_MILLIS = 5L

        const val HNS_PER_SECOND = 10_000_000L
        const val MIN_SAMPLE = -32768.0
        const val MAX_SAMPLE = 32767.0
    }
}
