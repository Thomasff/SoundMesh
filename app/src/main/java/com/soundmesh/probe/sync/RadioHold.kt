package com.soundmesh.probe.sync

import android.content.Context
import android.net.wifi.WifiManager

/**
 * A WifiLock, narrowed to what a run needs, so the release discipline can be tested off a handset.
 */
internal interface RadioHold {
    fun acquire()
    fun release()
}

/**
 * Runs [body] with the WiFi radio held out of power save, and reports through [held] whether it
 * actually was.
 *
 * The report is not decoration. acquire needs WAKE_LOCK and is best effort here, on the same terms
 * as the multicast lock in [PeerDiscovery] - a run should not die because a vendor build refused a
 * lock. But a hold that quietly did nothing would make a run read as evidence that power save does
 * not matter, when the arm under test never ran at all. Which arm ran belongs beside the numbers.
 *
 * Given back whatever happens: a leaked lock keeps the radio out of power save for the life of the
 * process, and nothing in a run would show it.
 */
internal fun <T> holdingRadio(hold: RadioHold?, held: (Boolean) -> Unit, body: () -> T): T {
    val taken = hold != null && runCatching { hold.acquire() }.isSuccess
    held(taken)
    try {
        return body()
    } finally {
        if (taken) runCatching { hold?.release() }
    }
}

/**
 * The handset's own WiFi radio, or null if it will not hand one over.
 *
 * WIFI_MODE_FULL_LOW_LATENCY exists from API 29, which is this app's floor, so there is no older
 * mode to fall back to. It was taken for the shape two router runs on 2026-09-08 measured: round
 * trips whose median was 56 to 70 ms on a link where the hotspot managed 5.5, with a tail past
 * 200 ms - frames waiting at an access point for a station that is asleep.
 *
 * It does not buy that. On 2026-09-13 every refused round reported this hold as taken and still
 * measured 55 ms, while the same handset, poked into exchanging with its router, measured 11. The
 * lock is kept because it costs nothing and asks the ROM for the right thing, but [keepingAwake]
 * is what does it. That also leaves the 09-08 reading in doubt: it was taken with the same idle
 * handset, so the router it blamed may never have been the slow part.
 */
internal fun radioHoldOf(context: Context): RadioHold? = runCatching {
    val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, RADIO_LOCK_TAG)
    lock.setReferenceCounted(false)
    object : RadioHold {
        override fun acquire() = lock.acquire()
        override fun release() = lock.release()
    }
}.getOrNull()

private const val RADIO_LOCK_TAG = "SoundMesh:calibration"
