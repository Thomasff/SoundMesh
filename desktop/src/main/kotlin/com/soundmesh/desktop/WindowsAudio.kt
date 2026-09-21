package com.soundmesh.desktop

import java.lang.foreign.Arena

/**
 * The two questions that can be asked of the output device without opening anything.
 *
 * This started as the whole of the spike, carrying its own copy of the vtable walk and the GUID
 * builder. That copy is gone: [Wasapi] is the one place that knows how a COM call is made, and a
 * second implementation of it would be the third time on this project that a mechanism got
 * written out again beside the one that already worked.
 *
 * Nothing here opens a stream, so nothing here makes a sound.
 */
object WindowsAudio {

    /** Ticks per second on the clock WASAPI stamps positions with. */
    fun qpcFrequency(): Long = Arena.ofConfined().use { arena ->
        Wasapi.qpcFrequency(arena.allocate(8, 8))
    }

    /**
     * What the default render endpoint is mixing at, which is what anything playing here has to
     * match.
     *
     * Asked through a renderer that is built and immediately closed: [WasapiRenderer] does not
     * start the stream in its constructor, so this costs one Initialize and no sound. Keeping one
     * path to the endpoint is worth more than saving that call.
     */
    fun defaultRenderMixFormat(): MixFormat = WasapiRenderer().use { it.format }
}
