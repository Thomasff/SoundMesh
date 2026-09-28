package com.soundmesh.product

import java.net.HttpURLConnection
import java.net.URI

/**
 * What 检查更新 on the about block's version line comes back with.
 *
 * Only ever asked when somebody presses it: nothing here reaches GitHub on its own. A newer release
 * is answered with its page, not its file - the page says what changed and holds the package for
 * each platform, and a browser handles a slow or broken download far better than this app would.
 */
sealed interface UpdateAnswer {
    object UpToDate : UpdateAnswer
    data class Newer(val version: String, val page: String) : UpdateAnswer

    /**
     * No network, GitHub unreachable, or no release to compare with - which is also what a private
     * repository answers, since its releases are a 404 to anybody not signed in.
     */
    object Failed : UpdateAnswer
}

/** GitHub's address for a repository's newest release, from the repository's own page, or null. */
fun latestReleaseApi(repoUrl: String): String? {
    val match = Regex("""^https://github\.com/([^/]+)/([^/]+?)/?$""").find(repoUrl.trim()) ?: return null
    val (owner, name) = match.destructured
    return "https://api.github.com/repos/$owner/$name/releases/latest"
}

/**
 * The release's tag, out of the API's answer.
 *
 * One field by name rather than a JSON parser, which core does not have and Android and the
 * desktop would each bring a different one of. The field that is not taken is html_url: it
 * appears again under the author and under every asset, and the page is built from the tag.
 */
fun tagOf(json: String): String? = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

/**
 * Whether [latest] is a later version than [current], part by part as numbers.
 *
 * As numbers because as text 0.10.0 sorts before 0.9.0. A missing part is 0, so v0.3 is 0.3.0.
 */
fun isNewer(latest: String, current: String): Boolean {
    fun parts(name: String) = name.trim().removePrefix("v").split('.')
        .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val a = parts(latest)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * Asks [fetch] for the newest release of [repoUrl] and compares it with [current].
 *
 * [fetch] returns the body, or null for anything but a plain success; one that throws is the same
 * failure. Blocking: call it off the main thread.
 */
fun answerUpdate(repoUrl: String, current: String, fetch: (String) -> String?): UpdateAnswer {
    val api = latestReleaseApi(repoUrl) ?: return UpdateAnswer.Failed
    val body = runCatching { fetch(api) }.getOrNull() ?: return UpdateAnswer.Failed
    val tag = tagOf(body) ?: return UpdateAnswer.Failed
    if (!isNewer(tag, current)) return UpdateAnswer.UpToDate
    return UpdateAnswer.Newer(tag, repoUrl.trim().trimEnd('/') + "/releases/tag/" + tag)
}

/**
 * The real [fetch] for [answerUpdate]: one GET, ten seconds to connect and ten to read.
 *
 * Ten because GitHub from mainland China is often slow rather than down, and a check that gives up
 * sooner says 检查失败 about a network that would have answered. The User-Agent is not optional:
 * GitHub's API refuses a request without one.
 */
fun fetchFromGitHub(url: String): String? {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "SoundMesh")
        if (connection.responseCode != HttpURLConnection.HTTP_OK) null
        else connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
