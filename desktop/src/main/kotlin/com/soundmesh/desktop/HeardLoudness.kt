package com.soundmesh.desktop

import kotlin.math.sqrt

/**
 * How loud each stretch handed to the engine was, so the level of what is sounding *now* can be
 * looked up by the frame the engine is on.
 *
 * The handset measures as it writes, and there that is close enough: its track takes a chunk at a
 * time. Here the writer keeps the engine's whole buffer full, 200 ms, so what was just written is
 * heard a fifth of a second later - and a light a fifth of a second ahead of the beat is a light
 * somebody sees is wrong. So each stretch's level is kept against the frames it covers, and the
 * reader asks for the frame that is playing.
 *
 * Writer thread only: [wrote] and [at] are both called with the renderer's lock held.
 */
class HeardLoudness {
    private val starts = LongArray(KEPT) { NOTHING }
    private val lengths = IntArray(KEPT)
    private val levels = FloatArray(KEPT)
    private var next = 0

    /** [frames] frames from [start] were handed over at [level]. */
    fun wrote(start: Long, frames: Int, level: Float) {
        starts[next] = start
        lengths[next] = frames
        levels[next] = level
        next = (next + 1) % KEPT
    }

    /** The level of the stretch holding [frame], or silence when no kept stretch does. */
    fun at(frame: Long): Float {
        for (k in 0 until KEPT) {
            val start = starts[k]
            if (start != NOTHING && frame >= start && frame < start + lengths[k]) return levels[k]
        }
        return 0f
    }

    companion object {
        /**
         * Stretches kept. The writer hands one over about every 10-16 ms, so this is well over a
         * second - several buffers - and the one being played is always among them.
         */
        const val KEPT = 96

        private const val NOTHING = Long.MIN_VALUE

        /**
         * The handset's loudnessOf, from a sum of squares: RMS over [samples] samples as a share of
         * full scale, 0..1. RMS rather than peak for the handset's reason - a peak meter spends most
         * of a song pinned.
         */
        fun levelOf(squares: Double, samples: Int): Float =
            if (samples <= 0) 0f else (sqrt(squares / samples) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }
}
