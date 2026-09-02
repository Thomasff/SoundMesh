package com.soundmesh.core

/**
 * One clock exchange. t1 and t4 are on the asking clock, t2 and t3 on the answering clock.
 *
 * A single exchange does not pin the offset down to a value; it pins it to the interval
 * [t3 - t4, t2 - t1], whose width is the round trip. The midpoint is the best guess only
 * when the path is symmetric, which is why the estimator prefers the shortest round trips.
 */
data class ClockExchange(val t1: Long, val t2: Long, val t3: Long, val t4: Long) {
    val roundTripNanos: Long get() = (t4 - t1) - (t3 - t2)
    val offsetMidpointNanos: Long get() = ((t2 - t1) + (t3 - t4)) / 2
}

/** Offset of the answering clock relative to the asking one, valid around the queried instant. */
data class ClockEstimate(
    val offsetNanos: Long,
    val uncertaintyNanos: Long,
    val driftPpm: Double,
    val sampleCount: Int
)
