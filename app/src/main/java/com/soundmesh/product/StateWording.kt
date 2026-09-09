package com.soundmesh.product

import android.os.PowerManager
import androidx.annotation.StringRes
import com.soundmesh.core.SessionState
import com.soundmesh.probe.R

/**
 * What a state is called on a screen a person is standing in front of.
 *
 * A layer of its own, and a pure one, so the mapping can be tested for exhaustiveness. The failure
 * worth catching is a state added to the enum and never given words: it shows up as a screen
 * quietly displaying the fallback rather than as anything that breaks. DEGRADED was added the day
 * before this file and would have landed there.
 *
 * Returns a resource id rather than a string for the same reason - an id can be compared in a JVM
 * test without a device, and two states sharing one is then a failing assertion.
 */
object StateWording {
    @StringRes
    fun of(state: SessionState?): Int = when (state) {
        null, SessionState.IDLE -> R.string.state_idle
        SessionState.SYNCING -> R.string.state_syncing
        SessionState.PLAYING -> R.string.state_playing
        SessionState.DEGRADED -> R.string.state_degraded
        SessionState.RECOVERING -> R.string.state_recovering
        SessionState.SUSPENDED -> R.string.state_suspended
        SessionState.STOPPED -> R.string.state_stopped
    }

    /**
     * A failure code in words, or null when the code itself is the best that can be said.
     *
     * The one code that has words is the one that reached a user as `BindException`: the clock
     * port is held by whichever of the two features got there first, and calibration and a session
     * are both entitled to it. Nothing about the collision is wrong - they are not meant to run at
     * once - but the name of a Java class is not a thing to hand somebody standing in a room with
     * three handsets. Anything else keeps the code, which is at least searchable.
     *
     * Returning null rather than a fallback string, so the caller keeps the code in the message it
     * already had, and so a test can tell "no words for this" from "these words".
     */
    @StringRes
    fun failure(code: String?): Int? = when (code) {
        "BindException" -> R.string.failure_port_in_use
        else -> null
    }

    /**
     * The platform's thermal ladder, in words.
     *
     * Section 11.2 asks for heat to be shown honestly and nothing more - no throttling of our own,
     * no warning. What the system thinks is the interesting half: a charging handset can sit warm
     * at NONE, and a cool one can reach SEVERE right after a burst.
     */
    @StringRes
    fun thermal(status: Int): Int = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> R.string.thermal_none
        PowerManager.THERMAL_STATUS_LIGHT -> R.string.thermal_light
        PowerManager.THERMAL_STATUS_MODERATE -> R.string.thermal_moderate
        PowerManager.THERMAL_STATUS_SEVERE -> R.string.thermal_severe
        PowerManager.THERMAL_STATUS_CRITICAL -> R.string.thermal_critical
        PowerManager.THERMAL_STATUS_EMERGENCY -> R.string.thermal_emergency
        PowerManager.THERMAL_STATUS_SHUTDOWN -> R.string.thermal_shutdown
        else -> R.string.thermal_unknown
    }
}
