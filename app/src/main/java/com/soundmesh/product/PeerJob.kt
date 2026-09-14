package com.soundmesh.product

/** Which measurement the peer-calibration screen should open on. */
enum class PeerJob { PAIR, ROOM, OVERHEAD }

/** The extra's name, and the key it travels under. */
const val PEER_JOB_EXTRA = "peer_job"

/**
 * Anything unrecognised - including nothing at all - is the pair measurement. That is what ADB
 * has always started this activity to do, with no extra, and that has to keep working.
 */
internal fun peerJobOf(extra: String?): PeerJob =
    PeerJob.entries.firstOrNull { it.name == extra } ?: PeerJob.PAIR
