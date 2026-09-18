package com.soundmesh.product

import androidx.annotation.StringRes
import com.soundmesh.probe.R

/**
 * What a handset's icon is saying.
 *
 * Three states rather than one hollow circle. They are three different faults that are checked in
 * three different places, and on the evening of 2026-09-14 an hour went into telling two of them
 * apart because the screen drew them identically.
 *
 * There was a fourth, ASLEEP, drawn dim for a handset that was following with its screen off. It
 * went on 2026-09-18, and the reason is worth keeping: it was added on the night the screen was
 * still the suspect, and that night ended by clearing the screen entirely - the same fault
 * reproduced with the screen on and the app merely in the background. A dark screen changes
 * nothing about playing, so drawing it as a state told a listener their handset was in trouble
 * when it was not. It was also unreachable in the code that drew it, which is how a state that
 * says nothing survives: nothing ever showed it to be questioned.
 */
enum class StandbyLook { FOLLOWING, GONE, KILLED }

/**
 * [saidNotExempt] is what that handset REPORTED about itself, not something this host worked out.
 * Both halves have to be true before calling it a kill: a disconnection on its own says nothing
 * about the reason, and guessing the reason is how the previous evening was spent.
 */
internal fun standbyLook(
    connected: Boolean,
    saidNotExempt: Boolean
): StandbyLook = when {
    connected -> StandbyLook.FOLLOWING
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
