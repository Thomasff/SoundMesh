package com.soundmesh.core

/**
 * How much of the lead was left, gathered over a window and handed back as one line.
 *
 * Here for the question a fixed lead cannot answer by itself: how short
 * could it be. A chunk dropped as late says only that the lead was too short that once; what says
 * how much shorter it could have been is how little was left when each chunk got where it was
 * going, and the lowest of those is where a WiFi stall shows. Recorded, not acted on: nothing here
 * changes what is played.
 *
 * Every sample is kept until the window closes rather than binned, because a window is a few
 * hundred chunks and the one number that matters is its minimum.
 */
class LeadMeter(private val label: String, private val windowNanos: Long = WINDOW_NANOS) {
    private val samples = ArrayList<Long>()
    private var windowStartNanos = 0L

    /**
     * Adds one reading taken at [nowNanos], and answers the window's line once the window has
     * gone by - null until then. The window opens at its first reading, so a stream that stopped
     * for a while does not come back to a window that closed while it was away.
     */
    @Synchronized
    fun record(valueNanos: Long, nowNanos: Long): String? {
        if (samples.isEmpty()) windowStartNanos = nowNanos
        samples.add(valueNanos)
        if (nowNanos - windowStartNanos < windowNanos) return null
        samples.sort()
        // Nearest-rank, as ChunkPlayout's band: no value is invented between two that were read.
        fun at(quantile: Double) = samples[(kotlin.math.ceil(quantile * samples.size).toInt() - 1).coerceAtLeast(0)] / 1_000_000L
        val line = "$label n=${samples.size} min ${samples.first() / 1_000_000L} p1 ${at(0.01)} " +
            "p50 ${at(0.5)} max ${samples.last() / 1_000_000L} ms"
        samples.clear()
        return line
    }

    companion object {
        /** Ten seconds: a line an evening is read by, a few hundred chunks each. Picked. */
        const val WINDOW_NANOS = 10_000_000_000L
    }
}
