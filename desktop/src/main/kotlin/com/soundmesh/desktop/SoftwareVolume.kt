package com.soundmesh.desktop

import kotlin.math.roundToInt

/**
 * The volume a room sets on this machine: SoundMesh's own sound and nothing else (09-23 user's
 * choice). A handset obeys the same command by moving its system volume; here that would move
 * every program's sound on the computer, so this scales the samples instead.
 *
 * Only ever quieter. At 100 the samples go out untouched, so a room that never touched the
 * volume sounds exactly as before, and nothing here can add gain a later stage did not budget for.
 */
class SoftwareVolume {
    /** 0 to 100; 100 until somebody sets it, and again after a restore. */
    @Volatile var percent: Int = FULL
        private set

    fun set(percent: Int) {
        this.percent = percent.coerceIn(0, FULL)
    }

    /** A restore puts back what was there before any set - which for this volume is always full. */
    fun restore() {
        percent = FULL
    }

    /**
     * The amplitude factor for [percent]: its square, so half way is about -12 dB rather than
     * the -6 dB a straight line gives, which a person barely hears as quieter. A system volume
     * slider is shaped the same way for the same reason. Picked, not measured against a handset.
     */
    fun gain(): Double = (percent / FULL.toDouble()).let { it * it }

    companion object {
        const val FULL = 100
    }
}

/**
 * [inner] with every scheduled chunk scaled by [volume].
 *
 * A change is ramped across the chunk it lands in rather than stepped at its edge, so dragging a
 * slider does not click. The chunk is copied rather than scaled in place: the caller's buffer may
 * be one it goes on using.
 */
class GainOutput(private val inner: FrameOutput, private val volume: SoftwareVolume) : FrameOutput {
    private var lastGain = volume.gain()

    override fun frameAtLocalNanos(localNanos: Long): Long = inner.frameAtLocalNanos(localNanos)

    override fun dropScheduled() = inner.dropScheduled()

    override fun schedule(samples: ShortArray, channels: Int, atFrame: Long): Boolean {
        val target = volume.gain()
        val from = lastGain
        if (from == 1.0 && target == 1.0) return inner.schedule(samples, channels, atFrame)
        val frames = samples.size / channels
        val scaled = ShortArray(samples.size)
        for (frame in 0 until frames) {
            val gain = from + (target - from) * (frame + 1) / frames
            for (channel in 0 until channels) {
                val i = frame * channels + channel
                scaled[i] = (samples[i] * gain).roundToInt().toShort()
            }
        }
        val took = inner.schedule(scaled, channels, atFrame)
        // Only a chunk that went out moves the ramp on: one refused as late was never heard.
        if (took) lastGain = target
        return took
    }
}
