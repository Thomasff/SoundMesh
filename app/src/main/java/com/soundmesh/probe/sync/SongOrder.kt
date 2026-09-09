package com.soundmesh.probe.sync

import java.text.Collator
import java.util.Locale

/**
 * One playable thing, named the way the listener's own file manager names it.
 *
 * [uri] is a string rather than a `Uri` so that everything deciding what plays and in what order
 * can be decided off a handset. What holds the string is the platform's business; what is done
 * with it is not.
 */
data class Song(val uri: String, val name: String, val mimeType: String?)

/**
 * Which of a folder's files are songs, and what order they play in.
 *
 * A layer of its own, and a pure one, for the same reason StateWording is: both halves of this
 * fail without any symptom. A filter that is too strict leaves a song out of a folder the listener
 * is looking at; too loose puts a cover image in the playlist, where it becomes a refusal in the
 * middle of an evening. And any order at all looks like an order - a folder played out of sequence
 * is only wrong to somebody who knows the album.
 */
object SongOrder {
    /**
     * The songs among [entries], in the order they should play.
     *
     * The collator is built here rather than held as a constant because it is not safe to share
     * between threads, and one per sort costs nothing next to reading a folder.
     */
    fun of(entries: List<Song>): List<Song> =
        entries.filter(::isSong).sortedWith(byName(Collator.getInstance(Locale.CHINA)))

    /**
     * The type if the provider gave a usable one, the extension if it did not.
     *
     * Providers disagree about what a .flac is - audio/flac, application/octet-stream, or nothing
     * at all - so a type that says nothing is not taken as a no. It is taken as no answer, and the
     * name is asked instead. The mistake this avoids is the quieter of the two: a song that is
     * sitting in the folder in plain sight and does not play.
     */
    private fun isSong(entry: Song): Boolean {
        val type = entry.mimeType?.substringBefore('/')?.lowercase()
        return when (type) {
            "audio" -> true
            // The one type that has to say no out loud. Everything else a provider might answer -
            // an image, a document, application/octet-stream - is already carrying an extension
            // this does not know, so the name refuses it without help. Video is the exception
            // because .m4a and .mp4 are the same container, so a video file can arrive wearing an
            // extension from the list below.
            "video" -> false
            else -> entry.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
        }
    }

    /**
     * Every run of digits compares as a number, so ten follows nine.
     *
     * Compared as text, "track10" comes before "track2", which is how album order is lost: the
     * folder still plays, in an order that looks deliberate. Padding is not part of the number
     * either - a folder holding both "02" and "3" is one somebody ripped twice.
     */
    private fun byName(collator: Collator) = Comparator<Song> { left, right ->
        val ordered = compareNaturally(left.name, right.name, collator)
        // Names differing only in case still need one definite answer, or a folder plays in a
        // different sequence each time it is opened and nothing on screen could explain why.
        if (ordered != 0) ordered else left.name.compareTo(right.name)
    }

    /**
     * Runs of digits compare as numbers; everything between them compares the way a Chinese
     * reader expects.
     *
     * The text used to be walked one character at a time and compared by code unit, which for
     * Latin names is alphabetical and for Chinese ones is not order at all - it is the order the
     * characters happen to sit at in Unicode. A folder of six songs came out as
     * 倔强 / 和你一样 / 追梦赤子心 / 闪耀 where the file manager beside it read
     * 和你一样 / 倔强 / 闪耀 / 追梦赤子心. Both are stable and reproducible; only one of them is
     * an order anybody can predict. The same code-unit rule is why a file named zzz- sorted third
     * rather than last in a folder of Chinese names: ASCII comes before every CJK character.
     *
     * Whole runs rather than single characters, because a collator's answer for one character is
     * not the same question - the sound of a name is a property of the word.
     *
     * A limit worth knowing: the collation here is the runtime's, and the JVM this is tested on
     * and the ICU on a handset are not guaranteed to be the same table. The test below fixes the
     * mechanism - pinyin rather than code units - not the exact sequence a device will produce.
     */
    private fun compareNaturally(left: String, right: String, collator: Collator): Int {
        var l = 0
        var r = 0
        while (l < left.length && r < right.length) {
            if (left[l].isDigit() && right[r].isDigit()) {
                val leftEnd = digitsEnd(left, l)
                val rightEnd = digitsEnd(right, r)
                val ordered = compareNumbers(left.substring(l, leftEnd), right.substring(r, rightEnd))
                if (ordered != 0) return ordered
                l = leftEnd
                r = rightEnd
            } else {
                val leftEnd = textEnd(left, l)
                val rightEnd = textEnd(right, r)
                val ordered = collator.compare(left.substring(l, leftEnd), right.substring(r, rightEnd))
                if (ordered != 0) return ordered
                l = leftEnd
                r = rightEnd
            }
        }
        return (left.length - l).compareTo(right.length - r)
    }

    private fun textEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length && !text[end].isDigit()) end++
        return end
    }

    private fun digitsEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length && text[end].isDigit()) end++
        return end
    }

    /** By value, and by length first, so that no track number is long enough to overflow. */
    private fun compareNumbers(left: String, right: String): Int {
        val l = left.trimStart('0')
        val r = right.trimStart('0')
        return if (l.length != r.length) l.length.compareTo(r.length) else l.compareTo(r)
    }

    /**
     * What Android's own decoders read, minus the containers that are usually video.
     *
     * Only consulted when the provider would not say, so a wrong guess here costs a refusal at the
     * moment that song comes up rather than a folder that will not open.
     */
    private val AUDIO_EXTENSIONS =
        setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr", "mka", "ape", "wma")
}
