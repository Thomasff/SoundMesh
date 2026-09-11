package com.soundmesh.probe.sync

/**
 * How long the capture has been handing over digital silence.
 *
 * Not a diagnosis, a reading. A listener on 09-12 lost the whole room part way through a song,
 * found that nudging the host's media volume off zero brought it straight back, and that leaving
 * it on one meant it never happened. Every part of that is consistent with the handset putting its
 * media output path to sleep while nothing audible is coming out of it - and capture taps that
 * path, so a sleeping path is a room playing silence. It is also consistent with two or three
 * other things, and nothing in the app could tell them apart because nothing was looking.
 *
 * Exactly zero rather than quiet. A quiet passage in a song is not this: real audio that nobody
 * can hear still has dither, noise and the bottom bit moving. All samples exactly zero for
 * seconds on end is a path that has stopped producing rather than a song that has gone quiet -
 * and the one case this would misread, a track of true digital silence between songs, is worth
 * misreading, because a listener watching the room play nothing wants to know that too.
 *
 * Process-wide because the thing that reads it is a screen and the thing that writes it is a
 * service, and they do not otherwise meet. Cheap enough to sit in the capture loop: a chunk is
 * 20 ms of audio and the scan stops at the first byte that is not zero, which in music is the
 * first byte.
 */
object CaptureSilence {
    @Volatile private var lastSoundNanos = 0L
    @Volatile private var since = 0L

    /** Called when a capture opens, so a previous session's silence is not this one's. */
    fun watch() {
        lastSoundNanos = System.nanoTime()
        since = 0L
    }

    fun forget() {
        lastSoundNanos = 0L
        since = 0L
    }

    /** Every chunk the capture hands over, on the host's own loop. */
    fun sawChunk(chunk: ByteArray) {
        if (lastSoundNanos == 0L) return
        for (byte in chunk) {
            if (byte.toInt() != 0) {
                lastSoundNanos = System.nanoTime()
                since = 0L
                return
            }
        }
        since = System.nanoTime() - lastSoundNanos
    }

    /** How long every sample has been zero, or zero while nothing is being captured. */
    fun silentNanos(): Long = if (lastSoundNanos == 0L) 0L else since
}
