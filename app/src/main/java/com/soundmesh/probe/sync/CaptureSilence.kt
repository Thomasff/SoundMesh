package com.soundmesh.probe.sync

/**
 * How long the capture has been handing over digital silence, and a record of every time it did.
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
 *
 * Nothing is silent until it has made a sound. A capture that has never handed over a single
 * non-zero sample is not a path that stopped producing - it is a listener who opened the capture
 * and has not started any music yet, which is what happened on 09-13: the capture was opened to
 * look at the room drawing, no music was ever played, and eighty-six seconds of nothing were
 * filed as a fault. So the reading stays at zero until the first sound arrives.
 *
 * [onSpell] is the half that outlives the screen. The red line only helps somebody who is looking
 * at this app, and somebody playing music is not: on 09-12 it happened in the middle of a song, to
 * a handset in a pocket, and was over by the time anyone could have looked. So a stretch past
 * [SPELL_NANOS] is handed to whoever is keeping the record, once, when it ends.
 */
object CaptureSilence {
    /**
     * Longer than any gap between two tracks, shorter than anybody's patience with a silent room.
     *
     * One number for two readers on purpose: the red line on the screen and the line in the record
     * are the same decision about how long is long enough, and two constants that mean one thing
     * are two constants that drift.
     */
    const val SPELL_SECONDS = 4
    const val SPELL_NANOS = SPELL_SECONDS * 1_000_000_000L

    @Volatile private var watching = false
    @Volatile private var heardAnything = false
    @Volatile private var lastSoundNanos = 0L
    @Volatile private var since = 0L
    @Volatile private var inSpell = false
    @Volatile private var now: () -> Long = System::nanoTime
    @Volatile private var onSpell: (Long, Boolean) -> Unit = { _, _ -> }

    /**
     * Called when a capture opens, so a previous session's silence is not this one's.
     *
     * [now] is a parameter rather than a call because a test for something measured in seconds
     * would otherwise have to take seconds.
     */
    fun watch(
        now: () -> Long = System::nanoTime,
        onSpell: (silentNanos: Long, recovered: Boolean) -> Unit = { _, _ -> }
    ) {
        this.now = now
        this.onSpell = onSpell
        watching = true
        heardAnything = false
        lastSoundNanos = now()
        since = 0L
        inSpell = false
    }

    /** A capture that closes mid-spell still files it: never coming back is the louder answer. */
    fun forget() {
        if (inSpell) file(since, recovered = false)
        watching = false
        heardAnything = false
        since = 0L
        inSpell = false
    }

    /** Every chunk the capture hands over, on the host's own loop. */
    fun sawChunk(chunk: ByteArray) {
        if (!watching) return
        for (byte in chunk) {
            if (byte.toInt() != 0) {
                if (inSpell) file(since, recovered = true)
                heardAnything = true
                lastSoundNanos = now()
                since = 0L
                return
            }
        }
        if (!heardAnything) return
        since = now() - lastSoundNanos
        if (since >= SPELL_NANOS) inSpell = true
    }

    /** How long every sample has been zero, or zero while nothing is being captured. */
    fun silentNanos(): Long = if (!watching) 0L else since

    private fun file(silentNanos: Long, recovered: Boolean) {
        inSpell = false
        // Whoever keeps the record is on the other side of this call, and a record that throws
        // must not be able to stop a capture that is working again.
        runCatching { onSpell(silentNanos, recovered) }
    }
}
