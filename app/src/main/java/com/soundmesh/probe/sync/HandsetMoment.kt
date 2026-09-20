package com.soundmesh.probe.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager

/**
 * What the handset was doing at one instant, written as one line into the record.
 *
 * Taken because of 2026-09-14. The room went silent twice that evening; both times the capture was
 * handing over exact zeros while every sink played them faithfully, and both times **the only way
 * to look at the host was to plug it in - which brought the sound back within half a second**. So
 * the fault could never be examined in the state it failed in, and every reading that mattered was
 * taken after it had already been repaired by the act of taking it.
 *
 * The log buffer is no answer either: it is a ring, and the flood that arrives with the cable
 * pushed the whole failing window out of it. A line in a file survives a night on battery, a
 * handset carried to another room, and a person who is not at this address tomorrow.
 *
 * So the fields here are not a general health report. They are the things that were argued about
 * that evening and could not be settled:
 *
 * - [charger] - the one variable that lines up with both failures and with neither of the two runs
 *   that were fine. It is also what this ROM's own audio service watches: plugging in logs
 *   `ACTION_BATTERY_CHANGED plugged:2`, then `restart dms`, and the audio returns ten milliseconds
 *   later.
 * - [musicActive] - whether the handset itself believes anything is playing. If this says no while
 *   a music app is plainly playing, there is nothing left to argue about.
 * - [musicIndex]/[musicMuted] - the zero this app writes itself, which has been the suspect since
 *   09-12 and has twice been observed at zero while the room sounded perfect.
 * - [screenOn], [audioMode], [batteryPercent] - the three that were guessed at on the night.
 * - [players] - added 09-20, because until it existed this record could not tell the fault from
 *   somebody pausing their music. Both look identical in every other field: the capture hands over
 *   zeros and the handset says nothing is active on the media stream. A listener pointed that out
 *   after eighteen of these had been counted as faults, which is eighteen records of two things
 *   added together. A paused player leaves the active list; a player that is still running while
 *   something else silences its output stays in it. Written as the count and every usage rather
 *   than as a verdict, because other apps' entries arrive anonymised and how much of one survives
 *   on a given ROM is not something to assume - the first record answers it.
 *
 * Every field is nullable and a reading nobody could take prints `?`. A zero standing in for "the
 * handset would not say" would be the answer this line exists to establish, written as though it
 * had been established.
 */
internal data class HandsetMoment(
    val charger: String?,
    val screenOn: Boolean?,
    val musicActive: Boolean?,
    val audioMode: String?,
    val musicIndex: Int?,
    val musicMax: Int?,
    val musicMuted: Boolean?,
    val playing: String?,
    val batteryPercent: Int?,
    val players: String?
) {
    override fun toString(): String =
        "charging=${charger ?: UNKNOWN} screen=${said(screenOn, "on", "off")} " +
            "music-active=${said(musicActive, "true", "false")} mode=${audioMode ?: UNKNOWN} " +
            "media=${musicIndex ?: UNKNOWN}/${musicMax ?: UNKNOWN} " +
            "media-muted=${said(musicMuted, "yes", "no")} " +
            "playing=${playing ?: UNKNOWN} " +
            "battery=${batteryPercent?.let { "$it%" } ?: UNKNOWN} " +
            "players=${players ?: UNKNOWN}"

    private fun said(value: Boolean?, yes: String, no: String) =
        when (value) { true -> yes; false -> no; null -> UNKNOWN }

    companion object {
        private const val UNKNOWN = "?"
    }
}

/**
 * Reads one now, from a handset that may refuse any part of it.
 *
 * Each reading is taken on its own so that one that throws costs one field rather than the line -
 * a record that is missing because it could not be completed is the same as no record at all, and
 * this one gets written at most twice a fault.
 *
 * [playingStream] is the stream this handset's own output is on, which is not [AudioManager
 * .STREAM_MUSIC] while capturing and is the whole reason media can be at zero without the room
 * being silent.
 */
internal fun momentOf(context: Context, playingStream: Int): HandsetMoment {
    val audio = runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull()
    val battery = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    return HandsetMoment(
        charger = battery?.let { chargerName(it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) },
        screenOn = runCatching {
            context.getSystemService(PowerManager::class.java).isInteractive
        }.getOrNull(),
        musicActive = runCatching { audio?.isMusicActive }.getOrNull(),
        audioMode = runCatching { audio?.mode?.let(::modeName) }.getOrNull(),
        musicIndex = runCatching { audio?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull(),
        musicMax = runCatching { audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull(),
        musicMuted = runCatching { audio?.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull(),
        playing = runCatching {
            audio?.let {
                "${streamName(playingStream)}:${it.getStreamVolume(playingStream)}" +
                    "/${it.getStreamMaxVolume(playingStream)}"
            }
        }.getOrNull(),
        batteryPercent = battery?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) null else level * 100 / scale
        },
        players = runCatching {
            audio?.activePlaybackConfigurations?.let { all ->
                // Count first so an empty list is still an answer, then every usage, ours included
                // - which one is ours is read off the number rather than filtered out here, so a
                // ROM that reports something unexpected says so instead of being quietly dropped.
                all.joinToString(",", prefix = "${all.size}:") {
                    it.audioAttributes.usage.toString()
                }
            }
        }.getOrNull()
    )
}

private fun chargerName(plugged: Int) = when (plugged) {
    0 -> "none"
    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
    else -> null
}

private fun modeName(mode: Int) = when (mode) {
    AudioManager.MODE_NORMAL -> "NORMAL"
    AudioManager.MODE_RINGTONE -> "RINGTONE"
    AudioManager.MODE_IN_CALL -> "IN_CALL"
    AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
    else -> "mode$mode"
}

private fun streamName(stream: Int) = when (stream) {
    AudioManager.STREAM_MUSIC -> "MEDIA"
    AudioManager.STREAM_ALARM -> "ALARM"
    AudioManager.STREAM_NOTIFICATION -> "NOTIFICATION"
    else -> "stream$stream"
}
