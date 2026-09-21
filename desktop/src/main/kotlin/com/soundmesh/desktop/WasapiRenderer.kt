package com.soundmesh.desktop

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * One reading of IAudioClock::GetPosition, with the call bracketed by the system clock.
 *
 * [qpcBefore] and [qpcAfter] are when the call was made, not when the stamp was taken; the
 * engine's own [qpcPosition] is what says when [frames] was true. Keeping all three is what lets
 * a reading be thrown out later for having taken too long, and what caught the sampler's own
 * artefact in section 6 of windows-audio-clock.md.
 */
data class ClockSample(
    val qpcBefore: Long,
    val qpcAfter: Long,
    /** Exactly what the device reported, in the clock's own units - bytes on this endpoint. */
    val position: Long,
    /** [position] in frames, which is the unit everything above this class works in. */
    val frames: Long,
    val qpcPosition: Long
)

/**
 * The default output device, opened for playback, with a timeline in frames.
 *
 * What this is for: everything the Windows client has to do eventually reduces to "put this
 * sound out at this moment, and say when it went". [schedule] is the first half, [sampleClock]
 * the second. The frame index is the stream's own, counting from the first frame the engine
 * consumes after [start].
 *
 * What it is NOT: [sampleClock] says when the engine consumed a frame, which is not when the air
 * moved. The distance between those two is the output delay constant, it is a property of this
 * machine and its endpoint, and nothing on this side of the speaker can measure it - it takes a
 * microphone in the room. Treating a position stamp as an emission time is the whole mistake this
 * comment exists to prevent.
 *
 * Shared mode, 200 ms of buffer, and a writer thread on a 10 ms loop: the same arrangement the
 * probe used for the eight arms of experiment four, so the numbers this produces are comparable
 * with the ones already archived.
 */
class WasapiRenderer(bufferMillis: Long = 200L) : AutoCloseable {

    // Shared rather than confined: the writer thread touches these, and a confined arena would
    // refuse it. Two sets, because the clock reader must not queue behind the writer - see
    // [sampleClock].
    private val arena: Arena = Arena.ofShared()
    private val out: MemorySegment = arena.allocate(8, 8)
    private val out2: MemorySegment = arena.allocate(8, 8)
    private val clockA: MemorySegment = arena.allocate(8, 8)
    private val clockB: MemorySegment = arena.allocate(8, 8)
    private val clockC: MemorySegment = arena.allocate(8, 8)

    private val client: MemorySegment
    private val render: MemorySegment
    private val clock: MemorySegment

    val format: MixFormat
    val bufferFrames: Int
    val clockFrequency: Long
    val qpcFrequency: Long
    val streamLatencyHns: Long
    val defaultPeriodHns: Long
    val minimumPeriodHns: Long

    /** Absolute index of the next frame the writer will hand over. Guarded by [lock]. */
    private var written: Long = 0
    private val clips = ArrayList<Clip>()
    private val lock = Any()

    @Volatile private var running = false
    private var writer: Thread? = null

    private class Clip(val startFrame: Long, val mono: ShortArray) {
        val endFrame: Long get() = startFrame + mono.size
    }

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

        val device = run {
            Wasapi.check(
                Wasapi.getDefaultAudioEndpoint(
                    enumerator, Wasapi.DATAFLOW_RENDER, Wasapi.ROLE_MULTIMEDIA, out
                ),
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

        Wasapi.check(Wasapi.getDevicePeriod(client, out, out2), "GetDevicePeriod")
        defaultPeriodHns = out.get(Wasapi.I64, 0)
        minimumPeriodHns = out2.get(Wasapi.I64, 0)

        Wasapi.check(
            Wasapi.initialize(
                client, Wasapi.SHARE_MODE_SHARED, 0, bufferMillis * 10_000L, 0, mix
            ),
            "Initialize"
        )
        // The engine copied the format; the memory it handed over is ours to give back.
        Wasapi.coTaskMemFree(mix)

        Wasapi.check(Wasapi.getBufferSize(client, out), "GetBufferSize")
        bufferFrames = out.get(Wasapi.I32, 0)
        Wasapi.check(Wasapi.getStreamLatency(client, out), "GetStreamLatency")
        streamLatencyHns = out.get(Wasapi.I64, 0)

        render = service(Wasapi.IID_IAUDIO_RENDER_CLIENT)
        clock = service(Wasapi.IID_IAUDIO_CLOCK)

        Wasapi.check(Wasapi.getFrequency(clock, out), "GetFrequency")
        clockFrequency = out.get(Wasapi.I64, 0)
        qpcFrequency = Wasapi.qpcFrequency(out)
    }

    private fun service(iid: String): MemorySegment {
        Wasapi.check(Wasapi.getService(client, Wasapi.guid(arena, iid), out), "GetService($iid)")
        return out.get(Wasapi.PTR, 0)
    }

    // ------------------------------------------------------------------ timeline

    /** Ticks now, on the same clock the engine stamps positions with. Sampler thread only. */
    fun now(): Long = Wasapi.qpc(clockC)

    /** The absolute index of the next frame not yet handed to the engine. */
    fun framesWritten(): Long = synchronized(lock) { written }

    /**
     * Puts [mono] on the timeline starting at [atFrame], resampled to nothing and mixed with
     * nothing: it is written to every channel as it stands, because a chirp that a room has to
     * agree on is the same signal everywhere.
     *
     * Throws if [atFrame] is already behind the writer. Silently starting late would be the worst
     * of the available behaviours - the sound would come out, and the only sign that it came out
     * at the wrong moment would be in the measurement it was supposed to produce.
     */
    fun schedule(mono: ShortArray, atFrame: Long) {
        synchronized(lock) {
            require(atFrame >= written) {
                "frame $atFrame is ${written - atFrame} frames behind what has already been written"
            }
            clips.add(Clip(atFrame, mono))
        }
    }

    /**
     * Which frame the engine will be consuming at [qpcDeadline], given a fresh clock reading.
     *
     * The arithmetic is only as good as the reading, so callers that care take the reading
     * themselves and keep it: this is a convenience, not a measurement.
     */
    fun frameAt(qpcDeadline: Long, sample: ClockSample): Long {
        val ahead = qpcDeadline - sample.qpcPosition
        return sample.frames + Math.round(ahead.toDouble() / qpcFrequency * format.sampleRate)
    }

    /** The inverse of [frameAt]: when the engine will be consuming [frame]. */
    fun qpcAt(frame: Long, sample: ClockSample): Long {
        val ahead = frame - sample.frames
        return sample.qpcPosition + Math.round(ahead.toDouble() / format.sampleRate * qpcFrequency)
    }

    // --------------------------------------------------------------------- clock

    /**
     * Reads the engine's clock. One sampler thread, and deliberately outside [lock].
     *
     * The writer holds that lock across GetBuffer / fill / ReleaseBuffer, so a sampler that took
     * it would be waiting on the writer some of the time and not others - which is a sampling
     * interval that depends on the thing being sampled. The probe's sampler ran free of its
     * writer, and these numbers are meant to be comparable with the probe's.
     */
    fun sampleClock(): ClockSample {
        val before = Wasapi.qpc(clockA)
        val hr = Wasapi.getPosition(clock, clockB, clockC)
        val after = Wasapi.qpc(clockA)
        Wasapi.check(hr, "GetPosition")
        val position = clockB.get(Wasapi.I64, 0)
        return ClockSample(
            qpcBefore = before,
            qpcAfter = after,
            position = position,
            // Position counts whatever the clock's frequency says it counts - frames when that
            // frequency is the sample rate, bytes when it is the byte rate. Dividing makes the
            // caller's unit frames either way.
            frames = position * format.sampleRate / clockFrequency,
            qpcPosition = clockC.get(Wasapi.I64, 0)
        )
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Starts the stream, and does not return until the engine is really consuming.
     *
     * Start() returns before that happens. A clock reading taken in the gap says position 0 at a
     * tick that is not when frame 0 will be played, and anything scheduled against it is late by
     * however long the gap was - measured here at 12.9 ms, stable to a microsecond across three
     * chirps, which is thirteen times the whole alignment budget and completely silent.
     *
     * Waiting here rather than writing the rule in a comment: a caller cannot take a reading too
     * early if there is no moment at which the object is started and not yet running.
     */
    fun start() {
        check(!running) { "already started" }
        // Pre-roll, so the stream begins with data in it rather than with an underrun.
        writeAvailable()
        running = true
        writer = Thread(::writerLoop, "wasapi-writer").apply {
            isDaemon = true
            start()
        }
        Wasapi.check(Wasapi.start(client), "Start")

        val giveUpAt = System.nanoTime() + 2_000_000_000L
        while (sampleClock().frames == 0L) {
            check(System.nanoTime() < giveUpAt) { "the stream never began advancing" }
            Thread.sleep(1)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        writer?.join(2000)
        writer = null
        Wasapi.stop(client)
    }

    override fun close() {
        stop()
        arena.close()
    }

    // --------------------------------------------------------------------- writer

    private fun writerLoop() {
        Wasapi.coInitialize()
        while (running) {
            writeAvailable()
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun writeAvailable() = synchronized(lock) {
        if (Wasapi.getCurrentPadding(client, out) < 0) return@synchronized
        val free = bufferFrames - out.get(Wasapi.I32, 0)
        // Below a period's worth there is nothing useful to hand over, and asking for a tiny
        // buffer every 10 ms costs more than waiting for the next round.
        if (free < 64) return@synchronized

        if (Wasapi.getBuffer(render, free, out) < 0) return@synchronized
        fill(out.get(Wasapi.PTR, 0), free)
        Wasapi.releaseBuffer(render, free, 0)
        written += free
    }

    /**
     * Fills [frames] of the engine's buffer from the timeline.
     *
     * Silence is written as real zero samples rather than AUDCLNT_BUFFERFLAGS_SILENT, so the
     * engine takes the ordinary path with ordinary data in it. That was deliberate in the probe -
     * a silent flag may permit a shortcut, and a measurement of the shortcut is not a measurement
     * of what the product does - and it stays deliberate here.
     */
    private fun fill(buffer: MemorySegment, frames: Int) {
        val channels = format.channels
        val b = buffer.reinterpret(frames.toLong() * format.blockAlign)
        val base = written

        // Only the clips that reach into this window, and clips already behind it are dropped so
        // the list cannot grow for the length of a session.
        clips.removeAll { it.endFrame <= base }
        val live = clips.filter { it.startFrame < base + frames && it.endFrame > base }

        for (i in 0 until frames) {
            var v = 0
            if (live.isNotEmpty()) {
                val at = base + i
                for (clip in live) {
                    val k = (at - clip.startFrame).toInt()
                    if (k >= 0 && k < clip.mono.size) v += clip.mono[k].toInt()
                }
            }
            for (c in 0 until channels) {
                val slot = (i * channels + c).toLong()
                if (format.isFloat) {
                    b.set(Wasapi.F32, slot * 4, v / 32768.0f)
                } else {
                    b.set(Wasapi.I16, slot * 2, v.coerceIn(-32768, 32767).toShort())
                }
            }
        }
    }
}
