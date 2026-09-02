package com.soundmesh.probe

import android.media.AudioAttributes

/**
 * Audio usages the probe may replay captured PCM with. The non-media usages exist only to
 * verify device capability while the media stream is muted; they are not a product decision.
 */
enum class PlaybackUsage(val androidUsage: Int) {
    MEDIA(AudioAttributes.USAGE_MEDIA),
    ALARM(AudioAttributes.USAGE_ALARM),
    ACCESSIBILITY(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY);

    companion object {
        fun fromName(name: String?): PlaybackUsage {
            if (name == null) return MEDIA
            return entries.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("unknown playback_usage: $name")
        }
    }
}
