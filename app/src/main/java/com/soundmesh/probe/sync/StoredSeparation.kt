package com.soundmesh.probe.sync

import com.soundmesh.core.HostId
import java.io.File

/**
 * How far apart this handset and one peer were, the last time a calibration measured it.
 *
 * The calibration has always produced this number - it comes out of the flight time between the
 * two speakers, and the pair screen has always shown it - and it has never been kept. Nothing
 * needed it: every gain in this system depends on direction alone, so a distance had no consumer.
 *
 * It has one now, and it is not the one it looks like. It is not used to scale the drawing, or to
 * place anything: it is used to check a drawing a person made. Two icons dragged onto the wrong
 * phones is the one mistake in the room screen whose symptom - a rotation running backwards, a pan
 * moving the wrong way - has no visible cause, and comparing the drawn distances against these is
 * the only mechanism that can catch it. See [com.soundmesh.product.RoomCheck] for how little it
 * can say and why the margin is so wide.
 *
 * Kept beside [StoredCalibration] rather than inside it, and not for tidiness. That file is read
 * on the path that decides when audio is emitted, whole or not at all; a field appended to it
 * would make every existing file unreadable and cost every pair its standing correction.
 */
class StoredSeparation(private val directory: File, private val peerId: String) {
    init {
        // The peer id can arrive off a scanned screen, and it is the file name.
        require(HostId.isValid(peerId)) { "unusable peer id" }
    }

    /** Metres, or null if no calibration with this peer has ever measured one here. */
    fun read(): Double? {
        val file = file()
        if (!file.isFile) return null
        return runCatching { file.readText().trim().toDouble() }
            .getOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * The most recent measurement, replacing whatever was there.
     *
     * Replaced rather than averaged, unlike the alignment correction next door. The two answer
     * different questions: that one estimates a constant of the pair and gets better with every
     * run, while this one describes where the phones were standing, and where they were standing
     * last week is not evidence about where they are standing now.
     */
    fun write(metres: Double) {
        if (!metres.isFinite() || metres <= 0.0) return
        file().writeText(metres.toString())
    }

    private fun file() = File(directory, "$FILE_PREFIX$peerId")

    companion object {
        /**
         * Renamed when the distance stopped being read from the loudest lag.
         *
         * What the old name left behind is metres out - measured 09-11, one unchanged two
         * metre gap read anywhere between 5.11 and 11.86 - and a file holds a number and
         * nothing else, so there is no telling a good one from a bad one after the fact. A
         * new name retires the whole of the old set at once, and retires it for a pair that
         * may never run another calibration, which is what a migration could not do. They are
         * left where they are rather than deleted: a few bytes each, and nothing reads them.
         */
        const val FILE_PREFIX = "separation-edge-m-"
    }
}
