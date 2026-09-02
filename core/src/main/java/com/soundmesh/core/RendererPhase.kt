package com.soundmesh.core

/**
 * SyncRenderer's drift-sampling phase.
 *
 * PlaybackScheduler.poll only releases a chunk once its instant has arrived, so a device's own
 * release cadence leaves a startup release-phase error of up to one whole chunk period (~20ms) -
 * three orders of magnitude bigger than the ~20ppm steady-state crystal drift DriftController's
 * 1Hz, one-frame correction budget is sized for. ACQUIRING samples and corrects on every chunk to
 * work that startup error off quickly; TRACKING then holds the spec's slower 1Hz cadence, which is
 * all steady-state drift needs.
 */
enum class RendererPhase { ACQUIRING, TRACKING }

/** [consecutiveInDeadband] only means anything while ACQUIRING; TRACKING no longer tracks it. */
data class PhaseState(val phase: RendererPhase, val consecutiveInDeadband: Int) {
    companion object {
        val INITIAL = PhaseState(RendererPhase.ACQUIRING, 0)
    }
}

/** Matches DriftController.MEDIAN_WINDOW: convergence means the whole median window agrees. */
const val REQUIRED_CONSECUTIVE_IN_DEADBAND = 5

/**
 * Advances the ACQUIRING/TRACKING state machine by one drift sample.
 *
 * ACQUIRING switches to TRACKING once [requiredConsecutive] samples in a row have landed inside
 * DriftController's deadband - one lucky reading is not convergence, so this asks for a run as
 * long as the median filter's own window, meaning the whole window agrees rather than just its
 * median.
 *
 * TRACKING is sticky here: a later excursion outside the deadband is read as ordinary
 * steady-state noise the 1Hz loop is already built to correct, not a sign acquisition must run
 * again. Falling back to ACQUIRING on a later wide excursion would also be a reasonable choice;
 * this implementation does not do it, so a device that reconverges keeps the slower cadence for
 * the rest of the run.
 */
fun nextPhaseState(
    current: PhaseState,
    inDeadband: Boolean,
    requiredConsecutive: Int = REQUIRED_CONSECUTIVE_IN_DEADBAND
): PhaseState {
    if (current.phase == RendererPhase.TRACKING) return current
    val consecutive = if (inDeadband) current.consecutiveInDeadband + 1 else 0
    val phase = if (consecutive >= requiredConsecutive) RendererPhase.TRACKING else RendererPhase.ACQUIRING
    return PhaseState(phase, consecutive)
}
