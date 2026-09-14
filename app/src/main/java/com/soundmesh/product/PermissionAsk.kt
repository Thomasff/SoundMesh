package com.soundmesh.product

/** The three things that can happen when somebody taps a permission row. */
enum class AskRoute { DIALOG, SETTINGS, NOTHING }

/**
 * How to ask for a permission, given whether it is held and whether it has been asked for before.
 *
 * Asking twice is the case worth care. Android stops showing the dialog after a refusal that was
 * marked "don't ask again", and it does not tell the app which refusal that was - so launching it
 * a second time is, quite often, a button that does nothing at all. The settings page always
 * works. [askedBefore] is remembered in [Preferences] rather than asked of the system, because
 * the system has no answer to that question.
 */
internal fun askRoute(granted: Boolean, askedBefore: Boolean): AskRoute = when {
    granted -> AskRoute.NOTHING
    askedBefore -> AskRoute.SETTINGS
    else -> AskRoute.DIALOG
}
