package com.soundmesh.probe.sync

import com.soundmesh.core.HostId
import java.io.File

/**
 * How far apart every pair of handsets was, the last time a room measured the lot in one window.
 *
 * [StoredSeparation] keeps one number per peer, and the number it keeps is always *this handset to
 * that one* - which is the only distance a pair of phones can measure. A room measures the pairs
 * this handset is in no differently, and also the ones it is not: two other phones facing each
 * other, which is the thing that needs somewhere new to live. There was no place for it, because
 * until a room ran there was no way to produce it.
 *
 * One file for the whole field rather than one per pair, because a field is what is written and a
 * field is what is read. Half a field left behind by a run that died mid-write would be a room
 * that the check below reads as smaller than it is, and a check that quietly examines fewer pairs
 * than it has is worse than one that examines none.
 *
 * Replaced rather than merged, for [StoredSeparation]'s reason: where the phones were standing
 * last week is not evidence about where they are standing now. A room is the unit that was
 * measured, so a room is the unit that is replaced.
 */
class StoredRoomField(private val directory: File) {
    /**
     * Every pair the last room measured, keyed both ways round.
     *
     * Both ways because a caller holding two names has no reason to know which of them sorts
     * first, and making it look one of them up twice is an invitation to look it up once.
     */
    fun read(): Map<Pair<String, String>, Double> {
        val file = file()
        if (!file.isFile) return emptyMap()
        val lines = runCatching { file.readLines() }.getOrElse { return emptyMap() }
        val field = LinkedHashMap<Pair<String, String>, Double>()
        for (line in lines) {
            val fields = line.trim().split(" ")
            if (fields.size != 3) continue
            val (a, b) = fields
            if (!HostId.isValid(a) || !HostId.isValid(b) || a == b) continue
            val metres = fields[2].toDoubleOrNull() ?: continue
            if (!metres.isFinite() || metres <= 0.0) continue
            field[a to b] = metres
            field[b to a] = metres
        }
        return field
    }

    /**
     * The room just measured, replacing whatever was there.
     *
     * Pairs with no answer are dropped rather than written as a hole: what a reader wants to know
     * is which distances it has, and a room that could not read a pair has the same nothing to say
     * about it as a room that never contained it. Which pairs went unanswered is a fact about that
     * run, and it is in that run's report.
     */
    fun write(field: Map<Pair<String, String>, Double?>) {
        val lines = field.mapNotNull { (names, metres) ->
            val (a, b) = names
            if (!HostId.isValid(a) || !HostId.isValid(b) || a == b) return@mapNotNull null
            if (metres == null || !metres.isFinite() || metres <= 0.0) return@mapNotNull null
            "$a $b $metres"
        }
        // An empty room is written as an empty file rather than left alone: a run that measured
        // nothing has replaced the last one's answer with nothing, and leaving the old field in
        // place would let a stale drawing check go on firing on a room that has since changed.
        file().writeText(lines.joinToString("\n"))
    }

    private fun file() = File(directory, FILE_NAME)

    companion object {
        const val FILE_NAME = "room-field-m"
    }
}
