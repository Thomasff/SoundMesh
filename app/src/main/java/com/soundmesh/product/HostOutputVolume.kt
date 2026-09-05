package com.soundmesh.product

import android.media.AudioManager
import com.soundmesh.session.CAPTURING_HOST_STREAM

/** The level of the output a capturing host is heard on, and how far it goes. */
data class OutputVolume(val level: Int, val max: Int)

/**
 * Reads the volume of the stream a capturing host plays on. It does not set it, and no version of
 * this should.
 *
 * `setStreamVolume` was tried and is not a control. On the accessibility stream it neither threw
 * nor moved anything: sixteen calls went through during one drag of a slider, index 6 through 12,
 * and the stream stayed on 4 throughout. A slider driven by that looks live and is a lie.
 *
 * What moves a stream is the handset's own volume keys, and [android.app.Activity.setVolumeControlStream]
 * is how a screen points them at one. See [com.soundmesh.session.CAPTURING_HOST_USAGE] for why that
 * stream is the alarm one rather than the accessibility one.
 */
class HostOutputVolume(private val audio: AudioManager) {
    fun read(): OutputVolume = OutputVolume(
        audio.getStreamVolume(CAPTURING_HOST_STREAM),
        audio.getStreamMaxVolume(CAPTURING_HOST_STREAM)
    )
}
