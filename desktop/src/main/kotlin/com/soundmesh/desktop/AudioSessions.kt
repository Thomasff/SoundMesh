package com.soundmesh.desktop

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/** One program that has a row in the volume mixer on the default output. */
data class AudioSession(val pid: Long, val name: String, val playing: Boolean)

/** The part of the volume mixer a host capturing a program uses; [AudioSessions] is the real one. */
interface AppMixer {
    fun list(): List<AudioSession>
    fun volume(pid: Long): Float?
    fun setVolume(pid: Long, level: Float): Boolean
}

/**
 * The volume mixer's rows on the default output: who is there, and each one's own level and mute.
 *
 * Asked afresh on every call, because a row comes and goes with the program's streams and a list
 * kept from a minute ago names programs that may have quit. Several rows can belong to one process -
 * a program with two streams - so everything here acts on all of that process's rows at once.
 *
 * The only writes are to a named process's own row. The endpoint's level is a different control,
 * and nothing here touches it.
 */
object AudioSessions : AppMixer {

    /** Every program with a row, playing ones first, this process and the system sounds left out. */
    override fun list(): List<AudioSession> {
        val own = ProcessHandle.current().pid()
        val seen = LinkedHashMap<Long, Boolean>()
        eachSession { pid, state, _ ->
            if (pid != 0L && pid != own) seen[pid] = (seen[pid] ?: false) || state == Wasapi.SESSION_STATE_ACTIVE
        }
        return seen.map { (pid, playing) -> AudioSession(pid, nameOf(pid), playing) }
            .sortedWith(compareByDescending<AudioSession> { it.playing }.thenBy { it.name.lowercase() })
    }

    /** Mutes or unmutes every row [pid] has. Returns whether it had any. */
    fun setMute(pid: Long, mute: Boolean): Boolean {
        var found = false
        eachSession { owner, _, volume ->
            if (owner == pid) {
                Wasapi.check(Wasapi.setSessionMute(volume, mute), "SetMute")
                found = true
            }
        }
        return found
    }

    /** Whether [pid]'s first row is muted, or null if it has none. */
    fun isMuted(pid: Long): Boolean? {
        var answer: Boolean? = null
        eachSession { owner, _, volume ->
            if (owner == pid && answer == null) {
                Arena.ofConfined().use { arena ->
                    val out = arena.allocate(4, 4)
                    Wasapi.check(Wasapi.sessionMute(volume, out), "GetMute")
                    answer = out.get(Wasapi.I32, 0) != 0
                }
            }
        }
        return answer
    }

    /** Sets every row [pid] has to [level], 0 to 1. Returns whether it had any. */
    override fun setVolume(pid: Long, level: Float): Boolean {
        var found = false
        eachSession { owner, _, volume ->
            if (owner == pid) {
                Wasapi.check(Wasapi.setSessionVolume(volume, level), "SetMasterVolume")
                found = true
            }
        }
        return found
    }

    /** [pid]'s first row's level, or null if it has none. */
    override fun volume(pid: Long): Float? {
        var answer: Float? = null
        eachSession { owner, _, volume ->
            if (owner == pid && answer == null) {
                Arena.ofConfined().use { arena ->
                    val out = arena.allocate(4, 4)
                    Wasapi.check(Wasapi.sessionVolume(volume, out), "GetMasterVolume")
                    answer = out.get(Wasapi.F32, 0)
                }
            }
        }
        return answer
    }

    /** What the person would call [pid]: its executable's name, without the extension. */
    fun nameOf(pid: Long): String =
        ProcessHandle.of(pid)
            .flatMap { it.info().command() }
            .map { File(it).nameWithoutExtension }
            .orElse("pid $pid")

    private fun eachSession(visit: (pid: Long, state: Int, volume: MemorySegment) -> Unit) {
        Wasapi.coInitialize()
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(8, 8)
            val scratch = arena.allocate(8, 8)
            Wasapi.check(
                Wasapi.coCreateInstance(
                    Wasapi.guid(arena, Wasapi.CLSID_MM_DEVICE_ENUMERATOR),
                    Wasapi.guid(arena, Wasapi.IID_IMM_DEVICE_ENUMERATOR),
                    out
                ),
                "CoCreateInstance(MMDeviceEnumerator)"
            )
            val enumerator = out.get(Wasapi.PTR, 0)
            Wasapi.check(
                Wasapi.getDefaultAudioEndpoint(enumerator, Wasapi.DATAFLOW_RENDER, Wasapi.ROLE_MULTIMEDIA, out),
                "GetDefaultAudioEndpoint"
            )
            val device = out.get(Wasapi.PTR, 0)
            Wasapi.check(
                Wasapi.activate(device, Wasapi.guid(arena, Wasapi.IID_IAUDIO_SESSION_MANAGER2), out),
                "Activate(IAudioSessionManager2)"
            )
            val manager = out.get(Wasapi.PTR, 0)
            Wasapi.check(Wasapi.sessionEnumerator(manager, out), "GetSessionEnumerator")
            val sessions = out.get(Wasapi.PTR, 0)
            Wasapi.check(Wasapi.sessionCount(sessions, scratch), "GetCount")
            val count = scratch.get(Wasapi.I32, 0)
            val control2Id = Wasapi.guid(arena, Wasapi.IID_IAUDIO_SESSION_CONTROL2)
            val volumeId = Wasapi.guid(arena, Wasapi.IID_ISIMPLE_AUDIO_VOLUME)
            try {
                for (i in 0 until count) {
                    if (Wasapi.session(sessions, i, out) < 0) continue
                    val control = out.get(Wasapi.PTR, 0)
                    try {
                        if (Wasapi.sessionState(control, scratch) < 0) continue
                        val state = scratch.get(Wasapi.I32, 0)
                        if (Wasapi.queryInterface(control, control2Id, out) < 0) continue
                        val control2 = out.get(Wasapi.PTR, 0)
                        val pid = if (Wasapi.sessionProcessId(control2, scratch) >= 0) {
                            scratch.get(Wasapi.I32, 0).toLong() and 0xFFFFFFFFL
                        } else {
                            0L
                        }
                        Wasapi.release(control2)
                        if (Wasapi.queryInterface(control, volumeId, out) < 0) continue
                        val volume = out.get(Wasapi.PTR, 0)
                        try {
                            visit(pid, state, volume)
                        } finally {
                            Wasapi.release(volume)
                        }
                    } finally {
                        Wasapi.release(control)
                    }
                }
            } finally {
                Wasapi.release(sessions)
                Wasapi.release(manager)
                Wasapi.release(device)
                Wasapi.release(enumerator)
            }
        }
    }
}
