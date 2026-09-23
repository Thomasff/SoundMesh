package com.soundmesh.probe.sync

import android.media.AudioManager
import com.soundmesh.session.CAPTURING_HOST_STREAM
import java.io.File

/** What a stream is set to at this moment, and how far it goes on this handset. */
data class VolumeReading(val index: Int, val max: Int, val stream: String) {
    val percent: Int get() = percentOf(index, max)
}

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

    /** Forgets one stream, for the one that has just been put back on its own. */
    fun forget(stream: String) {
        val left = taken() - stream
        runCatching {
            if (left.isEmpty()) file.delete()
            else file.writeText(left.entries.joinToString("") { "${it.key} ${it.value}" + "\n" })
        }
    }

    companion object {
        const val FILE = "volume-before"
    }
}

/**
 * The three things this needs of a handset's streams, behind a seam.
 *
 * Not indirection for its own sake: the rule this class holds - which stream is silenced and when
 * it is put back - is one a test can get wrong in a way that leaves somebody's phone at zero, and
 * AudioManager cannot be stood up in a unit test. A fake also spells out the failure worth
 * rehearsing, which is a set that is accepted and does nothing.
 */
interface StreamVolumes {
    fun level(stream: Int): Int
    fun max(stream: Int): Int
    fun set(stream: Int, index: Int)
}

class AndroidStreamVolumes(private val audio: AudioManager) : StreamVolumes {
    override fun level(stream: Int): Int = audio.getStreamVolume(stream)
    override fun max(stream: Int): Int = audio.getStreamMaxVolume(stream)

    /**
     * Swallowed rather than reported: under do-not-disturb this throws, and the answer to that is
     * the same as the answer to it silently doing nothing - read the stream back.
     */
    override fun set(stream: Int, index: Int) {
        runCatching { audio.setStreamVolume(stream, index, 0) }
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
class HandsetVolume(private val streams: StreamVolumes, directory: File) {
    constructor(audio: AudioManager, directory: File) : this(AndroidStreamVolumes(audio), directory)

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
    fun set(percent: Int, capturing: Boolean): VolumeReading = moveTo(capturing, percent)

    /**
     * Follows this handset from one stream to the other, which is what changing mode does.
     *
     * Both directions, and the second one is the one that is easy to forget: a host leaving the
     * capturing mode goes back to playing on the stream that was silenced for it, so a mode
     * change that only ever muted would leave somebody pressing play on a phone at zero.
     *
     * [percent] is null when nothing has said what the room should be at, and then this only
     * moves the silence - it does not decide a loudness nobody asked for.
     */
    fun moveTo(capturing: Boolean, percent: Int?): VolumeReading {
        val stream = streamFor(capturing)
        // Only when nothing here is going to write over media anyway. Putting it back first and
        // then setting it is two loudnesses a few milliseconds apart, and the first of them is the
        // level from before this app ever touched the handset - which is a room jumping to full
        // and back on every drag of the slider. Where it ends up is the same either way, which is
        // why it took somebody in the room to notice.
        if (!capturing && percent == null) putMediaBack()
        if (percent != null) write(stream, indexFor(percent, streams.max(stream)))
        if (capturing) write(AudioManager.STREAM_MUSIC, 0)
        return reading(stream)
    }

    /**
     * Sets both of the outputs this app ever plays on to [percent], and says what they are now.
     *
     * For the one measurement that uses both at once: the output-lead calibration plays a chirp
     * on the ordinary path and another on the path a capturing host is heard on, and reads the
     * difference between the two arrivals. [set] is no use there - it silences one of the two,
     * which is the right thing everywhere else it is called from and exactly wrong here.
     *
     * Both go through [write], so [restore] puts both back to whatever they were before this app
     * first touched them, the same as every other way in here.
     */
    fun setBoth(percent: Int): List<VolumeReading> =
        listOf(AudioManager.STREAM_MUSIC, CAPTURING_HOST_STREAM).map { stream ->
            write(stream, indexFor(percent, streams.max(stream)))
            reading(stream)
        }

    private fun putMediaBack() {
        val was = before.taken()[MEDIA] ?: return
        streams.set(AudioManager.STREAM_MUSIC, was)
        before.forget(MEDIA)
    }

    /** Puts back whatever was there before this app first changed it, and stops remembering. */
    fun restore() {
        for ((name, index) in before.taken()) streamOf(name)?.let { streams.set(it, index) }
        before.forget()
    }

    /** Whether anything here has been changed and not yet put back. */
    fun changed(): Boolean = before.taken().isNotEmpty()

    private fun write(stream: Int, index: Int) {
        before.remember(nameOf(stream), streams.level(stream))
        streams.set(stream, index)
    }

    private fun reading(stream: Int) =
        VolumeReading(streams.level(stream), streams.max(stream), nameOf(stream))

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
