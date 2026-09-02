package com.soundmesh.core

data class DriftDecision(val adjustFrames: Int, val filteredErrorFrames: Int)

/**
 * Decides how to nudge playback back onto the shared timeline.
 *
 * Devices mix isolated stale position readings into an otherwise clean stream, so the raw
 * error is median filtered before it reaches the deadband. The deadband must stay well above
 * the measurement noise: a controller that chases its own noise jitters worse than one that
 * does nothing.
 */
class DriftController(
    private val deadbandFrames: Int = DEFAULT_DEADBAND_FRAMES,
    private val maxAdjustFrames: Int = 1
) {
    private val recent = ArrayDeque<Int>()

    @Synchronized
    fun observe(errorFrames: Int): DriftDecision {
        recent.addLast(errorFrames)
        while (recent.size > MEDIAN_WINDOW) recent.removeFirst()
        val sorted = recent.sorted()
        val filtered = sorted[sorted.size / 2]
        val adjust = when {
            filtered > deadbandFrames -> maxAdjustFrames
            filtered < -deadbandFrames -> -maxAdjustFrames
            else -> 0
        }
        return DriftDecision(adjust, filtered)
    }

    companion object {
        const val MEDIAN_WINDOW = 5
        /** One millisecond at 48 kHz, roughly twenty times the measured noise floor. */
        const val DEFAULT_DEADBAND_FRAMES = 48
    }
}
