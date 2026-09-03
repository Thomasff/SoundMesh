package com.soundmesh.probe.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.soundmesh.probe.R

/**
 * Holds the sync run's MediaProjection, and exists only because holding it is not allowed anywhere
 * else: the platform refuses `getMediaProjection` unless the caller is already a foreground service
 * of type mediaProjection. The first attempt obtained it straight from the activity on the belief
 * that the rule arrived in Android 14; the Android 10 handset threw a SecurityException and took the
 * whole run with it.
 *
 * CaptureForegroundService does the same promotion, but it owns the probe's capture cases end to
 * end - ProbeCase, the WAV sink, the status files. Routing a sync run through it would mean
 * reshaping validated capture code to carry a second kind of run, so this holds the projection and
 * nothing else.
 */
class SyncProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent)
            ACTION_RELEASE -> {
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquire(intent: Intent) {
        // Order matters and is the whole point of this class: promotion first, projection second.
        startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val projection = if (resultCode == Int.MIN_VALUE || resultData == null) null else {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            runCatching { manager.getMediaProjection(resultCode, resultData) }.getOrNull()
        }
        val waiting = pending
        pending = null
        waiting?.invoke(projection)
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.capture_notification))
            .build()
    }

    companion object {
        const val ACTION_ACQUIRE = "com.soundmesh.probe.sync.ACQUIRE_PROJECTION"
        const val ACTION_RELEASE = "com.soundmesh.probe.sync.RELEASE_PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "soundmesh_capture"
        private const val NOTIFICATION_ID = 1002

        /**
         * Set by the activity before it starts the service, called back on the main thread once the
         * projection exists or has failed. Same process, one run at a time, one waiter.
         */
        @Volatile
        var pending: ((MediaProjection?) -> Unit)? = null
    }
}
