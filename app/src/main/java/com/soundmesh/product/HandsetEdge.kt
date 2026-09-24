package com.soundmesh.product

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.soundmesh.session.SessionService

/**
 * The two things about the edge light (ui-shared's EdgeGlow.kt and EdgeWave.kt) that only a
 * handset has: which of its states the light is drawing, and how round its glass is.
 */

/**
 * Whether this handset is a sink that has dropped off its standing line while nothing is playing.
 *
 * Shared between [rememberEdgeGlow] (which picks the flat, unbreathing amplitude for this state)
 * and [HomeScreen] (which dims the edge colour itself for it) so the two cannot disagree about
 * which state they are drawing.
 */
internal fun disconnectedSink(state: HomeState): Boolean = !state.onStandby && state.role == Role.SINK

/** The edge light for this handset's [state], following [SessionService.ACTIVE]'s own loudness. */
@Composable
internal fun rememberEdgeGlow(state: HomeState, lit: Boolean): EdgeGlow =
    rememberEdgeGlow(lit, disconnectedSink(state), state.running) { SessionService.ACTIVE?.loudness() ?: 0f }

/**
 * The radius of this screen's own rounded corner, in pixels.
 *
 * Android has only known its own corner radius since API 31, and it is not a number that can be
 * derived from anything else - it is a property of the glass. So: ask when there is something to
 * ask, and otherwise use a radius typical of the handsets this runs on. Getting it wrong by a few
 * dp costs nothing visible; the light is 10dp in from the edge and its own bend is that much
 * gentler than the glass's.
 *
 * Clamped at the top because EDGE_NODE has to contain the deepest point of the arc, which sits
 * where the two bands meet, at `round - (round - restingDepth)/√2` in from the edge.
 */
internal fun screenCornerPx(view: View, density: Density): Float {
    val asked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
    } else {
        null
    }
    return with(density) {
        (asked ?: CORNER_FALLBACK_DP.toPx()).coerceIn(0f, CORNER_MOST_DP.toPx())
    }
}

/**
 * What to assume the glass is rounded by when the platform will not say, and how round it is
 * allowed to claim to be.
 *
 * The fallback is the middle of the range handsets of this generation actually use. The ceiling is
 * what EDGE_NODE can hold; a phone rounder than this gets a slightly tighter bend than its glass,
 * which is invisible next to the sharp corner it replaces.
 */
private val CORNER_FALLBACK_DP = 32.dp
private val CORNER_MOST_DP = 48.dp
