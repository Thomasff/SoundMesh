package com.soundmesh.product

/**
 * Which of the three screens this handset is on.
 *
 * Worked out from the state rather than remembered, so there is one answer rather than two that
 * can disagree. Settings and the full-screen pairing code are NOT in here: those are places
 * somebody goes and comes back from, held separately in the activity, precisely because they
 * have to survive the state changing underneath them.
 */
enum class HomeRoute { WELCOME, READY, PLAYING }

internal fun routeOf(state: HomeState): HomeRoute = when {
    // The role decides even against a running session: the welcome screen is the only one that
    // can supply a missing role, so it has to win that argument.
    state.role == Role.NONE -> HomeRoute.WELCOME
    state.running -> HomeRoute.PLAYING
    else -> HomeRoute.READY
}

/**
 * An optional build fact, or null where nobody has set one.
 *
 * Blank counts as unset. Author, repository, licence and release page are all undecided as of
 * 2026-09-15, and every screen that shows one has to render nothing at all rather than a blank
 * line, an empty link, or the word "TODO".
 */
internal fun configured(value: String): String? = value.trim().ifEmpty { null }
