package com.soundmesh.probe.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.soundmesh.core.PairingCodeCodec
import com.soundmesh.product.Preferences
import com.soundmesh.product.ScanSay
import com.soundmesh.product.ScanScreen
import com.soundmesh.product.SoundMeshTheme
import com.soundmesh.product.themeChoiceOf
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
    /**
     * Held by this activity rather than made by the composition, because the camera is bound to
     * it: a view the screen could replace on a recomposition would leave the camera drawing into
     * one nobody can see. See [ScanScreen].
     */
    private val previewView by lazy { PreviewView(this) }

    /** What the screen says. Written through [say], which puts it on the main thread. */
    private var saying by mutableStateOf(ScanSay.LOOKING)

    /** The decode runs off the main thread, so what it finds is published back through the looper. */
    private val analysis = Executors.newSingleThreadExecutor()

    /** Frames keep arriving after a code is read; the first one to win takes the scan. */
    private val scanned = AtomicBoolean(false)

    /** Held only to put the camera down again once there is nothing left to look for. */

    private var camera: ProcessCameraProvider? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Closes this screen a moment after a code is read.
     *
     * One object rather than a fresh lambda per scan, so that [beginScanning] can take it back off
     * the queue: a scanner re-armed inside the pause would otherwise be shut by the finish the
     * previous scan had already posted, which looks like a camera that closes itself at random.
     */
    private val finishAfterScan = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SoundMeshTheme(themeChoiceOf(Preferences(filesDir).read("theme"))) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ScanScreen(saying, preview = { previewView }, onBack = { finish() })
                }
            }
        }
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
        handler.removeCallbacks(finishAfterScan)
        scanned.set(false)
        say(ScanSay.LOOKING)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        // Said out loud rather than retried. A scanner silently waiting on a permission nobody
        // granted looks exactly like one pointed at a screen with no code on it.
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else say(ScanSay.NO_PERMISSION)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(finishAfterScan)
        analysis.shutdown()
    }

    private fun openCamera() {
        val pending = ProcessCameraProvider.getInstance(this)
        pending.addListener({
            val provider = runCatching { pending.get() }.getOrNull()
            if (provider == null) {
                say(ScanSay.NO_CAMERA)
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
                .onFailure { say(ScanSay.NO_CAMERA) }
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
                say(ScanSay.NOT_OURS)
                return
            }
            if (!scanned.compareAndSet(false, true)) return
            PairedHost(filesDir).write(code)
            runOnUiThread { camera?.unbindAll() }
            say(ScanSay.SCANNED)
            // Scanning is the one act in this system that needs a hand on the phone, and it is
            // done once. Leaving somebody on a dead preview to find the back button is the part of
            // it they should not have to think about.
            //
            // Not at once, though: the line above says the code was read, and a screen that
            // vanishes before it can be read leaves exactly the doubt it exists to remove.
            //
            // Nothing driven over ADB is watching this screen - scan-pair polls the pairing file,
            // which is written above - so finishing costs the tools nothing and saves them a
            // camera left running on a phone about to be measured in a quiet room.
            handler.postDelayed(finishAfterScan, SCANNED_LINGER_MILLIS)
        } finally {
            image.close()
        }
    }

    /** On the main thread, because the decode that has something to say runs off it. */
    private fun say(what: ScanSay) = runOnUiThread { saying = what }

    private companion object {
        const val CAMERA_REQUEST = 1

        /** Long enough to read one line off a screen somebody is already holding up. */
        const val SCANNED_LINGER_MILLIS = 1_500L
    }
}
