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

/**
 * [steppedBack] is somebody having pressed back out of the playing stage, which leaves a running
 * session and nobody looking at it. [holding] is the other way round: nothing is playing, and the
 * person is on the playing stage anyway because they just changed what the room will play from
 * there. Both are parameters rather than fields on [HomeState] for the same reason settings and
 * the pairing code are held in the activity: they say where a person is standing, not what this
 * handset is doing, and the state underneath them goes on changing.
 *
 * Without [holding], picking a different source while the music was on dropped somebody two
 * stages back to find the play button again - reported 2026-09-15, right after the same journey
 * had been fixed for a cancelled pick.
 */
internal fun routeOf(
    state: HomeState,
    steppedBack: Boolean = false,
    holding: Boolean = false
): HomeRoute = when {
    // The role decides even against a running session: the welcome screen is the only one that
    // can supply a missing role, so it has to win that argument.
    state.role == Role.NONE -> HomeRoute.WELCOME
    steppedBack -> HomeRoute.READY
    state.running || holding -> HomeRoute.PLAYING
    else -> HomeRoute.READY
}

/**
 * Whether somebody watching the room is still watching it, at the moment the room is put down.
 *
 * The answer is the stage they are on, not whether anything is playing. Those look the same for
 * the first switch and differ for every one after it: the second switch in a row is made from a
 * stage that was already put down once, where nothing is playing and the person is standing there
 * regardless - so asking `running` answered "nowhere" and sent them back to the board. Reported
 * 2026-09-15, one round after [routeOf] gained [holding] for the first switch.
 */
internal fun stillWatching(state: HomeState, steppedBack: Boolean, holding: Boolean): Boolean =
    routeOf(state, steppedBack, holding) == HomeRoute.PLAYING

/**
 * An optional build fact, or null where nobody has set one.
 *
 * Blank counts as unset. The release page is the one still undecided, and every screen that shows
 * one of these has to render nothing at all for it rather than a blank line, an empty link, or the
 * word "TODO".
 */
internal fun configured(value: String): String? = value.trim().ifEmpty { null }
