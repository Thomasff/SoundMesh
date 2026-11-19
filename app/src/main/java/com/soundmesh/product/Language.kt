package com.soundmesh.product

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * Which language the app draws itself in.
 *
 * [SYSTEM] is whatever the handset is set to, which is what somebody who never opens the settings
 * gets - English where that is neither of the two, see [systemTag]. The other two are a person
 * saying otherwise, and an explicit choice wins in BOTH
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
 * Returns `this` untouched for [LanguageChoice.SYSTEM] on a Chinese phone rather than pinning the
 * system's current locale: a phone whose language is changed while this app sits in the background
 * should come back in the new one, and a context built from the old configuration would not.
 */
fun Context.inChosenLanguage(): Context = inLanguage(languageChoiceOf(Preferences(filesDir).read(LANGUAGE_KEY)))

/**
 * The same, for a choice already in hand; null is no choice at all and changes nothing.
 *
 * The settings screen needs this one: it changes the language of a screen that is already up, and
 * `attachBaseContext` has long since run. See [HomeActivity]'s own note on why that screen hands
 * the localised context down through the composition instead of restarting itself.
 */
fun Context.inLanguage(choice: LanguageChoice?): Context {
    if (choice == null) return this
    val tag = choice.tag ?: systemTag(systemLanguage()) ?: return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return createConfigurationContext(configuration)
}

/**
 * What [LanguageChoice.SYSTEM] comes to on a phone set to [systemLanguage]: nothing to pin on a
 * Chinese one, English on any other.
 *
 * Left to the resource rule, a phone in a language this app has no strings for falls through to
 * `values/`, which is the Chinese one - and the app is shared in English, to people whose phones
 * are mostly in neither. Decided 2026-11-20.
 */
internal fun systemTag(systemLanguage: String): String? = if (systemLanguage == "zh") null else "en"

/**
 * The phone's own first language, read from the system rather than from a context: a context here
 * may already carry a language picked in settings, which is not the phone's.
 */
private fun systemLanguage(): String = Resources.getSystem().configuration.locales[0].language
