package com.soundmesh.probe.sync

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * What the phone already answers to elsewhere, or its model number if it answers to
 * nothing. Never null, so a handset always has something to be called.
 *
 * Kept here rather than beside [StoredHandsetName], which moved to core: this half reads the
 * system, and core does not have one to read.
 */
fun systemHandsetName(context: Context): String =
    runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()?.let(StoredHandsetName::cleaned) ?: StoredHandsetName.cleaned(Build.MODEL) ?: UNNAMED_HANDSET

/** This handset's name: the one it was given here, or the one the phone already answers to. */
fun handsetName(context: Context): String =
    StoredHandsetName(context.filesDir).read() ?: systemHandsetName(context)

/** For a handset whose own name could not be read at all, which is not expected to happen. */
private const val UNNAMED_HANDSET = "handset"
