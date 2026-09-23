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
    private val PROP_VARIANT_CLEAR =
        export("ole32.dll", "PropVariantClear", FunctionDescriptor.of(I32, PTR))
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

    /** HRESULT f(this, count) - the capture side's ReleaseBuffer, which carries no flags. */
    private val CALL_I = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, I32))

    /** HRESULT OpenPropertyStore(this, stgmAccess, out*) */
    private val CALL_PI_P = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, I32, PTR))

    /** HRESULT GetBuffer(this, data**, frames*, flags*, devicePosition*, qpcPosition*) */
    private val CALL_CAPTURE_GET_BUFFER =
        LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, PTR, PTR, PTR, PTR, PTR))

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
    /**
     * IAudioClient2, which is IAudioClient plus the three methods that describe the stream
     * before it is initialised. Activated instead of IAudioClient rather than queried from it:
     * it derives from IAudioClient, so the same pointer answers every call the rest of this
     * makes, and one Activate is one fewer thing to release.
     */
    const val IID_IAUDIO_CLIENT2 = "726778CD-F60A-4EDA-82DE-E47610CD78AA"
    const val IID_IAUDIO_RENDER_CLIENT = "F294ACFC-3146-4483-A7BF-ADDCA7C260E2"
    const val IID_IAUDIO_CAPTURE_CLIENT = "C8ADBD64-E71E-48A0-A4DE-185C395CD317"
    const val IID_IAUDIO_CLOCK = "CD63314F-3FBA-4A1B-812C-EF96358728E7"
    const val IID_IAUDIO_ENDPOINT_VOLUME = "5CDF2C82-841E-4546-9722-0CF74078229A"
    const val SUBTYPE_FLOAT = "00000003-0000-0010-8000-00AA00389B71"

    /** The format part of PKEY_Device_FriendlyName; its property id is 14. */
    const val PKEY_DEVICE_FRIENDLY_NAME_FMTID = "A45C254E-DF1C-4EFD-8020-67D146A850E0"

    const val VT_LPWSTR = 31

    const val CLSCTX_ALL = 23
    const val SHARE_MODE_SHARED = 0
    const val DATAFLOW_RENDER = 0

    /** eCapture: the default recording endpoint, which is a different device from the render one. */
    const val DATAFLOW_CAPTURE = 1

    /**
     * AUDCLNT_STREAMFLAGS_LOOPBACK, which is set on a client opened against the *render* endpoint.
     *
     * What comes back is the mix the engine is about to hand the hardware - the same samples, no
     * speaker, no room, no microphone. It is the only arrangement on this machine where the right
     * answer to "when did that chirp come out" is known in advance rather than measured.
     */
    const val STREAMFLAGS_LOOPBACK = 0x00020000

    /**
     * AUDCLNT_STREAMOPTIONS_RAW: the stream skips the endpoint's signal processing.
     *
     * What sits in that chain is the vendor's, configured in the driver package and different
     * between machines. On a communications capture endpoint it includes noise suppression, and
     * suppression decides what is worth passing on: this machine's microphone array returns
     * digital silence for a quiet room, for a clap and for a chirp, while speech goes through
     * fine. Raw is the documented way past it, and it is the gentler of the two ways - exclusive
     * mode also bypasses the engine but takes the device away from every other program.
     */
    const val STREAMOPTIONS_RAW = 0x1

    /** AudioCategory_Other: no category, which is what a measurement is. */
    const val AUDIO_CATEGORY_OTHER = 0

    /** AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY: frames are missing before this packet. */
    const val BUFFERFLAGS_DISCONTINUITY = 0x1

    /** AUDCLNT_BUFFERFLAGS_SILENT: the packet's memory holds nothing and must be read as zeros. */
    const val BUFFERFLAGS_SILENT = 0x2

    /** AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR: the device position stamp on this packet is not usable. */
    const val BUFFERFLAGS_TIMESTAMP_ERROR = 0x4

    /** AUDCLNT_S_BUFFER_EMPTY, returned by a capture GetBuffer with nothing waiting. Not an error. */
    const val S_BUFFER_EMPTY = 0x08890001

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
    /**
     * IAudioClient2::SetClientProperties, vtable 16, which must be called before Initialize.
     *
     * The AudioClientProperties it takes is four 32-bit fields: its own size, whether the stream
     * is offloaded, its category, and its options. Built here rather than by the caller because
     * the size field has to agree with the layout, and a struct whose length is written by one
     * place and filled by another is the shape that fails quietly.
     */
    fun setClientProperties(arena: Arena, self: MemorySegment, options: Int, category: Int = AUDIO_CATEGORY_OTHER): Int {
        val properties = arena.allocate(16, 8)
        properties.set(I32, 0, 16)
        properties.set(I32, 4, 0)
        properties.set(I32, 8, category)
        properties.set(I32, 12, options)
        return CALL_P.invokeExact(method(self, 16), self, properties) as Int
    }

    fun activate(self: MemorySegment, iid: MemorySegment, out: MemorySegment): Int =
        CALL_ACTIVATE.invokeExact(
            method(self, 3), self, iid, CLSCTX_ALL, MemorySegment.NULL, out
        ) as Int

    /**
     * What the person at this machine calls the device this code just opened.
     *
     * Worth the property store it takes to get: this machine has three endpoints, and which one
     * is the multimedia default is not something any measurement here can tell by looking at the
     * numbers. A run that names its device cannot silently be a run of a different device.
     *
     * Returns null rather than throwing - a missing name is a worse report, not a failed run.
     */
    fun friendlyName(arena: Arena, device: MemorySegment): String? {
        val out = arena.allocate(8, 8)
        // IMMDevice::OpenPropertyStore, vtable 4. STGM_READ is 0.
        if ((CALL_PI_P.invokeExact(method(device, 4), device, 0, out) as Int) < 0) return null
        val store = out.get(PTR, 0)

        // PKEY_Device_FriendlyName: a GUID followed by a property id, twenty bytes in all.
        val key = arena.allocate(24, 8)
        key.copyFrom(guid(arena, PKEY_DEVICE_FRIENDLY_NAME_FMTID).reinterpret(16))
        key.set(I32, 16, 14)

        // A PROPVARIANT is twenty-four bytes here: the type at 0, then padding, then the union at
        // 8, which for VT_LPWSTR holds a pointer to a null-terminated wide string.
        val value = arena.allocate(24, 8)
        if (pp(store, 5, key, value) < 0) return null
        if (value.get(I16, 0).toInt() != VT_LPWSTR) return null
        val text = wideStringAt(value.get(PTR, 8))
        PROP_VARIANT_CLEAR.invokeExact(value) as Int
        return text
    }

    /**
     * How loud this endpoint is set and whether it is muted, or null if that cannot be asked.
     *
     * Read only. Nothing in this project changes a person's volume: it is theirs, it applies to
     * everything else they run, and a measurement tool that turns the speakers up is a worse
     * neighbour than one that says it heard nothing.
     *
     * Worth having because of what silence looks like from inside. A muted microphone hands back
     * zeros through the ordinary path - no error, no flag, a full-length recording - and so does a
     * room with nothing in it. The two are the same recording, and only this tells them apart.
     */
    fun endpointVolume(arena: Arena, device: MemorySegment): Pair<Float, Boolean>? {
        val out = arena.allocate(8, 8)
        if (activate(device, guid(arena, IID_IAUDIO_ENDPOINT_VOLUME), out) < 0) return null
        val volume = out.get(PTR, 0)
        val scratch = arena.allocate(8, 8)
        // GetMasterVolumeLevelScalar is vtable 9 and GetMute 15; the eleven setters between them
        // are deliberately not wrapped.
        if (p(volume, 9, scratch) < 0) return null
        val level = scratch.get(F32, 0)
        if (p(volume, 15, scratch) < 0) return null
        return level to (scratch.get(I32, 0) != 0)
    }

    private fun wideStringAt(p: MemorySegment): String {
        val sb = StringBuilder()
        var at = 0L
        while (true) {
            val unit = p.reinterpret(at + 2).get(I16, at)
            if (unit.toInt() == 0) return sb.toString()
            sb.append(unit.toInt().toChar())
            at += 2
        }
    }

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

    // -------------------------------------------------------- IAudioCaptureClient
    //
    // GetBuffer 3, ReleaseBuffer 4, GetNextPacketSize 5. The same table positions as the render
    // side and different signatures at both of them, which is the one thing a reader coming from
    // the interface above is likely to get wrong.

    /**
     * Hands over the next packet whole: its memory, its length, its flags, and both its stamps.
     *
     * Whatever is taken must be given back with [captureReleaseBuffer] before the next call, and
     * the frame count handed back has to be the one that came out of here or zero - the engine
     * does not accept a partial read.
     */
    fun captureGetBuffer(
        self: MemorySegment,
        outData: MemorySegment,
        outFrames: MemorySegment,
        outFlags: MemorySegment,
        outDevicePosition: MemorySegment,
        outQpcPosition: MemorySegment
    ): Int = CALL_CAPTURE_GET_BUFFER.invokeExact(
        method(self, 3), self, outData, outFrames, outFlags, outDevicePosition, outQpcPosition
    ) as Int

    fun captureReleaseBuffer(self: MemorySegment, frames: Int): Int =
        CALL_I.invokeExact(method(self, 4), self, frames) as Int

    fun getNextPacketSize(self: MemorySegment, outFrames: MemorySegment): Int = p(self, 5, outFrames)

    // ---------------------------------------------------------------- IAudioClock

    fun getFrequency(self: MemorySegment, out: MemorySegment): Int = p(self, 3, out)

    fun getPosition(self: MemorySegment, outPosition: MemorySegment, outQpc: MemorySegment): Int =
        pp(self, 4, outPosition, outQpc)

    // -------------------------------------------------------------- IUnknown

    fun queryInterface(self: MemorySegment, iid: MemorySegment, out: MemorySegment): Int =
        pp(self, 0, iid, out)

    fun release(self: MemorySegment): Int = v(self, 2)

    // ---------------------------------------------------------- audio sessions
    //
    // One session is one row of the volume mixer: a program's streams on one endpoint, with the
    // level and mute the person sets there. IAudioSessionManager2 GetSessionEnumerator 5;
    // IAudioSessionEnumerator GetCount 3, GetSession 4; IAudioSessionControl GetState 3;
    // IAudioSessionControl2 GetProcessId 14; ISimpleAudioVolume SetMasterVolume 3,
    // GetMasterVolume 4, SetMute 5, GetMute 6.

    const val IID_IAUDIO_SESSION_MANAGER2 = "77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"
    const val IID_IAUDIO_SESSION_CONTROL2 = "BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"
    const val IID_ISIMPLE_AUDIO_VOLUME = "87CE5498-68D6-44E5-9215-6DA47EF883D8"

    /** AudioSessionStateActive: the session has a stream that is playing right now. */
    const val SESSION_STATE_ACTIVE = 1

    /** HRESULT f(this, int, out*) */
    private val CALL_I_P = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, I32, PTR))

    /** HRESULT SetMasterVolume(this, float, eventContext*) */
    private val CALL_F_P = LINKER.downcallHandle(FunctionDescriptor.of(I32, PTR, F32, PTR))

    fun sessionEnumerator(manager: MemorySegment, out: MemorySegment): Int = p(manager, 5, out)

    fun sessionCount(enumerator: MemorySegment, out: MemorySegment): Int = p(enumerator, 3, out)

    fun session(enumerator: MemorySegment, index: Int, out: MemorySegment): Int =
        CALL_I_P.invokeExact(method(enumerator, 4), enumerator, index, out) as Int

    fun sessionState(control: MemorySegment, out: MemorySegment): Int = p(control, 3, out)

    fun sessionProcessId(control2: MemorySegment, out: MemorySegment): Int = p(control2, 14, out)

    fun setSessionVolume(volume: MemorySegment, level: Float): Int =
        CALL_F_P.invokeExact(method(volume, 3), volume, level, MemorySegment.NULL) as Int

    fun sessionVolume(volume: MemorySegment, out: MemorySegment): Int = p(volume, 4, out)

    fun setSessionMute(volume: MemorySegment, mute: Boolean): Int =
        CALL_I_P.invokeExact(method(volume, 5), volume, if (mute) 1 else 0, MemorySegment.NULL) as Int

    fun sessionMute(volume: MemorySegment, out: MemorySegment): Int = p(volume, 6, out)

    // ------------------------------------------------------- process loopback
    //
    // One program's sound on its own, before it is mixed with anybody else's: Windows 10 21H2 and
    // later. It is not a device the enumerator lists, so it is reached through
    // ActivateAudioInterfaceAsync, which answers on another thread through a COM object the
    // caller supplies - [ActivationHandler] is that object, built by hand like everything here.

    private const val PROCESS_LOOPBACK_PATH = "VAD\\Process_Loopback"
    private const val IID_IUNKNOWN = "00000000-0000-0000-C000-000000000046"
    private const val IID_IAGILE_OBJECT = "94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"
    private const val IID_IACTIVATE_COMPLETION_HANDLER = "41D949AB-9862-444A-80F6-C261334DA5EB"

    /** VT_BLOB: a PROPVARIANT carrying a length and a pointer. */
    private const val VT_BLOB = 65

    /** AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK. */
    private const val ACTIVATION_TYPE_PROCESS_LOOPBACK = 1

    /** PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE: the program and whatever it started. */
    private const val LOOPBACK_MODE_INCLUDE_TREE = 0

    private const val E_NOINTERFACE = 0x80004002.toInt()
    private const val E_TIMEOUT = 0x800705B4.toInt()

    private val ACTIVATE_AUDIO_INTERFACE_ASYNC = export(
        "Mmdevapi.dll", "ActivateAudioInterfaceAsync",
        FunctionDescriptor.of(I32, PTR, PTR, PTR, PTR, PTR)
    )

    /**
     * An IAudioClient that hears the process [pid] and everything it started, and nothing else.
     *
     * Blocks until the system answers. Serialised, because the handler is one object with one
     * slot for the answer.
     */
    fun activateProcessLoopback(arena: Arena, pid: Long, out: MemorySegment): Int =
        synchronized(ActivationHandler) {
            val params = arena.allocate(12, 4)
            params.set(I32, 0, ACTIVATION_TYPE_PROCESS_LOOPBACK)
            params.set(I32, 4, pid.toInt())
            params.set(I32, 8, LOOPBACK_MODE_INCLUDE_TREE)
            // PROPVARIANT: the type at 0, then a BLOB at 8 - its length, padding, its pointer.
            val variant = arena.allocate(24, 8)
            variant.set(I16, 0, VT_BLOB.toShort())
            variant.set(I32, 8, 12)
            variant.set(PTR, 16, params)

            val latch = java.util.concurrent.CountDownLatch(1)
            ActivationHandler.latch = latch
            val operationOut = arena.allocate(8, 8)
            val hr = ACTIVATE_AUDIO_INTERFACE_ASYNC.invokeExact(
                arena.allocateFrom(PROCESS_LOOPBACK_PATH, java.nio.charset.StandardCharsets.UTF_16LE),
                guid(arena, IID_IAUDIO_CLIENT),
                variant,
                ActivationHandler.self,
                operationOut
            ) as Int
            if (hr < 0) return hr
            if (!latch.await(ACTIVATION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) return E_TIMEOUT
            val operation = operationOut.get(PTR, 0)
            // IActivateAudioInterfaceAsyncOperation::GetActivateResult, vtable 3: the activation's
            // own result, then the interface. The call returning success says only that the
            // question was answered.
            val result = arena.allocate(4, 4)
            val called = pp(operation, 3, result, out)
            release(operation)
            if (called < 0) called else result.get(I32, 0)
        }

    private const val ACTIVATION_TIMEOUT_SECONDS = 5L

    /**
     * IActivateAudioInterfaceCompletionHandler, as a vtable of four upcalls.
     *
     * Lives for the whole process, which is what lets AddRef and Release be constants: the system
     * may let go of it after the call that used it has returned, and memory that outlived nothing
     * cannot be released too early. Answers for IAgileObject too, because the system calls it
     * from its own thread and refuses a handler that is not free-threaded.
     */
    private object ActivationHandler {
        private val arena = Arena.global()
        val self: MemorySegment

        @Volatile var latch: java.util.concurrent.CountDownLatch? = null

        private val answers: List<MemorySegment> =
            listOf(IID_IUNKNOWN, IID_IAGILE_OBJECT, IID_IACTIVATE_COMPLETION_HANDLER).map { guid(arena, it) }

        init {
            val lookup = java.lang.invoke.MethodHandles.lookup()
            val int = Int::class.javaPrimitiveType!!
            val seg = MemorySegment::class.java
            fun stub(name: String, vararg params: Class<*>, fd: FunctionDescriptor): MemorySegment =
                LINKER.upcallStub(
                    lookup.findStatic(ActivationHandler::class.java, name, java.lang.invoke.MethodType.methodType(int, params)),
                    fd,
                    arena
                )
            val vtable = arena.allocate(4 * 8L, 8)
            vtable.set(PTR, 0, stub("queryInterface", seg, seg, seg, fd = FunctionDescriptor.of(I32, PTR, PTR, PTR)))
            vtable.set(PTR, 8, stub("addRef", seg, fd = FunctionDescriptor.of(I32, PTR)))
            vtable.set(PTR, 16, stub("release", seg, fd = FunctionDescriptor.of(I32, PTR)))
            vtable.set(PTR, 24, stub("activateCompleted", seg, seg, fd = FunctionDescriptor.of(I32, PTR, PTR)))
            self = arena.allocate(8, 8)
            self.set(PTR, 0, vtable)
        }

        @JvmStatic
        fun queryInterface(self: MemorySegment, riid: MemorySegment, ppv: MemorySegment): Int {
            val asked = riid.reinterpret(16)
            val slot = ppv.reinterpret(8)
            return if (answers.any { it.mismatch(asked) == -1L }) {
                slot.set(PTR, 0, self)
                0
            } else {
                slot.set(PTR, 0, MemorySegment.NULL)
                E_NOINTERFACE
            }
        }

        @JvmStatic
        fun addRef(self: MemorySegment): Int = 1

        @JvmStatic
        fun release(self: MemorySegment): Int = 1

        @JvmStatic
        fun activateCompleted(self: MemorySegment, operation: MemorySegment): Int {
            latch?.countDown()
            return 0
        }
    }

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
