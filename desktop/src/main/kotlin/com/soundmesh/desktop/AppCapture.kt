package com.soundmesh.desktop

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/** A capture as [HostSession] drives it: [AppCapture], or a test's stand-in for one. */
interface CaptureHandle : AutoCloseable {
    /** What every sample is multiplied by on the way out. */
    var gain: Float

    fun start()
}

/**
 * One program's sound, and whatever it started, before the engine mixes it with anybody else's.
 *
 * The desktop's counterpart of the handset capturing another app's playback. Capturing the
 * endpoint's mix instead would take this program's own output back in with it - the room's copy,
 * a second and a half late, fed back into the room - and it would take every notification with it.
 *
 * Asked for at the stream's rate and channels in 32-bit float, and handed on as the 16-bit frames a
 * chunk carries after [gain] is applied. Float because of what [gain] is for: the capture hears
 * the program after its row in the mixer, so a program turned down there to keep its own copy off
 * the speakers is heard turned down, and [gain] undoes that. Measured: a row at 1% came back
 * exactly 40 dB down. In 16 bits that undoing would cost the bits it had to restore; in float the
 * attenuation is only an exponent. The engine converts to whatever is asked: this kind of client
 * has no mix format of its own.
 *
 * [onPcm] is called on the reader thread with interleaved frames, and the array is reused after
 * it returns.
 */
class AppCapture(
    val pid: Long,
    bufferMillis: Long = 200L,
    private val onPcm: (bytes: ByteArray, length: Int) -> Unit
) : CaptureHandle {

    @Volatile override var gain: Float = 1f

    private val arena: Arena = Arena.ofShared()
    private val out: MemorySegment = arena.allocate(8, 8)
    private val data: MemorySegment = arena.allocate(8, 8)
    private val frames: MemorySegment = arena.allocate(8, 8)
    private val flags: MemorySegment = arena.allocate(8, 8)
    private val devicePosition: MemorySegment = arena.allocate(8, 8)
    private val qpcPosition: MemorySegment = arena.allocate(8, 8)

    private val client: MemorySegment
    private val capture: MemorySegment

    @Volatile private var running = false
    private var reader: Thread? = null
    private var scratch = ByteArray(0)

    /** Frames handed to [onPcm] so far, silence included. */
    @Volatile var framesDelivered = 0L
        private set

    /** Packets the engine marked silent: the program had a stream open and nothing in it. */
    @Volatile var silentPackets = 0L
        private set

    init {
        Wasapi.coInitialize()
        Wasapi.check(Wasapi.activateProcessLoopback(arena, pid, out), "ActivateAudioInterfaceAsync(process $pid)")
        client = out.get(Wasapi.PTR, 0)

        // WAVEFORMATEX, float: tag, channels, rate, bytes a second, block, bits, extra size.
        val format = arena.allocate(20, 4)
        format.set(Wasapi.I16, 0, Wasapi.WAVE_FORMAT_IEEE_FLOAT.toShort())
        format.set(Wasapi.I16, 2, CHANNELS.toShort())
        format.set(Wasapi.I32, 4, SAMPLE_RATE)
        format.set(Wasapi.I32, 8, SAMPLE_RATE * FLOAT_FRAME_BYTES)
        format.set(Wasapi.I16, 12, FLOAT_FRAME_BYTES.toShort())
        format.set(Wasapi.I16, 14, 32.toShort())
        format.set(Wasapi.I16, 16, 0.toShort())

        Wasapi.check(
            Wasapi.initialize(
                client,
                Wasapi.SHARE_MODE_SHARED,
                Wasapi.STREAMFLAGS_LOOPBACK or STREAMFLAGS_AUTOCONVERTPCM or STREAMFLAGS_SRC_DEFAULT_QUALITY,
                bufferMillis * 10_000L,
                0,
                format
            ),
            "Initialize(process loopback)"
        )
        Wasapi.check(
            Wasapi.getService(client, Wasapi.guid(arena, Wasapi.IID_IAUDIO_CAPTURE_CLIENT), out),
            "GetService(IAudioCaptureClient)"
        )
        capture = out.get(Wasapi.PTR, 0)
    }

    override fun start() {
        check(!running) { "already started" }
        running = true
        Wasapi.check(Wasapi.start(client), "Start")
        reader = Thread(::readerLoop, "app-capture").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running) return
        running = false
        reader?.join(2000)
        reader = null
        Wasapi.stop(client)
    }

    override fun close() {
        stop()
        Wasapi.release(capture)
        Wasapi.release(client)
        arena.close()
    }

    private fun readerLoop() {
        Wasapi.coInitialize()
        while (running) {
            while (running && drainOne()) Unit
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun drainOne(): Boolean {
        val hr = Wasapi.captureGetBuffer(capture, data, frames, flags, devicePosition, qpcPosition)
        if (hr == Wasapi.S_BUFFER_EMPTY || hr < 0) return false
        val frameCount = frames.get(Wasapi.I32, 0)
        if (frameCount <= 0) {
            Wasapi.captureReleaseBuffer(capture, frameCount)
            return false
        }
        val length = frameCount * BYTES_PER_FRAME
        if (scratch.size < length) scratch = ByteArray(length)
        if (flags.get(Wasapi.I32, 0) and Wasapi.BUFFERFLAGS_SILENT != 0) {
            java.util.Arrays.fill(scratch, 0, length, 0)
            silentPackets++
        } else {
            val block = data.get(Wasapi.PTR, 0).reinterpret(frameCount.toLong() * FLOAT_FRAME_BYTES)
            val scale = gain * Short.MAX_VALUE
            for (i in 0 until frameCount * CHANNELS) {
                val s = Math.round((block.get(Wasapi.F32, i * 4L) * scale).coerceIn(-32768f, 32767f))
                scratch[i * 2] = s.toByte()
                scratch[i * 2 + 1] = (s shr 8).toByte()
            }
        }
        Wasapi.captureReleaseBuffer(capture, frameCount)
        framesDelivered += frameCount
        onPcm(scratch, length)
        return true
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * 2

        private const val FLOAT_FRAME_BYTES = CHANNELS * 4
        private const val STREAMFLAGS_AUTOCONVERTPCM = 0x80000000.toInt()
        private const val STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x08000000
        private const val POLL_MILLIS = 5L
    }
}
