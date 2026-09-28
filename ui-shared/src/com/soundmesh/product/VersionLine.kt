package com.soundmesh.product

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The words of 检查更新, handed in because the two windows each read their words their own way.
 *
 * [download] is the unfilled template, 下载 %1$s: both windows hand back a string as written when
 * given no arguments, and the version is only known here.
 */
class UpdateWords(
    val check: String,
    val checking: String,
    val upToDate: String,
    val download: String,
    val failed: String
)

/**
 * The about block's first line: [text], the version and build, with 检查更新 at its right end.
 *
 * Everything happens on the line itself rather than in a dialog. Pressed, the chip becomes
 * 检查中… and then one of three things: 已是最新版本 in its place; a filled 下载 vX.Y.Z that opens
 * that release's page in the browser; or the chip back again with 检查失败 under the line, so it
 * can be pressed again. Only a press reaches GitHub - nothing is checked on opening the page.
 *
 * No chip where [repoUrl] is not a GitHub repository, which includes unset: a button that could
 * only ever fail is worse than none, the same rule the rows under this one follow.
 */
@Composable
fun VersionLine(text: String, version: String, repoUrl: String, words: UpdateWords) {
    val open = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<UpdateAnswer?>(null) }
    val checkable = latestReleaseApi(repoUrl) != null
    Line(first = true) {
        LineName(text, quiet = true)
        val newer = answer as? UpdateAnswer.Newer
        when {
            !checkable -> {}
            checking -> Tag(words.checking)
            answer == UpdateAnswer.UpToDate -> Tag(words.upToDate)
            newer != null -> FilledChip(String.format(Locale.ROOT, words.download, newer.version)) {
                runCatching { open.openUri(newer.page) }
            }
            else -> Chip(words.check) {
                checking = true
                answer = null
                scope.launch {
                    answer = withContext(Dispatchers.IO) { answerUpdate(repoUrl, version, ::fetchFromGitHub) }
                    checking = false
                }
            }
        }
    }
    if (!checking && answer == UpdateAnswer.Failed) Note(words.failed, Tone.WATCH)
}
