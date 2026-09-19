package com.soundmesh.probe.sync

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.soundmesh.product.PairCodeScreen
import com.soundmesh.product.Preferences
import com.soundmesh.product.SoundMeshTheme
import com.soundmesh.product.inChosenLanguage
import com.soundmesh.product.themeChoiceOf

/**
 * The host's half of pairing, standing still.
 *
 * A run shows the same code, but only while it plays, which is backwards: pairing is what happens
 * before anyone presses play. This screen exists so the code can be held up to a camera with
 * nothing else going on - no tone, no recording, no deadline.
 *
 * It reads the identity rather than deciding one. [HostIdentity] names this handset once and every
 * run afterwards answers to that name, so a code shown here and a code shown by a run are the same
 * code, and a peer that scanned either files its calibration under the same host.
 *
 * What it draws is the status screen's own full-screen code and not a second drawing of one: this
 * is reached by name from the tools, and a tools-only spelling would be the one nobody notices
 * going stale.
 */
class ShowCodeActivity : ComponentActivity() {

    /** The language this app was told to be, put on before anything here reads a string. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.inChosenLanguage())
    }

    private var offer by mutableStateOf<HostPairingCode.Offer?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SoundMeshTheme(themeChoiceOf(Preferences(filesDir).read("theme"))) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PairCodeScreen(offer) { finish() }
                }
            }
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        readCode()
    }

    /**
     * Launched again while already open. Re-read rather than left alone: the address in the code is
     * this handset's, and a handset that changed networks since the last launch is showing a code
     * that scans cleanly and then connects to nothing.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readCode()
    }

    private fun readCode() {
        offer = HostPairingCode.offer(this, HostIdentity(filesDir).current(), SyncActivity.CHUNK_PORT)
    }
}
