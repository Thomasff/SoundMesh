package com.soundmesh.product

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soundmesh.probe.BuildConfig
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.PairingCodeImage

/**
 * Stage one: nobody has picked a role yet.
 *
 * This is the only screen a person who has never opened the app before is guaranteed to read, so
 * it says what the other screens assume: this needs a second phone, both need the app, both need
 * the same WiFi, and then this one needs to pick a side. [routeOf] sends every other state past
 * this screen without a second look.
 */
@Composable
fun WelcomeScreen(state: HomeState, actions: HomeActions) {
    Text(stringResource(R.string.welcome_what), style = MaterialTheme.typography.bodyLarge)
    Section(R.string.welcome_need) {
        Text(stringResource(R.string.welcome_need_phones), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.welcome_need_app), style = MaterialTheme.typography.bodyMedium)
        // Only offered once there is somewhere for the code to point. An unset release URL would
        // make a code that scans to nothing, which is worse than no code at all - see configured().
        val releaseUrl = configured(BuildConfig.RELEASE_URL)
        if (releaseUrl != null) {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(R.string.welcome_need_app_share))
            }
            if (expanded) {
                // Remembered against the URL rather than the recomposition, the same reason the
                // pairing code on the host screen is: encoding one is the most expensive thing on
                // this screen, for a picture that only ever changes if the release URL does.
                val code = remember(releaseUrl) {
                    PairingCodeImage.bitmap(releaseUrl, PairingCodeImage.DEFAULT_PIXELS).asImageBitmap()
                }
                Image(code, contentDescription = null, modifier = Modifier.size(240.dp))
            }
        }
        Text(stringResource(R.string.welcome_need_wifi), style = MaterialTheme.typography.bodyMedium)
        val wifiName = state.wifiName
        if (wifiName != null) {
            Text(
                stringResource(R.string.welcome_wifi_name, wifiName),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                stringResource(R.string.welcome_wifi_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
    RolePicker(actions)
}
