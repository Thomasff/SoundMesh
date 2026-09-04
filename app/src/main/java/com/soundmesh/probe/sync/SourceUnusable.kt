package com.soundmesh.probe.sync

/**
 * A PCM source that cannot be used for the run, carrying the code the report should show.
 *
 * The run's failure handler names an exception by its class, so a plain IllegalStateException
 * reports as "IllegalStateException" and the message describing what was actually wrong with the
 * file or the capture format never reaches the artifact. Same reason ClockOffsetUnavailable exists.
 */
class SourceUnusable(val code: String) : IllegalStateException(code)
