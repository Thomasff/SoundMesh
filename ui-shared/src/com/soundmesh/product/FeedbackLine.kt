package com.soundmesh.product

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler

/**
 * 反馈与建议, under the version line: [email] on the right, opening the mail app with [subject]
 * and [device] filled in - see [feedbackMailto].
 *
 * Only the address taps, as on the author and repository rows below it. Where nothing takes a
 * mailto address the open throws, and [noMailApp] goes under the line: the address is still on
 * screen to be written to by hand, which is why it is the address shown and not a word like 发邮件.
 */
@Composable
fun FeedbackLine(label: String, email: String, subject: String, device: String, noMailApp: String) {
    val open = LocalUriHandler.current
    var failed by remember { mutableStateOf(false) }
    Line {
        LineName(label, quiet = true)
        Text(
            email,
            modifier = Modifier.clickable {
                failed = runCatching { open.openUri(feedbackMailto(email, subject, device)) }.isFailure
            },
            style = MaterialTheme.typography.bodyMedium,
            color = linkColour()
        )
    }
    if (failed) Note(noMailApp, Tone.WATCH)
}
