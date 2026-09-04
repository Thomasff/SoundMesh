package com.soundmesh.probe.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.soundmesh.core.PairingCodeCodec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reading the code a host shows, so a run can start already knowing which handset it means.
 *
 * Deliberately its own screen and its own act. Scanning is the one part of this system that needs
 * a hand on the phone - someone has to hold it up to another screen - and the runs measure timing
 * to a fraction of a millisecond, which moving a handset destroys. So the scan happens once,
 * lands in [PairedHost], and every run after it starts with nobody touching anything.
 *
 * What it does NOT do is join a network. Both handsets are already on the same one before this
 * screen is any use at all; the code names a host, it does not carry a way onto the WiFi.
 */
class ScanActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private lateinit var previewView: PreviewView

    /** The decode runs off the main thread, so what it finds is published back through the looper. */
    private val analysis = Executors.newSingleThreadExecutor()

    /** Frames keep arriving after a code is read; the first one to win takes the scan. */
    private val scanned = AtomicBoolean(false)

    /** Held only to put the camera down again once there is nothing left to look for. */
    
    private var camera: ProcessCameraProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        statusView = TextView(this)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(statusView)
            }
        )
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        beginScanning()
    }

    /**
     * Launched again while already open.
     *
     * Without this the second scan of a session never happens. The activity is singleTask, so a
     * fresh launch reuses the instance that already found a code - and that instance put its camera
     * down on purpose. From the outside it looks like a scanner that will not see anything.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        beginScanning()
    }

    private fun beginScanning() {
        scanned.set(false)
        say("SCANNING")
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        // Said out loud rather than retried. The harness reads this line off the screen, and a
        // scanner silently waiting on a permission nobody granted looks exactly like one pointed
        // at a screen with no code on it.
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera() else say("NO CAMERA PERMISSION")
    }

    override fun onDestroy() {
        super.onDestroy()
        analysis.shutdown()
    }

    private fun openCamera() {
        val pending = ProcessCameraProvider.getInstance(this)
        pending.addListener({
            val provider = runCatching { pending.get() }.getOrNull()
            if (provider == null) {
                say("NO CAMERA")
                return@addListener
            }
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            // KEEP_ONLY_LATEST because a decode that falls behind should skip frames rather than
            // work through a queue: by the time an old frame decodes the phone has moved.
            val reader = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            reader.setAnalyzer(analysis, ::examine)
            camera = provider
            provider.unbindAll()
            runCatching { provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, reader) }
                .onFailure { say("NO CAMERA") }
        }, mainExecutor)
    }

    private fun examine(image: ImageProxy) {
        try {
            if (scanned.get()) return
            val plane = image.planes[0]
            val bytes = ByteArray(plane.buffer.remaining())
            plane.buffer.get(bytes)
            val text = QrReader.read(
                QrReader.luminance(bytes, plane.rowStride, plane.pixelStride, image.width, image.height),
                image.width,
                image.height
            ) ?: return
            // Any QR code in the room decodes; almost none of them are ours. Said rather than
            // ignored, because "the camera can see it and it is the wrong code" and "the camera
            // cannot see anything" are different problems with the same silence.
            val code = runCatching { PairingCodeCodec.decode(text) }.getOrNull()
            if (code == null) {
                say("NOT A PAIRING CODE")
                return
            }
            if (!scanned.compareAndSet(false, true)) return
            PairedHost(filesDir).write(code)
            runOnUiThread { camera?.unbindAll() }
            say("SCANNED ${code.hostId} at ${code.address}:${code.chunkPort}")
        } finally {
            image.close()
        }
    }

    private fun say(text: String) = runOnUiThread { statusView.text = text }

    private companion object {
        const val CAMERA_REQUEST = 1
    }
}
