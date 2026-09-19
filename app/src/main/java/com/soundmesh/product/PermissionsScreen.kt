package com.soundmesh.product

import android.Manifest
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soundmesh.probe.R

/**
 * The synthetic key for the one row that is not a runtime permission at all - ignoring battery
 * optimizations is asked for through [android.provider.Settings], not
 * [android.app.Activity.requestPermissions] - so there is no [Manifest] constant for it. Kept
 * beside the three real ones so every row can be driven off a single `String` key: the same key
 * names the row in [held] / `askedBefore`, and the same key is handed back through `onAsk`.
 */
const val PERMISSION_BACKGROUND = "background"

/**
 * The screen that asks for what the app needs and says in one short line what each thing is for.
 *
 * Shown unprompted on the first launch, and reachable from the settings screen at any time after
 * it. The second way in is what makes the first one safe to tap past: without it a refusal on the
 * first evening was permanent as far as this app was concerned, and the only asks left were the
 * ones that fire at the moment the thing is needed.
 *
 * [doneLabel] is the only difference between the two ways in. The first launch is on its way
 * somewhere and says so; a visit from settings is not going anywhere and should not pretend to be.
 *
 * A refusal here never blocks [onDone]: every permission is asked for again, on its own, at the
 * moment it is actually needed - see the two asks still in [HomeActivity.captureAudio] and
 * [HomeActivity.play]. This screen exists only to ask early and explain why, not to gate anything.
 *
 * [held] is which of the four are granted right now, and `askedBefore` is which of them this
 * screen has already asked for once - both read by the caller from [Preferences] and the platform,
 * because this screen stays a pure render of the two, the same split the rest of this app's
 * screens keep. Tapping a row calls [onAsk] with that row's key; what that tap actually does -
 * open the system dialog, or send the person to Settings - is [askRoute]'s decision, made by the
 * caller so this screen never has to know Android's own rules for asking twice.
 */
@Composable
fun PermissionsScreen(
    held: Set<String>,
    askedBefore: Set<String>,
    onAsk: (String) -> Unit,
    onDone: () -> Unit,
    @StringRes doneLabel: Int = R.string.perm_continue
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleLarge)

        PermissionRow(
            R.string.perm_mic, R.string.perm_mic_why,
            granted = Manifest.permission.RECORD_AUDIO in held,
            askedBefore = Manifest.permission.RECORD_AUDIO in askedBefore,
            onAsk = { onAsk(Manifest.permission.RECORD_AUDIO) }
        )
        PermissionRow(
            R.string.perm_notify, R.string.perm_notify_why,
            granted = Manifest.permission.POST_NOTIFICATIONS in held,
            askedBefore = Manifest.permission.POST_NOTIFICATIONS in askedBefore,
            onAsk = { onAsk(Manifest.permission.POST_NOTIFICATIONS) }
        )
        PermissionRow(
            R.string.perm_camera, R.string.perm_camera_why,
            granted = Manifest.permission.CAMERA in held,
            askedBefore = Manifest.permission.CAMERA in askedBefore,
            onAsk = { onAsk(Manifest.permission.CAMERA) }
        )
        PermissionRow(
            R.string.perm_background, R.string.perm_background_why,
            granted = PERMISSION_BACKGROUND in held,
            askedBefore = PERMISSION_BACKGROUND in askedBefore,
            onAsk = { onAsk(PERMISSION_BACKGROUND) }
        )

        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(doneLabel))
        }
    }
}

/**
 * One row: a name, a one-line reason, and a button whose label is [askRoute]'s answer.
 *
 * The button disables itself rather than disappearing when nothing is left to ask - a row that
 * vanished on grant would read as the permission itself having gone away.
 */
@Composable
private fun PermissionRow(
    @StringRes name: Int,
    @StringRes why: Int,
    granted: Boolean,
    askedBefore: Boolean,
    onAsk: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(name), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(why), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        when (askRoute(granted, askedBefore)) {
            AskRoute.NOTHING -> Text(stringResource(R.string.perm_granted), style = MaterialTheme.typography.bodyMedium)
            AskRoute.DIALOG -> Button(onClick = onAsk) { Text(stringResource(R.string.perm_grant)) }
            AskRoute.SETTINGS -> Button(onClick = onAsk) { Text(stringResource(R.string.perm_settings)) }
        }
    }
}
