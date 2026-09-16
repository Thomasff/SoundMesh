package com.soundmesh.product

import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.soundmesh.probe.R

/**
 * What the scanner is doing, and how each of those reads.
 *
 * Five states rather than a line of text, because four of them are about the camera rather than
 * about a code: a scanner nobody granted the camera to, one the system would not open, one aimed
 * at somebody's takeaway menu and one that simply has not found anything yet all look identical
 * from a metre away, and they are four different things to walk over and fix.
 */
enum class ScanSay(val text: Int, val tone: Tone) {
    LOOKING(R.string.scan_looking, Tone.QUIET),
    NOT_OURS(R.string.scan_not_ours, Tone.WATCH),
    SCANNED(R.string.scan_done, Tone.GOOD),
    NO_PERMISSION(R.string.scan_no_permission, Tone.WRONG),
    NO_CAMERA(R.string.scan_no_camera, Tone.WRONG)
}

/**
 * Whether the camera's picture belongs on the screen.
 *
 * Only while the camera is still looking. A preview whose camera has been put down keeps the last
 * frame it was handed and goes on showing it, so the screen a scan ends on looks exactly like the
 * screen it started on - a moving-looking picture of another phone's code, over a line saying it
 * has been read that nobody looks at, because the picture already said the scanner is still busy.
 *
 * The three refusals are the same case from the other end: there is no camera behind the preview
 * at all, and an empty black rectangle reads as one that is working.
 */
internal fun previewShown(say: ScanSay): Boolean =
    say == ScanSay.LOOKING || say == ScanSay.NOT_OURS

/**
 * The scanner, which is the one screen in this app somebody holds in their hand and aims.
 *
 * [preview] hands over the camera's own view rather than making one here: it is what the camera is
 * bound to, it outlives any one composition, and nothing about it is drawn by this file.
 *
 * Three ways out, all of them [onBack]: the handset's own back, the arrow at the top, and anywhere
 * on the page that is not the picture. This is the one screen in the app somebody is holding at
 * arm's length and pointing at another phone, with their attention on that phone rather than on
 * this one, and a way out they have to aim for is a way out they will not find. No ripple under
 * the tap, because a whole page lighting up says the page is a button.
 */
@Composable
fun ScanScreen(say: ScanSay, preview: () -> View, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack
            )
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        // Nothing between blocks: a label carries its own space above it. See Look.kt.
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        PageBar(R.string.scan_title, onBack)
        Note(stringResource(R.string.scan_hint))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp),
            shape = RoundedCornerShape(12.dp),
            // The ground behind the picture, and what is left when there is no picture. Read off
            // the scheme so that an unopened camera is a quiet panel in whichever theme is on
            // rather than a black hole with a red line under it.
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            if (previewShown(say)) AndroidView(factory = { preview() }, modifier = Modifier.fillMaxSize())
        }
        Note(stringResource(say.text), say.tone)
    }
}
