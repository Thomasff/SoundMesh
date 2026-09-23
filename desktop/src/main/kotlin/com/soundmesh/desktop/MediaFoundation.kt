package com.soundmesh.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets.UTF_16LE

/** Sound as a decoder produced it: 16-bit signed little-endian, interleaved, at the file's own rate. */
internal class DecodedAudio(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

/**
 * The decoders Windows ships, reached through Media Foundation's source reader by hand.
 *
 * Checked against a throwaway probe on 2026-09-23 (docs/feasibility-results/data/2026-09-23-media-foundation):
 * a WAV read through here came out byte for byte what javax.sound reads, a 44.1 kHz mono MP3 that
 * Windows' own encoder wrote came out still 44.1 kHz mono at the pitch it was written at, and a
 * five minute MP3 took 0.57 s - which is why a song is decoded whole rather than streamed.
 *
 * **Asked for 16-bit PCM and nothing else.** The reader will resample and remix as well if asked,
 * and it is not asked: the rate and the channels go through core's Resampler like every other
 * source, so the product has one resampler, the one the archive was measured with. And the rate and
 * channels are read back from what the decoder says it is producing, never from the container - a
 * wrong one would still decode and still play, at the wrong speed, with every timing number sane.
 */
internal object MediaFoundation {

    private val LINKER: Linker = Linker.nativeLinker()
    private val LIBS: Arena = Arena.global()
    private val PTR = Wasapi.PTR
    private val I32 = Wasapi.I32
    private val I64 = Wasapi.I64

    private fun export(library: String, name: String, fd: FunctionDescriptor): MethodHandle =
        LINKER.downcallHandle(
            SymbolLookup.libraryLookup(library, LIBS).find(name)
                .orElseThrow { IllegalStateException("$library has no $name") },
            fd
        )

    private val MF_STARTUP = export("mfplat.dll", "MFStartup", FunctionDescriptor.of(I32, I32, I32))
    private val MF_SHUTDOWN = export("mfplat.dll", "MFShutdown", FunctionDescriptor.of(I32))
    private val MF_CREATE_MEDIA_TYPE = export("mfplat.dll", "MFCreateMediaType", FunctionDescriptor.of(I32, PTR))
    private val MF_CREATE_SOURCE_READER_FROM_URL =
        export("mfreadwrite.dll", "MFCreateSourceReaderFromURL", FunctionDescriptor.of(I32, PTR, PTR, PTR))

    /** HRESULT f(this) - Release, Unlock */
    private val CALL_V = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR))

    /** HRESULT f(this, out*) - ConvertToContiguousBuffer */
    private val CALL_P = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, PTR))

    /** HRESULT f(this, key*, value*) - SetGUID, GetUINT32 */
    private val CALL_PP = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, PTR, PTR))

    /** HRESULT Lock(this, data**, maxLength*, currentLength*) */
    private val CALL_PPP = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, PTR, PTR, PTR))

    /** HRESULT SetUINT32(this, key*, value) */
    private val CALL_PI = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, PTR, I32))

    /** HRESULT SetStreamSelection(this, stream, selected) */
    private val CALL_II = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, I32, I32))

    /** HRESULT GetCurrentMediaType(this, stream, out*) */
    private val CALL_IP = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, I32, PTR))

    /** HRESULT SetCurrentMediaType(this, stream, reserved*, type*) */
    private val CALL_IPP = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, I32, PTR, PTR))

    /** HRESULT ReadSample(this, stream, controlFlags, actualStream*, flags*, timestamp*, sample**) */
    private val CALL_READ_SAMPLE =
        LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, I32, I32, PTR, PTR, PTR, PTR))

    /**
     * Decodes all of [file] to 16-bit PCM at the file's own rate and channel count, or throws an
     * IllegalArgumentException that says what is wrong with the file in words a person can act on.
     *
     * COM is initialised on the calling thread as a multithreaded apartment, the same as the
     * renderer does, so the player thread can open the speakers after this without a clash.
     */
    fun decode(file: File, maxSeconds: Int): DecodedAudio {
        Wasapi.coInitialize()
        Wasapi.check(MF_STARTUP.invokeExact(MF_VERSION, MFSTARTUP_FULL) as Int, "MFStartup")
        try {
            Arena.ofConfined().use { arena -> return read(arena, file, maxSeconds) }
        } finally {
            MF_SHUTDOWN.invokeExact() as Int
        }
    }

    private fun read(arena: Arena, file: File, maxSeconds: Int): DecodedAudio {
        val out = arena.allocate(PTR)
        val opened = MF_CREATE_SOURCE_READER_FROM_URL.invokeExact(
            arena.allocateFrom(file.absolutePath, UTF_16LE), MemorySegment.NULL, out
        ) as Int
        require(opened >= 0) {
            "${file.name} is not in a format this machine can decode - Windows reads MP3, AAC (M4A), " +
                "FLAC, WMA and WAV (Media Foundation said ${Wasapi.hex(opened)})"
        }
        val reader = out.get(PTR, 0)
        try {
            Wasapi.check(ii(reader, SET_STREAM_SELECTION, ALL_STREAMS, 0), "deselecting every stream")
            val selected = ii(reader, SET_STREAM_SELECTION, FIRST_AUDIO_STREAM, 1)
            require(selected >= 0) { "${file.name} has no sound in it (${Wasapi.hex(selected)})" }
            askForPcm(arena, reader, file.name)
            val (rate, channels) = currentFormat(arena, reader)
            require(channels == 1 || channels == 2) {
                "$channels channels is more than a stereo pipeline can place; export it as mono or stereo"
            }
            val limitBytes = maxSeconds.toLong() * rate * channels * BYTES_PER_SAMPLE
            return DecodedAudio(readAll(arena, reader, file.name, limitBytes, maxSeconds), rate, channels)
        } finally {
            release(reader)
        }
    }

    /** 16-bit PCM, with the rate and channel count left for the decoder to keep as the file has them. */
    private fun askForPcm(arena: Arena, reader: MemorySegment, name: String) {
        val typeOut = arena.allocate(PTR)
        Wasapi.check(MF_CREATE_MEDIA_TYPE.invokeExact(typeOut) as Int, "MFCreateMediaType")
        val wanted = typeOut.get(PTR, 0)
        try {
            Wasapi.check(pp(wanted, SET_GUID, Wasapi.guid(arena, MT_MAJOR_TYPE), Wasapi.guid(arena, MEDIA_TYPE_AUDIO)), "setting the major type")
            Wasapi.check(pp(wanted, SET_GUID, Wasapi.guid(arena, MT_SUBTYPE), Wasapi.guid(arena, AUDIO_FORMAT_PCM)), "setting the subtype")
            Wasapi.check(pi(wanted, SET_UINT32, Wasapi.guid(arena, MT_AUDIO_BITS_PER_SAMPLE), 16), "setting the sample size")
            val set = CALL_IPP.invokeExact(
                method(reader, SET_CURRENT_MEDIA_TYPE), reader, FIRST_AUDIO_STREAM, MemorySegment.NULL, wanted
            ) as Int
            require(set >= 0) { "$name holds sound this machine has no decoder for (${Wasapi.hex(set)})" }
        } finally {
            release(wanted)
        }
    }

    /** The rate and channel count the decoder says it is producing - what the conversion is driven from. */
    private fun currentFormat(arena: Arena, reader: MemorySegment): Pair<Int, Int> {
        val typeOut = arena.allocate(PTR)
        Wasapi.check(
            CALL_IP.invokeExact(method(reader, GET_CURRENT_MEDIA_TYPE), reader, FIRST_AUDIO_STREAM, typeOut) as Int,
            "reading what the decoder produces"
        )
        val type = typeOut.get(PTR, 0)
        try {
            val value = arena.allocate(I32)
            fun uint32(key: String, what: String): Int {
                Wasapi.check(pp(type, GET_UINT32, Wasapi.guid(arena, key), value), what)
                return value.get(I32, 0)
            }
            val bits = uint32(MT_AUDIO_BITS_PER_SAMPLE, "reading the sample size")
            check(bits == 16) { "the decoder produced $bits-bit samples after being asked for 16" }
            return uint32(MT_AUDIO_SAMPLES_PER_SECOND, "reading the rate") to
                uint32(MT_AUDIO_NUM_CHANNELS, "reading the channel count")
        } finally {
            release(type)
        }
    }

    private fun readAll(arena: Arena, reader: MemorySegment, name: String, limitBytes: Long, maxSeconds: Int): ByteArray {
        val pcm = ByteArrayOutputStream()
        val actualStream = arena.allocate(I32)
        val flags = arena.allocate(I32)
        val timestamp = arena.allocate(I64)
        val sampleOut = arena.allocate(PTR)
        val bufferOut = arena.allocate(PTR)
        val data = arena.allocate(PTR)
        val length = arena.allocate(I32)
        while (true) {
            Wasapi.check(
                CALL_READ_SAMPLE.invokeExact(
                    method(reader, READ_SAMPLE), reader, FIRST_AUDIO_STREAM, 0, actualStream, flags, timestamp, sampleOut
                ) as Int,
                "reading $name"
            )
            val flag = flags.get(I32, 0)
            check(flag and READER_ERROR == 0) { "the decoder failed partway through $name" }
            // The conversion is driven from the type read before the first sample. A change
            // partway through would have every later byte converted at the wrong rate or width.
            check(flag and CURRENT_MEDIA_TYPE_CHANGED == 0) { "$name changes format partway through, which this cannot follow" }
            val sample = sampleOut.get(PTR, 0)
            if (sample.address() != 0L) {
                try {
                    Wasapi.check(p(sample, CONVERT_TO_CONTIGUOUS_BUFFER, bufferOut), "gathering a sample")
                    val buffer = bufferOut.get(PTR, 0)
                    try {
                        Wasapi.check(ppp(buffer, LOCK, data, MemorySegment.NULL, length), "locking a sample")
                        val bytes = length.get(I32, 0).toLong()
                        pcm.write(data.get(PTR, 0).reinterpret(bytes).toArray(ValueLayout.JAVA_BYTE))
                        v(buffer, UNLOCK)
                    } finally {
                        release(buffer)
                    }
                } finally {
                    release(sample)
                }
                require(pcm.size() <= limitBytes) {
                    "$name is longer than ${maxSeconds / 60} minutes, and a song is held whole in memory " +
                        "before it plays; pick a shorter one"
                }
            }
            if (flag and END_OF_STREAM != 0) return pcm.toByteArray()
        }
    }

    // ------------------------------------------------------------------ vtable calls

    private fun method(obj: MemorySegment, index: Int): MemorySegment {
        val vtbl = obj.reinterpret(8).get(PTR, 0)
        return vtbl.reinterpret((index + 1) * 8L).get(PTR, index * 8L)
    }

    private fun v(self: MemorySegment, index: Int): Int = CALL_V.invokeExact(method(self, index), self) as Int

    private fun p(self: MemorySegment, index: Int, a: MemorySegment): Int =
        CALL_P.invokeExact(method(self, index), self, a) as Int

    private fun pp(self: MemorySegment, index: Int, a: MemorySegment, b: MemorySegment): Int =
        CALL_PP.invokeExact(method(self, index), self, a, b) as Int

    private fun ppp(self: MemorySegment, index: Int, a: MemorySegment, b: MemorySegment, c: MemorySegment): Int =
        CALL_PPP.invokeExact(method(self, index), self, a, b, c) as Int

    private fun pi(self: MemorySegment, index: Int, a: MemorySegment, value: Int): Int =
        CALL_PI.invokeExact(method(self, index), self, a, value) as Int

    private fun ii(self: MemorySegment, index: Int, a: Int, b: Int): Int =
        CALL_II.invokeExact(method(self, index), self, a, b) as Int

    private fun release(obj: MemorySegment) {
        if (obj.address() != 0L) v(obj, RELEASE)
    }

    private const val MF_VERSION = 0x00020070
    private const val MFSTARTUP_FULL = 0
    private const val BYTES_PER_SAMPLE = 2

    private const val FIRST_AUDIO_STREAM = -3 // MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0xFFFFFFFD
    private const val ALL_STREAMS = -2 // MF_SOURCE_READER_ALL_STREAMS, 0xFFFFFFFE

    private const val READER_ERROR = 0x1
    private const val END_OF_STREAM = 0x2
    private const val CURRENT_MEDIA_TYPE_CHANGED = 0x20

    // IUnknown
    private const val RELEASE = 2

    // IMFSourceReader
    private const val SET_STREAM_SELECTION = 4
    private const val GET_CURRENT_MEDIA_TYPE = 6
    private const val SET_CURRENT_MEDIA_TYPE = 7
    private const val READ_SAMPLE = 9

    // IMFAttributes, which IMFMediaType and IMFSample both begin with
    private const val GET_UINT32 = 7
    private const val SET_UINT32 = 21
    private const val SET_GUID = 24

    // IMFSample
    private const val CONVERT_TO_CONTIGUOUS_BUFFER = 41

    // IMFMediaBuffer
    private const val LOCK = 3
    private const val UNLOCK = 4

    private const val MT_MAJOR_TYPE = "48eba18e-f8c9-4687-bf11-0a74c9f96a8f"
    private const val MT_SUBTYPE = "f7e34c9a-42e8-4714-b74b-cb29d72c35e5"
    private const val MT_AUDIO_BITS_PER_SAMPLE = "f2deb57f-40fa-4764-aa33-ed4f2d1ff669"
    private const val MT_AUDIO_NUM_CHANNELS = "37e48bf5-645e-4c5b-89de-ada9e29b696a"
    private const val MT_AUDIO_SAMPLES_PER_SECOND = "5faeeae7-0290-4c31-9e8a-c534f68d9dba"
    private const val MEDIA_TYPE_AUDIO = "73647561-0000-0010-8000-00AA00389B71"
    private const val AUDIO_FORMAT_PCM = "00000001-0000-0010-8000-00AA00389B71"
}
