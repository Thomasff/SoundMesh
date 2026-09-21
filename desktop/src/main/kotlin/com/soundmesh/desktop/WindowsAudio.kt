package com.soundmesh.desktop

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * As much of WASAPI as it takes to ask the default output device what format it is in.
 *
 * Deliberately the smallest thing that still exercises the whole mechanism: creating a COM
 * object, dispatching through a vtable, reading out-parameters, and walking a native struct.
 * The full layer - Initialize, the render client, IAudioClock - is in the probe at
 * docs/feasibility-results/data/2026-09-21-jvm-wasapi/probe/ and comes over once there is
 * somewhere for it to live.
 *
 * Nothing here opens a stream, so nothing here makes a sound.
 */
object WindowsAudio {

    private val linker: Linker = Linker.nativeLinker()
    private val libs: Arena = Arena.global()

    private val PTR = ValueLayout.ADDRESS
    private val I32 = ValueLayout.JAVA_INT

    private fun export(library: String, name: String, fd: FunctionDescriptor) =
        linker.downcallHandle(
            SymbolLookup.libraryLookup(library, libs).find(name)
                .orElseThrow { IllegalStateException("$library has no $name") },
            fd
        )

    private val coInitializeEx =
        export("ole32.dll", "CoInitializeEx", FunctionDescriptor.of(I32, PTR, I32))
    private val coCreateInstance =
        export("ole32.dll", "CoCreateInstance", FunctionDescriptor.of(I32, PTR, PTR, I32, PTR, PTR))
    private val queryPerformanceFrequency =
        export("kernel32.dll", "QueryPerformanceFrequency", FunctionDescriptor.of(I32, PTR))

    /** HRESULT f(this, out*) - the shape every call below happens to have. */
    private val callP = linker.downcallHandle(FunctionDescriptor.of(I32, PTR, PTR))
    /** HRESULT Activate(this, iid*, clsCtx, params*, out*) */
    private val callActivate =
        linker.downcallHandle(FunctionDescriptor.of(I32, PTR, PTR, I32, PTR, PTR))
    /** HRESULT GetDefaultAudioEndpoint(this, dataFlow, role, out*) */
    private val callEndpoint =
        linker.downcallHandle(FunctionDescriptor.of(I32, PTR, I32, I32, PTR))

    private const val CLSCTX_ALL = 23
    private const val DATAFLOW_RENDER = 0

    /**
     * eMultimedia. Not eCommunications, whose default on this machine is the monitor over HDMI -
     * picking the wrong role lands on a different device silently, and the symptom is that the
     * sound is somewhere else.
     */
    private const val ROLE_MULTIMEDIA = 1

    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    private const val WAVE_FORMAT_IEEE_FLOAT = 3

    private const val CLSID_MM_DEVICE_ENUMERATOR = "BCDE0395-E52F-467C-8E3D-C4579291692E"
    private const val IID_IMM_DEVICE_ENUMERATOR = "A95664D2-9614-4F35-A746-DE8DB63617E6"
    private const val IID_IAUDIO_CLIENT = "1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"
    private const val SUBTYPE_FLOAT = "00000003-0000-0010-8000-00AA00389B71"

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

    fun qpcFrequency(): Long = Arena.ofConfined().use { arena ->
        val out = arena.allocate(8, 8)
        val ok = queryPerformanceFrequency.invokeExact(out) as Int
        check(ok != 0) { "QueryPerformanceFrequency failed" }
        out.get(ValueLayout.JAVA_LONG, 0)
    }

    fun defaultRenderMixFormat(): MixFormat = Arena.ofConfined().use { arena ->
        val hr = coInitializeEx.invokeExact(MemorySegment.NULL, 0) as Int
        check(hr >= 0) { "CoInitializeEx failed: ${hex(hr)}" }

        val out = arena.allocate(8, 8)
        check(
            coCreateInstance.invokeExact(
                guid(arena, CLSID_MM_DEVICE_ENUMERATOR),
                MemorySegment.NULL,
                CLSCTX_ALL,
                guid(arena, IID_IMM_DEVICE_ENUMERATOR),
                out
            ) as Int >= 0
        ) { "CoCreateInstance(MMDeviceEnumerator) failed" }
        val enumerator = out.get(PTR, 0)

        check(
            callEndpoint.invokeExact(
                method(enumerator, 4), enumerator, DATAFLOW_RENDER, ROLE_MULTIMEDIA, out
            ) as Int >= 0
        ) { "GetDefaultAudioEndpoint failed" }
        val device = out.get(PTR, 0)

        check(
            callActivate.invokeExact(
                method(device, 3), device, guid(arena, IID_IAUDIO_CLIENT),
                CLSCTX_ALL, MemorySegment.NULL, out
            ) as Int >= 0
        ) { "Activate(IAudioClient) failed" }
        val client = out.get(PTR, 0)

        // IAudioClient::GetMixFormat is vtable 8: IUnknown's three, then Initialize,
        // GetBufferSize, GetStreamLatency, GetCurrentPadding, IsFormatSupported.
        check(callP.invokeExact(method(client, 8), client, out) as Int >= 0) {
            "GetMixFormat failed"
        }
        readWaveFormat(out.get(PTR, 0))
    }

    // ------------------------------------------------------------------ plumbing

    /** The index-th function pointer in obj's vtable; a COM pointer points at a pointer to it. */
    private fun method(obj: MemorySegment, index: Int): MemorySegment {
        val vtbl = obj.reinterpret(8).get(PTR, 0)
        return vtbl.reinterpret((index + 1) * 8L).get(PTR, index * 8L)
    }

    private fun readWaveFormat(p: MemorySegment): MixFormat {
        val f = p.reinterpret(40)
        val tag = f.get(ValueLayout.JAVA_SHORT, 0).toInt() and 0xFFFF
        val isFloat = if (tag == WAVE_FORMAT_EXTENSIBLE) {
            guidAt(f, 24).equals(SUBTYPE_FLOAT, ignoreCase = true)
        } else {
            tag == WAVE_FORMAT_IEEE_FLOAT
        }
        return MixFormat(
            sampleRate = f.get(I32, 4),
            channels = f.get(ValueLayout.JAVA_SHORT, 2).toInt() and 0xFFFF,
            bitsPerSample = f.get(ValueLayout.JAVA_SHORT, 14).toInt() and 0xFFFF,
            isFloat = isFloat,
            blockAlign = f.get(ValueLayout.JAVA_SHORT, 12).toInt() and 0xFFFF
        )
    }

    /** First three fields little-endian, last eight bytes in written order. */
    private fun guid(arena: Arena, text: String): MemorySegment {
        val h = text.replace("-", "")
        require(h.length == 32) { text }
        val g = arena.allocate(16, 4)
        g.set(I32, 0, h.substring(0, 8).toLong(16).toInt())
        g.set(ValueLayout.JAVA_SHORT, 4, h.substring(8, 12).toInt(16).toShort())
        g.set(ValueLayout.JAVA_SHORT, 6, h.substring(12, 16).toInt(16).toShort())
        for (i in 0 until 8) {
            g.set(ValueLayout.JAVA_BYTE, 8L + i, h.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte())
        }
        return g
    }

    private fun guidAt(g: MemorySegment, offset: Long): String = buildString {
        append("%08X-".format(g.get(I32, offset)))
        append("%04X-".format(g.get(ValueLayout.JAVA_SHORT, offset + 4).toInt() and 0xFFFF))
        append("%04X-".format(g.get(ValueLayout.JAVA_SHORT, offset + 6).toInt() and 0xFFFF))
        for (i in 0 until 8) {
            append("%02X".format(g.get(ValueLayout.JAVA_BYTE, offset + 8 + i)))
            if (i == 1) append('-')
        }
    }

    private fun hex(hr: Int) = "0x" + "%08x".format(hr)
}
