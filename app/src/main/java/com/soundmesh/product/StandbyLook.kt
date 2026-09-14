package com.soundmesh.product

import androidx.annotation.StringRes
import com.soundmesh.probe.R

/**
 * What a handset's icon is saying.
 *
 * Four states rather than one hollow circle. They are four different faults that are checked in
 * four different places, and on the evening of 2026-09-14 an hour went into telling two of them
 * apart because the screen drew them identically.
 */
enum class StandbyLook { FOLLOWING, ASLEEP, GONE, KILLED }

/**
 * [saidNotExempt] is what that handset REPORTED about itself, not something this host worked out.
 * Both halves have to be true before calling it a kill: a disconnection on its own says nothing
 * about the reason, and guessing the reason is how the previous evening was spent.
 */
internal fun standbyLook(
    connected: Boolean,
    screenOn: Boolean,
    saidNotExempt: Boolean
): StandbyLook = when {
    connected && screenOn -> StandbyLook.FOLLOWING
    connected -> StandbyLook.ASLEEP
    saidNotExempt -> StandbyLook.KILLED
    else -> StandbyLook.GONE
}

/**
 * What to tell somebody whose handset keeps being stopped, or null when nothing needs saying.
 *
 * The second case is the half that nothing can query. The vendor switches - an app launch
 * manager's three toggles, and every equivalent - have no API: they cannot be read, requested, or
 * linked to. So the only way to know they are the problem is that the standard exemption was
 * granted and the handset was killed anyway, which is a fact about behaviour rather than a
 * reading. That is why this takes [killedAnyway] instead of asking the system anything.
 */
@StringRes
internal fun vendorAdvice(exempt: Boolean, killedAnyway: Boolean): Int? = when {
    !killedAnyway -> null
    exempt -> R.string.vendor_launch_manager
    else -> R.string.vendor_try_exemption
}
