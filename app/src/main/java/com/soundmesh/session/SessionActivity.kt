package com.soundmesh.session

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.soundmesh.probe.sync.PairedHost

/**
 * Starts, stops and shows one product session.
 *
 * [SessionService] is not exported, so something inside the app has to hand it its intent. That is
 * this screen's first job. Its second is to say what the session is doing, which is the whole of
 * the interface until there is a real one - a session that is silent because another app holds the
 * focus and a session that is silent because the host went away look identical from across a room.
 *
 * A sink takes its host from what the camera scanned, not from an address typed here. Pairing is
 * already solved and already persisted; asking again would be a second answer to one question.
 */
class SessionActivity : Activity() {
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            show()
            handler.postDelayed(this, REFRESH_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply { gravity = Gravity.CENTER }
        setContentView(statusView)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        act(intent)
    }

    /** Launched again while already open. A singleTask activity gets no second onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        act(intent)
    }

    private fun act(intent: Intent) {
        when {
            intent.getBooleanExtra("stop", false) -> startService(stopRequest())
            intent.getStringExtra("role") == "HOST" -> startHost(intent)
            intent.getStringExtra("role") == "SINK" -> startSink()
            else -> Unit
        }
        show()
    }

    private fun startHost(intent: Intent) {
        val file = intent.getStringExtra("source_file")
        if (file == null) {
            statusView.text = "NO SOURCE: a host needs a file to play"
            return
        }
        startForegroundService(
            Intent(this, SessionService::class.java)
                .setAction(SessionService.ACTION_START_HOST)
                .putExtra(SessionService.EXTRA_SOURCE_FILE, file)
        )
    }

    private fun startSink() {
        val code = PairedHost(filesDir).read()
        if (code == null) {
            statusView.text = "NOT PAIRED: scan the host's code first"
            return
        }
        startForegroundService(
            Intent(this, SessionService::class.java)
                .setAction(SessionService.ACTION_START_SINK)
                .putExtra(SessionService.EXTRA_HOST_ADDRESS, code.address)
                .putExtra(SessionService.EXTRA_CHUNK_PORT, code.chunkPort)
                .putExtra(SessionService.EXTRA_PEER_ID, code.hostId)
        )
    }

    private fun stopRequest(): Intent =
        Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP)

    /**
     * The state, or why there is none.
     *
     * A failed start leaves no session at all, and the difference between that and a session that
     * has not reached PLAYING yet is the difference between waiting and walking over to the phone.
     */
    private fun show() {
        val session = SessionService.ACTIVE
        statusView.text = when {
            session != null -> "SESSION ${session.state()}"
            SessionService.FAILURE != null -> "NO SESSION: ${SessionService.FAILURE}"
            else -> "SESSION IDLE"
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private companion object {
        /** Fast enough that a state change reads as immediate, slow enough to cost nothing. */
        const val REFRESH_MILLIS = 200L
    }
}
