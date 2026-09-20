package com.soundmesh.product

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import com.soundmesh.session.SessionService
import kotlin.math.exp

/**
 * Whether this handset is a sink that has dropped off its standing line while nothing is playing.
 *
 * Shared between [rememberEdgeGlow] (which picks the flat, unbreathing amplitude for this state)
 * and [HomeScreen] (which dims the edge colour itself for it) so the two cannot disagree about
 * which state they are drawing.
 */
internal fun disconnectedSink(state: HomeState): Boolean = !state.onStandby && state.role == Role.SINK

/**
 * What the edge is doing right now. One of four, and the one thing a frame loop is keyed on.
 *
 * [DARK] is the state this handset spends most of its life in, and the reason this is an enum at
 * all rather than a boolean and a float: a handset with no colour draws no edge, so there is
 * nothing for a frame loop to feed and the loop should not be running. See [rememberEdgeGlow].
 */
private enum class Glow { DARK, DISCONNECTED, PLAYING, STANDBY }

/**
 * How bright this handset's screen edge should glow right now, 0..1 - as something to call once a
 * frame from inside a draw, **not** as a value.
 *
 * Three lit regimes, checked in order:
 * - **disconnected** - a sink whose standing line is down. Fixed and low: the breathing stops
 *   rather than keeps miming a room this handset is no longer part of.
 * - **playing** - read off [SessionService.ACTIVE]'s own loudness every frame, smoothed so the
 *   edge follows the music rather than flickering with every 20ms chunk.
 * - **standing by, not playing** - a slow, even breath, so a phone left in a corner still reads
 *   as alive from across the room.
 *
 * Deliberately not read off [HomeState] itself: the screen's own state is polled five times a
 * second, which is far too slow for something meant to move with the music, and lifting that
 * whole poll to a frame rate to carry one float would put the cost of this decoration on a path
 * that has to stay cheap for reasons that have nothing to do with it. This reads the renderer's
 * volatile directly instead - see [com.soundmesh.probe.sync.SyncRenderer.loudness].
 *
 * ────────────────────────────────────────────────────────────────────────────
 * Why this hands back a function instead of a Float, and why [lit] exists
 * ────────────────────────────────────────────────────────────────────────────
 * Until 2026-09-20 this returned the Float, and HomeScreen read it. A value that changes every
 * frame, read during composition, recomposes whatever read it - here the whole of [HomeScreen],
 * sixty times a second, or a hundred and twenty on a panel that fast. Measured on the X10
 * (Android 10, 60Hz), parked on the role-picker with no colour and **no edge drawn at all**:
 *
 *     main thread 20.5% of a core, render thread 0.0%, frames rendered in 30s: 0
 *
 * Nothing was being drawn. All of it was composition being re-run to arrive at the same screen.
 * Deleting the call took the main thread to 2.3%, which is what attributes those eighteen points
 * to this file rather than to anything else on the screen.
 *
 * Two things fix it, and both are needed:
 * - the float is read inside the draw lambda (see `badgeEdge`), where a read invalidates the draw
 *   and not the composition;
 * - [lit] is false when there is no colour to draw, and then no frame loop runs at all. Without
 *   it a handset that draws no edge would still wake sixty times a second to move a number
 *   nothing reads, which is the same waste in a cheaper suit.
 */
@Composable
internal fun rememberEdgeGlow(state: HomeState, lit: Boolean): () -> Float {
    val glow = remember { mutableFloatStateOf(STANDBY_GLOW_MIN) }
    val regime = when {
        !lit -> Glow.DARK
        disconnectedSink(state) -> Glow.DISCONNECTED
        state.running -> Glow.PLAYING
        else -> Glow.STANDBY
    }
    LaunchedEffect(regime) {
        when (regime) {
            // No loop: nothing on screen is reading this, and the frame the loop would ask for is
            // a frame the whole app would otherwise not have drawn.
            Glow.DARK -> Unit
            Glow.DISCONNECTED -> glow.floatValue = DISCONNECTED_GLOW
            Glow.PLAYING -> followLoudness(glow)
            Glow.STANDBY -> breathe(glow)
        }
    }
    return remember(glow) { { glow.floatValue } }
}

/**
 * Follows [SessionService.ACTIVE]'s own loudness, one exponential step per frame.
 *
 * ★ The step is a time constant, not a per-frame fraction. It used to be `smoothed += 0.25f *
 * (target - smoothed)`, which is a different amount of smoothing on every refresh rate the app
 * runs at - the edge was twice as sluggish on the 120Hz handset as on the 60Hz one, for no reason
 * anybody chose. [LOUDNESS_TAU_SECONDS] is picked so that at 60Hz this is the old 0.25 to three
 * decimal places, which is what makes the change a no-op on the handset it was tuned on.
 *
 * Starts from zero rather than from whatever the breath left behind, which is what it did before:
 * the edge easing up from dark as the first bar plays is the behaviour, not an artefact of how it
 * used to be written.
 */
private suspend fun followLoudness(glow: MutableFloatState) {
    glow.floatValue = 0f
    var previous: Long? = null
    while (true) {
        withInfiniteAnimationFrameNanos { now ->
            val last = previous
            previous = now
            val target = SessionService.ACTIVE?.loudness() ?: 0f
            if (last != null) {
                val dt = (now - last) * 1e-9f
                val k = 1f - exp(-dt / LOUDNESS_TAU_SECONDS)
                glow.floatValue += k * (target - glow.floatValue)
            }
        }
    }
}

/**
 * A slow, even breath between [STANDBY_GLOW_MIN] and [STANDBY_GLOW_MAX].
 *
 * Written out rather than left to `rememberInfiniteTransition`, which hands back a State that has
 * to be read somewhere - and the only place to read it was composition, which is the whole of the
 * problem [rememberEdgeGlow] describes. The shape is the same one it had: linear up over
 * [STANDBY_BREATH_MILLIS], linear back down over the same, forever.
 */
private suspend fun breathe(glow: MutableFloatState) {
    val half = STANDBY_BREATH_MILLIS * 1_000_000L
    var start: Long? = null
    while (true) {
        withInfiniteAnimationFrameNanos { now ->
            val from = start ?: now.also { start = it }
            // 0..2 through one full there-and-back, so the fold below needs no branch on direction.
            val at = ((now - from) % (2L * half)).toFloat() / half
            val up = if (at <= 1f) at else 2f - at
            glow.floatValue = STANDBY_GLOW_MIN + (STANDBY_GLOW_MAX - STANDBY_GLOW_MIN) * up
        }
    }
}

/**
 * The time constant the playing edge follows the music with.
 *
 * Not a guess and not a taste: it is the value that reproduces the per-frame 0.25 this replaced,
 * at the 60Hz it was tuned at. `1 - exp(-(1/60) / 0.058) = 0.2504`. Every other refresh rate now
 * gets the same responsiveness in seconds instead of the same fraction per frame.
 */
private const val LOUDNESS_TAU_SECONDS = 0.058f

/** The disconnected sink's fixed, unbreathing amplitude. */
private const val DISCONNECTED_GLOW = 0.15f

private const val STANDBY_GLOW_MIN = 0.35f
private const val STANDBY_GLOW_MAX = 0.75f
private const val STANDBY_BREATH_MILLIS = 2000L
