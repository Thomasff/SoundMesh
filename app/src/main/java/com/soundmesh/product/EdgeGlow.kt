package com.soundmesh.product

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloat
import com.soundmesh.probe.sync.loudnessOf
import com.soundmesh.session.SessionService

/**
 * Whether this handset is a sink that has dropped off its standing line while nothing is playing.
 *
 * Shared between [edgeGlow] (which picks the flat, unbreathing amplitude for this state) and
 * [HomeScreen] (which dims the edge colour itself for it) so the two cannot disagree about which
 * state they are drawing.
 */
internal fun disconnectedSink(state: HomeState): Boolean = !state.onStandby && state.role == Role.SINK

/**
 * How bright this handset's screen edge should glow right now, 0..1.
 *
 * Three regimes, checked in order:
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
 */
@Composable
internal fun edgeGlow(state: HomeState): Float = when {
    disconnectedSink(state) -> DISCONNECTED_GLOW
    state.running -> playingGlow()
    else -> standbyGlow()
}

/** Follows [SessionService.ACTIVE]'s own loudness, one exponential step per frame. */
@Composable
private fun playingGlow(): Float {
    val smoothed = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withInfiniteAnimationFrameNanos {
                val target = SessionService.ACTIVE?.loudness() ?: 0f
                smoothed.floatValue += LOUDNESS_SMOOTHING * (target - smoothed.floatValue)
            }
        }
    }
    return smoothed.floatValue
}

/** A slow, even breath between [STANDBY_GLOW_MIN] and [STANDBY_GLOW_MAX]. */
@Composable
private fun standbyGlow(): Float {
    val transition = rememberInfiniteTransition(label = "standbyBreath")
    val value by transition.animateFloat(
        initialValue = STANDBY_GLOW_MIN,
        targetValue = STANDBY_GLOW_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(STANDBY_BREATH_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "standbyGlow"
    )
    return value
}

/** How much of the previous frame's smoothed loudness survives into the next one. */
private const val LOUDNESS_SMOOTHING = 0.25f

/** The disconnected sink's fixed, unbreathing amplitude. */
private const val DISCONNECTED_GLOW = 0.15f

private const val STANDBY_GLOW_MIN = 0.35f
private const val STANDBY_GLOW_MAX = 0.75f
private const val STANDBY_BREATH_MILLIS = 2000
