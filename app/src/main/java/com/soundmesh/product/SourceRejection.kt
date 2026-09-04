package com.soundmesh.product

import androidx.annotation.StringRes
import com.soundmesh.probe.R

/**
 * Why a chosen song was refused, in words a person can act on.
 *
 * The codes come from [com.soundmesh.probe.sync.SourceUnusable] and were written for a report
 * file. On a screen they are worse than nothing: SOURCE_FILE_FORMAT_UNUSABLE reads as "wrong file
 * type", when what it means is a bit depth or a sample rate the converter has no route from -
 * and the two suggest different next moves. SOURCE_FILE_CHANNELS_UNUSABLE is its own code for the
 * same reason: a surround mix is refused for a reason that has nothing to do with the encoding.
 */
object SourceRejection {
    @StringRes
    fun of(code: String?): Int = when (code) {
        "SOURCE_FILE_MISSING" -> R.string.source_missing
        "SOURCE_FILE_NO_AUDIO" -> R.string.source_no_audio
        "SOURCE_FILE_TOO_SHORT" -> R.string.source_too_short
        "SOURCE_FILE_DECODE_STALLED" -> R.string.source_stalled
        "SOURCE_FILE_FORMAT_UNKNOWN" -> R.string.source_format_unknown
        "SOURCE_FILE_FORMAT_UNUSABLE" -> R.string.source_format_unusable
        "SOURCE_FILE_CHANNELS_UNUSABLE" -> R.string.source_channels_unusable
        else -> R.string.source_unreadable
    }
}
