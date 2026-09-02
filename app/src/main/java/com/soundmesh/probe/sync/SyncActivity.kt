package com.soundmesh.probe.sync

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.probe.ProbeCase
import com.soundmesh.probe.RunStore

/**
 * ADB driven harness for the M1 and M2 gates. Not product code: the product will discover
 * peers and manage sessions itself, while this takes both from intent extras.
 */
class SyncActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var runStore: RunStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this)
        setContentView(statusView)
        runStore = RunStore(filesDir)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handle()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle()
    }

    private fun handle() {
        val caseId = intent.getStringExtra(ProbeCase.EXTRA_CASE_ID)
        val role = intent.getStringExtra("role")
        val seconds = intent.getIntExtra("seconds", -1)
        if (caseId == null || !ProbeCase.isSafeCaseId(caseId) || role !in setOf("HOST", "SINK") || seconds !in 10..900) {
            statusView.text = "REJECTED"
            return
        }
        statusView.text = "$role RUNNING"
        Thread {
            val failure = runCatching { if (role == "HOST") runHost(seconds) else runSink(caseId, seconds) }
                .exceptionOrNull()?.javaClass?.simpleName
            if (role == "HOST" && failure == null) {
                runStore.writeSyncJson(caseId, "{\"schemaVersion\":1,\"role\":\"HOST\",\"failureCode\":null}")
            } else if (failure != null) {
                runStore.writeSyncJson(caseId, "{\"schemaVersion\":1,\"role\":\"$role\",\"failureCode\":\"$failure\"}")
            }
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                statusView.text = "$role DONE"
            }
        }.start()
    }

    private fun runHost(seconds: Int) {
        val server = ClockSyncServer(CLOCK_PORT)
        server.start()
        Thread.sleep(seconds * 1000L)
        server.stop()
    }

    private fun runSink(caseId: String, seconds: Int) {
        val address = intent.getStringExtra("host_address")
            ?: throw IllegalArgumentException("SINK needs host_address")
        val client = ClockSyncClient(address, CLOCK_PORT, ClockOffsetEstimator())
        val history = client.runFor(seconds)
        runStore.writeSyncJson(caseId, buildString {
            append("{\"schemaVersion\":1,\"role\":\"SINK\",\"failureCode\":null,\"estimates\":[")
            history.forEachIndexed { index, estimate ->
                if (index > 0) append(',')
                append("{\"offsetNanos\":").append(estimate.offsetNanos)
                append(",\"uncertaintyNanos\":").append(estimate.uncertaintyNanos)
                append(",\"driftPpm\":").append(estimate.driftPpm)
                append(",\"sampleCount\":").append(estimate.sampleCount).append('}')
            }
            append("]}")
        })
    }

    companion object {
        const val CLOCK_PORT = 45123
    }
}
