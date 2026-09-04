package com.soundmesh.probe.sync

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

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
 */
class ShowCodeActivity : Activity() {
    private lateinit var codeView: ImageView
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        codeView = ImageView(this)
        statusView = TextView(this)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(codeView)
                addView(statusView)
            }
        )
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        drawCode()
    }

    /**
     * Launched again while already open. Redrawn rather than left alone: the address in the code is
     * this handset's, and a handset that changed networks since the last launch is showing a code
     * that scans cleanly and then connects to nothing.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        drawCode()
    }

    private fun drawCode() {
        val payload = HostPairingCode.of(HostIdentity(filesDir).current(), SyncActivity.CHUNK_PORT)
        if (payload == null) {
            // Said rather than left blank. A handset with two candidate addresses and one with no
            // network at all both show nothing, and only one of them is worth walking over to fix.
            codeView.setImageDrawable(null)
            statusView.text = "NO CODE: no single address a peer could reach"
            return
        }
        codeView.setImageBitmap(PairingCodeImage.bitmap(payload, PairingCodeImage.DEFAULT_PIXELS))
        // Under the code, because a code is unreadable to a person and this is the one screen
        // someone stands in front of. It is also what a screenshot can be checked against.
        statusView.text = payload
    }
}
