package com.soundmesh.product

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Which language the app draws itself in.
 *
 * [SYSTEM] is whatever the handset is set to, which is what somebody who never opens the settings
 * gets. The other two are a person saying otherwise, and an explicit choice wins in BOTH
 * directions - an "English" that goes back to Chinese because the phone is Chinese is not a
 * setting. Same rule as [ThemeChoice], for the same reason.
 *
 * [tag] is the BCP 47 tag the resources are asked for. Chinese asks for `zh` and lands on the
 * default `values/`, which is the Chinese one: there is no `values-zh`, and adding one would be a
 * second copy of every string in the app to keep in step with the first.
 */
enum class LanguageChoice(val tag: String?) { SYSTEM(null), CHINESE("zh"), ENGLISH("en") }

internal fun languageChoiceOf(stored: String?): LanguageChoice =
    LanguageChoice.entries.firstOrNull { it.name == stored } ?: LanguageChoice.SYSTEM

/** What the settings screen writes and everything below reads. */
const val LANGUAGE_KEY = "language"

/**
 * The same context with the chosen language on it, or the same context where nobody has chosen.
 *
 * Called from `attachBaseContext`, which is the one hook that runs before anything has read a
 * string: an Activity's resources come from the base context it was attached to, and so do a
 * Service's. There is no androidx.appcompat here to do it, and the platform's own per-app
 * language needs API 33 while this app runs from 29 - so it is done by hand, which works
 * everywhere and is four lines.
 *
 * Returns `this` untouched for [LanguageChoice.SYSTEM] rather than pinning the system's current
 * locale: a phone whose language is changed while this app sits in the background should come
 * back in the new one, and a context built from the old configuration would not.
 */
fun Context.inChosenLanguage(): Context = inLanguage(languageChoiceOf(Preferences(filesDir).read(LANGUAGE_KEY)))

/**
 * The same, for a choice already in hand.
 *
 * The settings screen needs this one: it changes the language of a screen that is already up, and
 * `attachBaseContext` has long since run. See [HomeActivity]'s own note on why that screen hands
 * the localised context down through the composition instead of restarting itself.
 */
fun Context.inLanguage(choice: LanguageChoice): Context {
    val tag = choice.tag ?: return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return createConfigurationContext(configuration)
}
