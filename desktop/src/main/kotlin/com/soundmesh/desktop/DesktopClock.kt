package com.soundmesh.desktop

import java.lang.foreign.Arena

/**
 * The one place a QPC tick becomes a `System.nanoTime()` nanosecond, and back.
 *
 * The audio half of this client is on QPC: the renderer schedules on it, the capture stream stamps
 * with it. The wire half is on `System.nanoTime()`, because that is the only clock the handsets
 * put in a clock exchange. Nothing can be measured across the two machines until those meet.
 *
 * On the machine this was written on they are the same counter - the difference holds to 150 ns
 * over twelve seconds, fits a slope of 0.001 ppm, and its constant is exactly zero in separate
 * JVMs, so the conversion there is scale alone. ClockBridge is the program that established that
 * and is the way to establish it again somewhere else.
 *
 * The constant is measured here anyway rather than written in as the zero that was observed. That
 * zero is a detail of one runtime on one operating system, not a promise: the specification says
 * only that `nanoTime` is monotonic from an arbitrary origin, so a runtime is entitled to pick a
 * different one. Measuring costs a few microseconds at startup and is the difference between code
 * that works elsewhere and code that happens to work here.
 *
 * What is *not* handled is the two drifting apart, and deliberately: if they ever do, a fixed
 * offset is the wrong shape of answer and the right response is to find out why rather than to
 * fit a slope through it.
 */
class DesktopClock private constructor(
    val qpcFrequency: Long,
    /** nanoTime minus QPC-converted-to-nanoseconds, as measured at startup. */
    val offsetNanos: Long,
    /** How wide the narrowest read used to establish [offsetNanos] was. The offset's own error. */
    val uncertaintyNanos: Long
) {

    fun nanosAt(qpc: Long): Long = Math.round(qpc.toDouble() / qpcFrequency * 1e9) + offsetNanos

    fun qpcAt(nanos: Long): Long =
        Math.round((nanos - offsetNanos).toDouble() / 1e9 * qpcFrequency)

    override fun toString(): String =
        "QPC ${qpcFrequency} Hz, nanoTime offset ${offsetNanos} ns (+-${uncertaintyNanos})"

    companion object {
        /**
         * Reads both clocks until the pair has been caught close enough together to be useful.
         *
         * Each reading sandwiches a QPC read between two `nanoTime` reads, so the truth lies
         * inside that sandwich and the sandwich's width is the error. The narrowest of many is
         * kept rather than the average of them: an interrupt between two reads can only widen a
         * sandwich and push its midpoint later, never the other way, so the mean of a batch
         * carries the scheduler's tail into the answer while the minimum does not.
         */
        fun measure(arena: Arena, samples: Int = 500): DesktopClock {
            val scratch = arena.allocate(8, 8)
            val frequency = Wasapi.qpcFrequency(scratch)
            var bestWidth = Long.MAX_VALUE
            var bestOffset = 0L
            repeat(samples) {
                val before = System.nanoTime()
                val qpc = Wasapi.qpc(scratch)
                val after = System.nanoTime()
                val width = after - before
                if (width < bestWidth) {
                    bestWidth = width
                    bestOffset = (before + after) / 2 - Math.round(qpc.toDouble() / frequency * 1e9)
                }
            }
            return DesktopClock(frequency, bestOffset, bestWidth)
        }
    }
}
