package com.soundmesh.probe.sync

import kotlin.math.roundToInt

/**
 * Where a percentage lands on a scale of [max] steps.
 *
 * A percentage is what travels between handsets, because they do not agree on how many steps a
 * stream has - fifteen on one, sixteen on the next - so an index set across a room is a different
 * loudness on every handset in it. The rounding is stated rather than left to integer division:
 * 60% of fifteen steps is nine and not eight, and the two are a step apart everywhere.
 */
fun indexFor(percent: Int, max: Int): Int =
    (percent.coerceIn(0, 100) * max / 100.0).roundToInt().coerceIn(0, max)

/** The other direction, for saying on a screen what a handset actually landed on. */
fun percentOf(index: Int, max: Int): Int =
    if (max <= 0) 0 else (index * 100.0 / max).roundToInt().coerceIn(0, 100)
