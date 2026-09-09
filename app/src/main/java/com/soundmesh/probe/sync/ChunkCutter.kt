package com.soundmesh.probe.sync

/**
 * Cuts a stream of decoded audio into the one chunk size the renderer plays.
 *
 * A decoder's output buffers are whatever it felt like producing, and the converter downstream of
 * it emits whatever it could convert without reading samples that have not arrived - so neither
 * of them lands on a chunk boundary except by accident. What is left over is carried into the
 * next piece rather than padded or dropped: a chunk is twenty milliseconds by definition, and one
 * that were shorter would play at a rate set by the decoder's buffer sizes rather than by the
 * timeline, while every number in the run report stayed sane.
 *
 * The whole-buffer source has no use for this. It knows where the song ends before it starts, so
 * it truncates to a whole number of chunks once and reads by arithmetic afterwards. A stream does
 * not know where the end is until it gets there, which is also why the carry has to survive the
 * seam: the last part-chunk of one pass is finished by the first bytes of the next.
 */
class ChunkCutter(private val chunkBytes: Int) {
    init {
        require(chunkBytes > 0) { "a chunk has to be some bytes: $chunkBytes" }
    }

    private var carry = ByteArray(0)

    /** Bytes held back, always under one chunk - the whole of what this costs to keep. */
    internal val carried: Int get() = carry.size

    /** Every whole chunk this piece completes, in order. */
    fun cut(pcm: ByteArray): List<ByteArray> {
        val bytes = if (carry.isEmpty()) pcm else carry + pcm
        val whole = bytes.size / chunkBytes
        carry = bytes.copyOfRange(whole * chunkBytes, bytes.size)
        if (whole == 0) return emptyList()
        return List(whole) { bytes.copyOfRange(it * chunkBytes, (it + 1) * chunkBytes) }
    }
}
