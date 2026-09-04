package com.soundmesh.core

import java.security.SecureRandom

/**
 * The name one handset is known by across runs.
 *
 * Discovery can say a host is there and the run can say the audio lined up, but neither can say
 * that the host this run met is the host the last run met. That question has had no answer, and
 * everything that wants to remember something about a partner - the alignment correction first -
 * has had to pretend there is only ever one.
 *
 * Fixed length hexadecimal, because of where the value goes rather than for looks. It is a field
 * in a space separated code, and it names the file a peer's calibration is kept in. A value that
 * arrived off a scanned screen and could hold a space or a path segment would decide either one,
 * so the shape is checked wherever one is read rather than trusted from where it came.
 */
object HostId {
    /** 64 bits. Collisions matter within one room, not across a population. */
    const val LENGTH = 16

    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(LENGTH / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isValid(id: String?): Boolean =
        id != null && id.length == LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' }
}
