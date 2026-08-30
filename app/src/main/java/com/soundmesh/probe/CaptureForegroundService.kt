package com.soundmesh.probe

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
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the one user-approved MediaProjection and serializes private probe captures. */
class CaptureForegroundService : Service() {
    private val runStore by lazy { RunStore(filesDir) }
    private var mediaProjection: MediaProjection? = null
    private var activeSessionId: String? = null
    private var activeCaseId: String? = null
    private var stopRequested = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        return try {
            when (intent.action) {
                ACTION_START_SESSION -> startSession(intent)
                ACTION_SUBMIT_CASE -> submitCase(intent)
                ACTION_FINISH_SESSION -> finishSession(intent)
                else -> START_NOT_STICKY
            }
            START_NOT_STICKY
        } catch (error: IllegalArgumentException) {
            publish(STATUS_FAILED, error.message ?: "INVALID_REQUEST")
            START_NOT_STICKY
        } catch (error: IllegalStateException) {
            publish(STATUS_FAILED, error.message ?: "INVALID_STATE")
            START_NOT_STICKY
        }
    }

    private fun startSession(intent: Intent) {
        check(activeSessionId == null && mediaProjection == null) { "A projection session is already active" }
        val sessionId = intent.requiredSessionId()
        val resultData = intent.getParcelableExtra<Intent>(ProbeCase.EXTRA_RESULT_DATA)
            ?: throw IllegalArgumentException("missing media projection result data")
        val resultCode = intent.getIntExtra(ProbeCase.EXTRA_RESULT_CODE, Int.MIN_VALUE)
        check(resultCode != Int.MIN_VALUE) { "missing media projection result code" }

        // Android 15 requires this foreground promotion before getMediaProjection().
        startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("MediaProjection was not granted")
        activeSessionId = sessionId
        ACTIVE_SESSION_ID = sessionId
        publish(STATUS_SESSION_READY)
        submitCase(intent)
    }

    private fun submitCase(intent: Intent) {
        val sessionId = intent.requiredSessionId()
        check(sessionId == activeSessionId && mediaProjection != null) { "No matching active projection session" }
        check(activeCaseId == null) { "A capture case is already running" }
        val probeCase = ProbeCase.fromIntent(intent)
        activeCaseId = probeCase.caseId
        stopRequested = AtomicBoolean(false)
        runStore.writeStatus(probeCase.caseId, statusJson(STATUS_CAPTURING))
        publish(STATUS_CAPTURING)

        Thread({ runCase(probeCase, mediaProjection!!) }, "SoundMeshCapture-${probeCase.caseId}").start()
    }

    private fun runCase(probeCase: ProbeCase, projection: MediaProjection) {
        val reader = AndroidPlaybackReader(this, projection, probeCase.expectedPackage) {
            stopRequested.set(true)
        }
        val sink = CaptureFileSink(runStore.captureWavFile(probeCase.caseId), reader)
        var summary: CaptureRunSummary? = null
        var failure: Throwable? = null
        try {
            summary = CaptureRunner.run(
                CaptureRunConfig(
                    reader = reader,
                    sink = sink,
                    durationNanos = probeCase.durationSeconds * NANOS_PER_SECOND,
                    clock = MonotonicClock { System.nanoTime() },
                    stopRequested = { stopRequested.get() }
                )
            )
            val result = resultFor(probeCase, reader, sink, summary, null)
            runStore.writeCaptureJson(probeCase.caseId, result.toJson())
            val status = if (summary.state == CaptureState.COMPLETE) STATUS_COMPLETE else STATUS_FAILED
            runStore.writeStatus(probeCase.caseId, statusJson(status, summary.failureCode))
            publish(status, summary.failureCode)
        } catch (error: Throwable) {
            failure = error
            Log.e(LOG_TAG, "capture worker failed for ${probeCase.caseId}", error)
            val failureCode = error.javaClass.simpleName.ifEmpty { "CAPTURE_EXCEPTION" }
            runCatching {
                runStore.writeCaptureJson(
                    probeCase.caseId,
                    resultFor(probeCase, reader, sink, summary, failureCode).toJson()
                )
            }.onFailure { Log.e(LOG_TAG, "could not write failure result", it) }
            runCatching { runStore.writeStatus(probeCase.caseId, statusJson(STATUS_FAILED, failureCode)) }
                .onFailure { Log.e(LOG_TAG, "could not write failure status", it) }
            publish(STATUS_FAILED, failureCode)
        } finally {
            activeCaseId = null
            if (failure != null) {
                // CaptureRunner owns normal reader/sink cleanup; this covers failures before it starts.
                runCatching { reader.stop() }
                runCatching { reader.close() }
                runCatching { sink.close() }
            }
        }
    }

    private fun resultFor(
        probeCase: ProbeCase,
        reader: PcmReader,
        sink: CaptureFileSink,
        summary: CaptureRunSummary?,
        thrownFailureCode: String?
    ): CaptureResult {
        val completed = summary?.state == CaptureState.COMPLETE && thrownFailureCode == null
        return CaptureResult(
            schemaVersion = 1,
            caseId = probeCase.caseId,
            state = if (completed) STATUS_COMPLETE else STATUS_FAILED,
            requestedFormat = PcmFormat(48_000, 2),
            actualFormat = if (completed) PcmFormat(reader.sampleRate, reader.channelCount) else null,
            expectedBytes = probeCase.durationSeconds * 48_000L * reader.channelCount * PCM16_BYTES,
            capturedBytes = summary?.capturedBytes ?: 0,
            metrics = sink.metrics(),
            failureCode = thrownFailureCode ?: summary?.failureCode,
            artifactFiles = listOf("status.json", "capture.wav", "capture.json")
        )
    }

    private fun finishSession(intent: Intent) {
        val sessionId = intent.requiredSessionId()
        check(sessionId == activeSessionId) { "No matching active projection session" }
        check(activeCaseId == null) { "Cannot finish while a capture case is running" }
        mediaProjection?.stop()
        mediaProjection = null
        activeSessionId = null
        ACTIVE_SESSION_ID = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish(status: String, detail: String? = null) {
        Log.i(LOG_TAG, "status=$status${detail?.let { ", detail=$it" } ?: ""}")
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_STATUS, status).putExtra(EXTRA_STATUS_DETAIL, detail))
    }

    private fun statusJson(status: String, failureCode: String? = null): String =
        "{\"state\":\"$status\",\"failureCode\":${failureCode?.let { "\"$it\"" } ?: "null"}}"

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

    override fun onDestroy() {
        if (activeCaseId == null) mediaProjection?.stop()
        mediaProjection = null
        activeSessionId = null
        ACTIVE_SESSION_ID = null
        super.onDestroy()
    }

    private fun Intent.requiredSessionId(): String = getStringExtra(ProbeCase.EXTRA_SESSION_ID)
        ?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing session_id")

    private class CaptureFileSink(private val file: File, private val reader: PcmReader) : PcmSink {
        private var writer: WavFileWriter? = null
        private var accumulator: PcmMetricsAccumulator? = null
        override fun write(bytes: ByteArray, length: Int, timestampNanos: Long) {
            if (writer == null) {
                writer = WavFileWriter(file, reader.sampleRate, reader.channelCount)
                accumulator = PcmMetricsAccumulator(reader.sampleRate, reader.channelCount)
            }
            writer!!.writePcm(bytes, length)
            accumulator!!.accept(bytes, length, timestampNanos)
        }
        override fun close() {
            if (writer == null) writer = WavFileWriter(file, reader.sampleRate, reader.channelCount)
            writer!!.close()
        }
        fun metrics(): PcmMetrics? = accumulator?.finish()
    }

    companion object {
        const val ACTION_START_SESSION = "com.soundmesh.probe.START_SESSION"
        const val ACTION_SUBMIT_CASE = "com.soundmesh.probe.SUBMIT_CASE"
        const val ACTION_FINISH_SESSION = "com.soundmesh.probe.FINISH_SESSION"
        const val ACTION_STATUS = "com.soundmesh.probe.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_STATUS_DETAIL = "status_detail"
        const val STATUS_AWAITING_PERMISSION = "AWAITING_PERMISSION"
        const val STATUS_SESSION_READY = "SESSION_READY"
        const val STATUS_CAPTURING = "CAPTURING"
        const val STATUS_COMPLETE = "COMPLETE"
        const val STATUS_FAILED = "FAILED"
        private const val LOG_TAG = "SoundMeshProbe"
        private const val CHANNEL_ID = "soundmesh_capture"
        private const val NOTIFICATION_ID = 1001
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val PCM16_BYTES = 2L
        @Volatile var ACTIVE_SESSION_ID: String? = null
            private set
    }
}
