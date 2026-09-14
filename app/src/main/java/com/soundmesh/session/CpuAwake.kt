package com.soundmesh.session

import android.content.Context
import android.os.PowerManager
import android.os.Process

/**
 * A CPU wake lock, narrowed to what a session needs, so the release discipline can be tested off
 * a handset. The radio has its own, separate hold - see RadioHold, which asks for a different
 * thing and is not a substitute for this one.
 */
internal interface CpuHold {
    fun acquire()
    fun release()
}

/**
 * Keeps the handset from falling asleep for as long as it is playing to a room.
 *
 * Taken because of 2026-09-14, which is the first time the instruments caught this whole. The host
 * was capturing an online song and sending it out; every sink reported itself perfect - playing in
 * sync, no underruns, no reconnects, chunks arriving at 198 KB/s - and the room was silent for
 * four minutes and fifty-three seconds. CaptureSilence said why: every sample coming in was
 * exactly zero. The host's own log says the rest. Over that window it wrote **nothing at all** -
 * not one line from any process on the handset - and then at the instant a USB cable was plugged
 * in, PowerManagerService woke everything and the audio came back in the same second.
 *
 * So the sinks were right: they faithfully played five minutes of zeros, because the handset
 * producing them had gone to sleep with the session still open. A foreground service keeps the
 * process from being killed; it does not keep the device awake.
 *
 * **This is one of two halves and it is worth knowing which.** A partial wake lock stops the
 * device suspending. It does not stop the governor winding the clocks down or the scheduler
 * demoting a thread that is no longer the foreground app - which is a different symptom on a
 * different handset, and is what the audio thread priority in HostSession and SinkSession is for.
 *
 * [take] and [give] rather than a block, because a session spans intents: it starts on one and
 * ends on another, with nothing holding a stack frame in between.
 */
internal class CpuAwake(private val hold: CpuHold?) {
    private var taken = false

    /**
     * [held] is told whether the lock was actually obtained, and it is not decoration. A hold that
     * quietly did nothing would make a quiet night read as evidence that sleep was never the
     * problem, when the arm under test never ran. Which arm ran belongs beside the symptom.
     */
    fun take(held: (Boolean) -> Unit = {}): Boolean {
        // Already holding one is not a reason to take another: the service can be handed the same
        // start intent twice, and two acquires against one release is a handset that stays awake
        // until the process dies.
        if (taken) return true
        val lock = hold
        taken = lock != null && runCatching { lock.acquire() }.isSuccess
        runCatching { held(taken) }
        return taken
    }

    /**
     * Given back whatever happens. A leaked lock keeps the handset awake for the life of the
     * process, which is a battery fault nothing on any screen would show - and release throws on a
     * lock the system has already dropped underneath us, on the last thread of a stopping session
     * where there is nothing left to catch it.
     */
    fun give() {
        if (!taken) return
        taken = false
        runCatching { hold?.release() }
    }
}

/**
 * Says that the calling thread is carrying audio, which is the other half of [CpuAwake].
 *
 * A plain Thread inherits the nice value of whoever created it, which for a service thread is the
 * ordinary one. While the app is in front that costs nothing - the whole process is in the
 * scheduler's top band. With the screen off it is the only thing left saying this thread must not
 * wait: the process drops a band, the governor winds the clocks down, and a handset with no
 * headroom starts missing its deadline. That is a stutter on a 2019 chip while a 2020 one in the
 * same room plays through it, which is exactly what was reported on 2026-09-14.
 *
 * Best effort and deliberately quiet about failing: a handset that refuses the priority should
 * play at the priority it has, not refuse to play.
 */
internal fun atAudioPriority() {
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
}

/** The handset's own power manager, or null if it will not hand a lock over. */
internal fun cpuHoldOf(context: Context): CpuHold? = runCatching {
    val lock = context.applicationContext.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_LOCK_TAG)
    lock.setReferenceCounted(false)
    object : CpuHold {
        override fun acquire() = lock.acquire()
        override fun release() = lock.release()
    }
}.getOrNull()

private const val CPU_LOCK_TAG = "SoundMesh:session"
