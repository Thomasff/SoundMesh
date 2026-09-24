package com.soundmesh.product

/**
 * Writes down that something did not go out, once per reason rather than once per attempt.
 *
 * Per attempt, this is a line a second for as long as the fault lasts - 1,600 of them in one
 * evening on 2026-09-14, all identical, hiding the handful of lines that said what was happening
 * around them. What a person reading the timeline needs is when it started, what it says, and when
 * it stopped.
 *
 * Remembered per reason. It was one slot until 2026-09-24, and two reasons refused by the same dead
 * line - the volume and the still-here - took turns being new, so each wrote itself every second
 * anyway, and seventeen minutes of that filled the whole timeline on X10.
 */
internal class Complaints(private val write: (String) -> Unit) {
    /** The last thing written for each reason, so it is written once and not again. */
    private val complained = HashMap<String, String>()

    /** How many did not go out since the last that did, which is the other half of one line. */
    var missed = 0
        private set

    fun complain(what: String, why: String) {
        missed++
        val said = "$what: $why"
        if (complained[what] == said) return
        complained[what] = said
        write(said)
    }

    /** And the other end of it, which is the line that says the fault is over. */
    fun said(what: String) {
        if (complained.isEmpty()) return
        write("$what said again, after $missed that did not go out")
        complained.clear()
        missed = 0
    }
}
