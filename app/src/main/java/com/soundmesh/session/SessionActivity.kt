package com.soundmesh.session

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.product.inChosenLanguage

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

    /** The language this app was told to be, put on before anything here reads a string. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.inChosenLanguage())
    }

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
                .also { carryTuning(intent, it) }
        )
    }

    /**
     * Passes an experiment's tuning through to the service, and nothing when none was asked for.
     *
     * Absent stays absent rather than becoming an explicit default: the service reads the same
     * extra, and a control arm that arrives spelled differently from a session started before this
     * existed is not a control arm.
     */
    private fun carryTuning(from: Intent, to: Intent) {
        for (name in listOf(SessionService.EXTRA_DEADBAND_FRAMES, SessionService.EXTRA_TRIM_FRAMES)) {
            if (from.hasExtra(name)) to.putExtra(name, from.getIntExtra(name, 0))
        }
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
                .also { carryTuning(intent, it) }
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
            session != null -> "SESSION ${session.state()}\n\n${counters(session.report())}"
            SessionService.FAILURE != null -> "NO SESSION: ${SessionService.FAILURE}"
            else -> "SESSION IDLE"
        }
    }

    /**
     * The four counters that separate the ways playback can go wrong, one per line.
     *
     * On screen rather than only in a file because they are being read against what a person in
     * the room is hearing: a hitch and a counter that moved at that instant say together what
     * neither says alone. Trims and silence writes are the two waveform edits, and telling them
     * apart is the whole question - one deletes audio, the other inserts a gap.
     */
    private fun counters(report: String?): String {
        if (report == null) return ""
        return listOf("releaseTrims", "trimmedFrames", "silenceWrites", "droppedLate", "trackUnderruns", "played")
            .map { name -> "$name ${field(report, name)}" }
            .joinToString("\n")
    }

    /** Read out of the renderer's own JSON rather than re-derived, so the two cannot disagree. */
    private fun field(report: String, name: String): String =
        Regex("\"$name\":(-?\\d+)").find(report)?.groupValues?.get(1) ?: "-"

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
