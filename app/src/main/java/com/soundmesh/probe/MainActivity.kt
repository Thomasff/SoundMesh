package com.soundmesh.probe

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView

object ProbeIdentity { const val APPLICATION_ID = "com.soundmesh.probe" }

/** Launcher and visible consent boundary; it never automates the system consent UI. */
class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private var pendingIntent: Intent? = null
    private lateinit var runStore: RunStore
    private var pendingCaseId: String? = null
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            statusView.text = intent.getStringExtra(CaptureForegroundService.EXTRA_STATUS)
                ?: CaptureForegroundService.STATUS_FAILED
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this)
        setContentView(statusView)
        runStore = RunStore(filesDir)
        val statusFilter = IntentFilter(CaptureForegroundService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, statusFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, statusFilter)
        }
        handleLauncherIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLauncherIntent(intent)
    }

    private fun handleLauncherIntent(intent: Intent) {
        if (!intent.hasExtra(ProbeCase.EXTRA_SESSION_ID)) {
            statusView.text = getString(R.string.probe_ready)
            return
        }
        if (intent.getBooleanExtra(ProbeCase.EXTRA_FINISH_SESSION, false)) {
            startCaptureService(Intent(this, CaptureForegroundService::class.java)
                .setAction(CaptureForegroundService.ACTION_FINISH_SESSION)
                .putExtra(ProbeCase.EXTRA_SESSION_ID, intent.getStringExtra(ProbeCase.EXTRA_SESSION_ID)))
            return
        }
        try {
            ProbeCase.fromIntent(intent)
            require(!intent.getStringExtra(ProbeCase.EXTRA_SESSION_ID).isNullOrBlank()) { "missing session_id" }
        } catch (error: IllegalArgumentException) {
            // Without this the PC sees no status at all and can only time out.
            runCatching { ServiceRejection.record(runStore, intent.getStringExtra(ProbeCase.EXTRA_CASE_ID), error) }
            statusView.text = CaptureForegroundService.STATUS_FAILED
            return
        }
        if (CaptureForegroundService.ACTIVE_SESSION_ID != null) {
            startCaptureService(Intent(intent).setClass(this, CaptureForegroundService::class.java)
                .setAction(CaptureForegroundService.ACTION_SUBMIT_CASE))
            return
        }
        pendingIntent = Intent(intent)
        pendingCaseId = intent.getStringExtra(ProbeCase.EXTRA_CASE_ID)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runStore.writeStatus(pendingCaseId!!, RunStatus.json(CaptureForegroundService.STATUS_AWAITING_PERMISSION))
            statusView.text = CaptureForegroundService.STATUS_AWAITING_PERMISSION
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        } else requestProjectionConsent()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestProjectionConsent()
        else if (requestCode == REQUEST_RECORD_AUDIO) failPendingRun()
    }

    private fun requestProjectionConsent() {
        runStore.writeStatus(pendingCaseId!!, RunStatus.json(CaptureForegroundService.STATUS_AWAITING_PERMISSION))
        statusView.text = CaptureForegroundService.STATUS_AWAITING_PERMISSION
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Deprecated("Activity result API is sufficient for this one-time platform consent boundary.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return
        val original = pendingIntent
        pendingIntent = null
        if (resultCode != RESULT_OK || data == null || original == null) {
            failPendingRun()
            return
        }
        startCaptureService(original.setClass(this, CaptureForegroundService::class.java)
            .setAction(CaptureForegroundService.ACTION_START_SESSION)
            .putExtra(ProbeCase.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ProbeCase.EXTRA_RESULT_DATA, data))
    }

    private fun failPendingRun() {
        pendingCaseId?.let { runStore.writeStatus(it, RunStatus.json(CaptureForegroundService.STATUS_FAILED)) }
        statusView.text = CaptureForegroundService.STATUS_FAILED
    }

    private fun startCaptureService(intent: Intent) = startForegroundService(intent)

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 41
        const val REQUEST_MEDIA_PROJECTION = 42
    }
}
