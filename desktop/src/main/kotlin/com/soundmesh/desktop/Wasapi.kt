package com.soundmesh.desktop

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * WASAPI, and the COM around it, called by hand through the foreign function API.
 *
 * A port of the probe at docs/feasibility-results/data/2026-09-21-jvm-wasapi/probe/Wasapi.java,
 * which is where the vtable orders below were established and checked against numbers the C#
 * probe had already measured on this machine. Nothing here is new; what is new is that it lives
 * in the build instead of beside an experiment.
 *
 * This is the only place in the module that knows what a vtable is. Everything above it works in
 * frames and ticks.
 */
internal object Wasapi {

    private val LINKER: Linker = Linker.nativeLinker()
    private val LIBS: Arena = Arena.global()

    // Left to inference: ADDRESS is an AddressLayout from 22 on and a ValueLayout.OfAddress in
    // 21's preview, and spelling either one out pins this file to one of those worlds.
    val PTR = ValueLayout.ADDRESS
    val I16 = ValueLayout.JAVA_SHORT
    val I32 = ValueLayout.JAVA_INT
    val I64 = ValueLayout.JAVA_LONG
    val F32 = ValueLayout.JAVA_FLOAT

    private fun export(library: String, name: String, fd: FunctionDescriptor): MethodHandle =
        LINKER.downcallHandle(
            SymbolLookup.libraryLookup(library, LIBS).find(name)
                .orElseThrow { IllegalStateException("$library has no $name") },
            fd
        )

    private val CO_INITIALIZE_EX =
        export("ole32.dll", "CoInitializeEx", FunctionDescriptor.of(I32, PTR, I32))
    private val CO_CREATE_INSTANCE =
        export("ole32.dll", "CoCreateInstance", FunctionDescriptor.of(I32, PTR, PTR, I32, PTR, PTR))
    private val CO_TASK_MEM_FREE =
        export("ole32.dll", "CoTaskMemFree", FunctionDescriptor.ofVoid(PTR))
    private val QUERY_PERFORMANCE_COUNTER =
        export("kernel32.dll", "QueryPerformanceCounter", FunctionDescriptor.of(I32, PTR))
    private val QUERY_PERFORMANCE_FREQUENCY =
        export("kernel32.dll", "QueryPerformanceFrequency", FunctionDescriptor.of(I32, PTR))
    private val TIME_BEGIN_PERIOD =
        export("winmm.dll", "timeBeginPeriod", FunctionDescriptor.of(I32, I32))
    private val TIME_END_PERIOD =
        export("winmm.dll", "timeEndPeriod", FunctionDescriptor.of(I32, I32))

    // ------------------------------------------------------------------ vtable calls
    //
    // downcallHandle(descriptor) with no symbol returns a handle whose first argument is the
    // address to call. That is the whole mechanism: one handle per signature shape, reused for
    // every method that has that shape.

    /** HRESULT f(this) */
    private val CALL_V = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR))

    /** HRESULT f(this, out*) */
    private val CALL_P = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, PTR))

    /** HRESULT f(this, a*, b*) */
    private val CALL_PP = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, PTR, PTR))

    /** HRESULT GetDefaultAudioEndpoint(this, dataFlow, role, out*) */
    private val CALL_IIP = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, I32, I32, PTR))

    /** HRESULT Activate(this, iid*, clsCtx, params*, out*) */
    private val CALL_ACTIVATE =
        LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, PTR, I32, PTR, PTR))

    /** HRESULT Initialize(this, shareMode, flags, bufferHns, periodicityHns, format*, sessionGuid*) */
    private val CALL_INITIALIZE =
        LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, I32, I32, I64, I64, PTR, PTR))

    /** HRESULT GetBuffer(this, frames, out*) */
    private val CALL_GET_BUFFER = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, I32, PTR))

    /** HRESULT ReleaseBuffer(this, frames, flags) */
    private val CALL_RELEASE_BUFFER =
        LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, I32, I32))

    /**
     * The index-th function pointer in obj's vtable.
     *
     * A COM interface pointer points at a pointer to the vtable, so this is two dereferences.
     * Indices 0..2 are IUnknown's QueryInterface / AddRef / Release on every interface, which is
     * why every table below starts at 3.
     */
    private fun method(obj: MemorySegment, index: Int): MemorySegment {
        val vtbl = obj.reinterpret(8).get(PTR, 0)
        return vtbl.reinterpret((index + 1) * 8L).get(PTR, index * 8L)
    }

    private fun v(self: MemorySegment, index: Int): Int =
        CALL_V.invokeExact(method(self, index), self) as Int

    private fun p(self: MemorySegment, index: Int, a: MemorySegment): Int =
        CALL_P.invokeExact(method(self, index), self, a) as Int

    private fun pp(self: MemorySegment, index: Int, a: MemorySegment, b: MemorySegment): Int =
        CALL_PP.invokeExact(method(self, index), self, a, b) as Int

    // ------------------------------------------------------------------------- ids

    const val CLSID_MM_DEVICE_ENUMERATOR = "BCDE0395-E52F-467C-8E3D-C4579291692E"
    const val IID_IMM_DEVICE_ENUMERATOR = "A95664D2-9614-4F35-A746-DE8DB63617E6"
    const val IID_IAUDIO_CLIENT = "1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"
    const val IID_IAUDIO_RENDER_CLIENT = "F294ACFC-3146-4483-A7BF-ADDCA7C260E2"
    const val IID_IAUDIO_CLOCK = "CD63314F-3FBA-4A1B-812C-EF96358728E7"
    const val SUBTYPE_FLOAT = "00000003-0000-0010-8000-00AA00389B71"

    const val CLSCTX_ALL = 23
    const val SHARE_MODE_SHARED = 0
    const val DATAFLOW_RENDER = 0

    /**
     * eMultimedia. Not eCommunications, whose default on this machine is the monitor over HDMI -
     * picking the wrong role lands on a different device silently, and the symptom is that the
     * sound is somewhere else.
     */
    const val ROLE_MULTIMEDIA = 1

    const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    const val WAVE_FORMAT_IEEE_FLOAT = 3

    // ----------------------------------------------------------------------- time

    fun coInitialize() {
        // S_FALSE (1) means this thread was already initialised, which is not a failure.
        val hr = CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, 0) as Int
        check(hr >= 0) { "CoInitializeEx failed: ${hex(hr)}" }
    }

    fun qpc(scratch: MemorySegment): Long {
        val ok = QUERY_PERFORMANCE_COUNTER.invokeExact(scratch) as Int
        check(ok != 0) { "QueryPerformanceCounter failed" }
        return scratch.get(I64, 0)
    }

    fun qpcFrequency(scratch: MemorySegment): Long {
        val ok = QUERY_PERFORMANCE_FREQUENCY.invokeExact(scratch) as Int
        check(ok != 0) { "QueryPerformanceFrequency failed" }
        return scratch.get(I64, 0)
    }

    /**
     * Without this, a one-millisecond sleep waits for the default 15.625 ms tick.
     * Section 6 of windows-audio-clock.md is the whole story: skipping it produced a
     * clean-looking result that was entirely an artefact of the sampler.
     */
    fun timeBeginPeriod(ms: Int) {
        @Suppress("UNUSED_VARIABLE")
        val ignored = TIME_BEGIN_PERIOD.invokeExact(ms) as Int
    }

    fun timeEndPeriod(ms: Int) {
        @Suppress("UNUSED_VARIABLE")
        val ignored = TIME_END_PERIOD.invokeExact(ms) as Int
    }

    fun coTaskMemFree(p: MemorySegment) {
        CO_TASK_MEM_FREE.invokeExact(p)
    }

    // -------------------------------------------------------------------- objects

    fun coCreateInstance(clsid: MemorySegment, iid: MemorySegment, out: MemorySegment): Int =
        CO_CREATE_INSTANCE.invokeExact(clsid, MemorySegment.NULL, CLSCTX_ALL, iid, out) as Int

    /** IMMDeviceEnumerator::GetDefaultAudioEndpoint, vtable 4. */
    fun getDefaultAudioEndpoint(
        self: MemorySegment,
        dataFlow: Int,
        role: Int,
        out: MemorySegment
    ): Int = CALL_IIP.invokeExact(method(self, 4), self, dataFlow, role, out) as Int

    /** IMMDevice::Activate, vtable 3. */
    fun activate(self: MemorySegment, iid: MemorySegment, out: MemorySegment): Int =
        CALL_ACTIVATE.invokeExact(
            method(self, 3), self, iid, CLSCTX_ALL, MemorySegment.NULL, out
        ) as Int

    // --------------------------------------------------------------- IAudioClient
    //
    // Initialize 3, GetBufferSize 4, GetStreamLatency 5, GetCurrentPadding 6,
    // IsFormatSupported 7, GetMixFormat 8, GetDevicePeriod 9, Start 10, Stop 11,
    // Reset 12, SetEventHandle 13, GetService 14.

    fun initialize(
        self: MemorySegment,
        shareMode: Int,
        streamFlags: Int,
        bufferHns: Long,
        periodicityHns: Long,
        format: MemorySegment
    ): Int = CALL_INITIALIZE.invokeExact(
        method(self, 3), self, shareMode, streamFlags, bufferHns, periodicityHns,
        format, MemorySegment.NULL
    ) as Int

    fun getBufferSize(self: MemorySegment, outFrames: MemorySegment): Int = p(self, 4, outFrames)

    fun getStreamLatency(self: MemorySegment, outHns: MemorySegment): Int = p(self, 5, outHns)

    fun getCurrentPadding(self: MemorySegment, outFrames: MemorySegment): Int = p(self, 6, outFrames)

    fun getMixFormat(self: MemorySegment, outFormat: MemorySegment): Int = p(self, 8, outFormat)

    fun getDevicePeriod(self: MemorySegment, outDefault: MemorySegment, outMin: MemorySegment): Int =
        pp(self, 9, outDefault, outMin)

    fun start(self: MemorySegment): Int = v(self, 10)

    fun stop(self: MemorySegment): Int = v(self, 11)

    fun getService(self: MemorySegment, iid: MemorySegment, out: MemorySegment): Int =
        pp(self, 14, iid, out)

    // --------------------------------------------------------- IAudioRenderClient

    fun getBuffer(self: MemorySegment, frames: Int, outData: MemorySegment): Int =
        CALL_GET_BUFFER.invokeExact(method(self, 3), self, frames, outData) as Int

    fun releaseBuffer(self: MemorySegment, frames: Int, flags: Int): Int =
        CALL_RELEASE_BUFFER.invokeExact(method(self, 4), self, frames, flags) as Int

    // ---------------------------------------------------------------- IAudioClock

    fun getFrequency(self: MemorySegment, out: MemorySegment): Int = p(self, 3, out)

    fun getPosition(self: MemorySegment, outPosition: MemorySegment, outQpc: MemorySegment): Int =
        pp(self, 4, outPosition, outQpc)

    // ------------------------------------------------------------------- plumbing

    fun check(hr: Int, what: String) {
        if (hr < 0) throw IllegalStateException("$what failed: ${hex(hr)}")
    }

    fun hex(hr: Int): String = "0x" + "%08x".format(hr)

    /** First three fields little-endian, last eight bytes in written order. */
    fun guid(arena: Arena, text: String): MemorySegment {
        val h = text.replace("-", "")
        require(h.length == 32) { text }
        val g = arena.allocate(16, 4)
        g.set(I32, 0, h.substring(0, 8).toLong(16).toInt())
        g.set(I16, 4, h.substring(8, 12).toInt(16).toShort())
        g.set(I16, 6, h.substring(12, 16).toInt(16).toShort())
        for (i in 0 until 8) {
            g.set(ValueLayout.JAVA_BYTE, 8L + i, h.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte())
        }
        return g
    }

    fun guidAt(g: MemorySegment, offset: Long): String = buildString {
        append("%08X-".format(g.get(I32, offset)))
        append("%04X-".format(g.get(I16, offset + 4).toInt() and 0xFFFF))
        append("%04X-".format(g.get(I16, offset + 6).toInt() and 0xFFFF))
        for (i in 0 until 8) {
            append("%02X".format(g.get(ValueLayout.JAVA_BYTE, offset + 8 + i)))
            if (i == 1) append('-')
        }
    }
}

/** What the mix format says, which is what anything rendering here has to match. */
data class MixFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val isFloat: Boolean,
    val blockAlign: Int
) {
    override fun toString() =
        "$sampleRate Hz  $channels ch  $bitsPerSample bit  " +
            (if (isFloat) "float" else "pcm") + "  block=$blockAlign"
}

/**
 * Reads a WAVEFORMATEX(TENSIBLE) the engine handed back.
 *
 * Only float32 and pcm16 are handled, and anything else throws here rather than being written as
 * whatever the bytes happen to mean - a mix format nobody anticipated should be a stopped process,
 * not a noise.
 */
internal fun readWaveFormat(p: MemorySegment): MixFormat {
    val f = p.reinterpret(40)
    val tag = f.get(Wasapi.I16, 0).toInt() and 0xFFFF
    val bits = f.get(Wasapi.I16, 14).toInt() and 0xFFFF
    val isFloat = if (tag == Wasapi.WAVE_FORMAT_EXTENSIBLE) {
        Wasapi.guidAt(f, 24).equals(Wasapi.SUBTYPE_FLOAT, ignoreCase = true)
    } else {
        tag == Wasapi.WAVE_FORMAT_IEEE_FLOAT
    }
    if (!isFloat && bits != 16) {
        throw UnsupportedOperationException(
            "mix format is $bits-bit integer; only float32 and pcm16 are handled"
        )
    }
    return MixFormat(
        sampleRate = f.get(Wasapi.I32, 4),
        channels = f.get(Wasapi.I16, 2).toInt() and 0xFFFF,
        bitsPerSample = bits,
        isFloat = isFloat,
        blockAlign = f.get(Wasapi.I16, 12).toInt() and 0xFFFF
    )
}
