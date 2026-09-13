package com.soundmesh.probe.sync

import android.media.AudioManager
import com.soundmesh.session.CAPTURING_HOST_STREAM
import java.io.File
import kotlin.math.roundToInt

/** What a stream is set to at this moment, and how far it goes on this handset. */
data class VolumeReading(val index: Int, val max: Int, val stream: String) {
    val percent: Int get() = percentOf(index, max)
}

/**
 * Where a percentage lands on a scale of [max] steps.
 *
 * A percentage is what travels between handsets, because they do not agree on how many steps a
 * stream has - fifteen on one, sixteen on the next - so an index set across a room is a different
 * loudness on every handset in it. The rounding is stated rather than left to integer division:
 * 60% of fifteen steps is nine and not eight, and the two are a step apart everywhere.
 */
internal fun indexFor(percent: Int, max: Int): Int =
    (percent.coerceIn(0, 100) * max / 100.0).roundToInt().coerceIn(0, max)

/** The other direction, for saying on a screen what a handset actually landed on. */
internal fun percentOf(index: Int, max: Int): Int =
    if (max <= 0) 0 else (index * 100.0 / max).roundToInt().coerceIn(0, 100)

/**
 * What each stream was set to before anything here first touched it.
 *
 * On disk rather than in memory, and written once per stream rather than on every change. This is
 * the only thing that can put somebody's phone back the way they left it, and the moment it is
 * most needed is after the app has been killed - which is exactly when a field would be gone.
 * Written once because the value worth keeping is the one from before this app, not the one from
 * before the last drag of a slider.
 */
class StoredVolumeBefore(private val directory: File) {
    private val file: File get() = File(directory, FILE)

    /** Keeps [index] for [stream] unless something is already kept for it. */
    fun remember(stream: String, index: Int) {
        if (taken().containsKey(stream)) return
        runCatching { file.appendText("$stream $index\n") }
    }

    fun taken(): Map<String, Int> = runCatching {
        file.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            val fields = line.trim().split(" ")
            val index = fields.getOrNull(1)?.toIntOrNull()
            if (fields.size == 2 && index != null) fields[0] to index else null
        }.toMap()
    }.getOrDefault(emptyMap())

    fun forget() {
        runCatching { file.delete() }
    }

    companion object {
        const val FILE = "volume-before"
    }
}

/**
 * Sets this handset's volume and then reads back what actually happened.
 *
 * Reading back is not belt and braces. `setStreamVolume` has already been seen on this project's
 * own handsets to take a value, throw nothing, and move nothing: sixteen calls during one drag of
 * a slider, index 6 through 12, and the stream stayed on 4 throughout. That was the accessibility
 * stream rather than these two, so it may well work here - but a slider driven by what was asked
 * for rather than by what landed looks live either way, and only one of those is true. Under
 * do-not-disturb the same call throws instead. Both are answered the same way: say what the
 * stream is now.
 *
 * Which stream depends on what this handset is being, not on what it is. A host capturing another
 * app's audio is heard on the alarm stream - see [CAPTURING_HOST_STREAM] - and everybody else is
 * on media. One number across a room therefore means "whatever you are actually playing on".
 */
class HandsetVolume(private val audio: AudioManager, private val directory: File) {
    private val before = StoredVolumeBefore(directory)

    fun streamFor(capturing: Boolean): Int =
        if (capturing) CAPTURING_HOST_STREAM else AudioManager.STREAM_MUSIC

    fun read(capturing: Boolean): VolumeReading = reading(streamFor(capturing))

    /**
     * Sets the stream this handset plays on to [percent], and answers what it is now.
     *
     * A capturing host also has its media stream silenced, because the app being captured is
     * heard on it live while the room plays the same thing a second and a half later - so a
     * capturing host with media up hears everything twice. What was there first is kept, and
     * [restore] is the way back.
     */
    fun set(percent: Int, capturing: Boolean): VolumeReading {
        val stream = streamFor(capturing)
        write(stream, indexFor(percent, audio.getStreamMaxVolume(stream)))
        if (capturing) write(AudioManager.STREAM_MUSIC, 0)
        return reading(stream)
    }

    /** Puts back whatever was there before this app first changed it, and stops remembering. */
    fun restore() {
        for ((name, index) in before.taken()) {
            streamOf(name)?.let { runCatching { audio.setStreamVolume(it, index, 0) } }
        }
        before.forget()
    }

    /** Whether anything here has been changed and not yet put back. */
    fun changed(): Boolean = before.taken().isNotEmpty()

    private fun write(stream: Int, index: Int) {
        before.remember(nameOf(stream), audio.getStreamVolume(stream))
        // Swallowed rather than reported: under do-not-disturb this throws, and the answer to
        // that is the same as the answer to it silently doing nothing - read the stream back.
        runCatching { audio.setStreamVolume(stream, index, 0) }
    }

    private fun reading(stream: Int) = VolumeReading(
        audio.getStreamVolume(stream),
        audio.getStreamMaxVolume(stream),
        nameOf(stream)
    )

    companion object {
        const val MEDIA = "MEDIA"
        const val ALARM = "ALARM"

        fun nameOf(stream: Int): String = if (stream == CAPTURING_HOST_STREAM) ALARM else MEDIA

        private fun streamOf(name: String): Int? = when (name) {
            ALARM -> CAPTURING_HOST_STREAM
            MEDIA -> AudioManager.STREAM_MUSIC
            else -> null
        }
    }
}
