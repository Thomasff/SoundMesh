package com.soundmesh.desktop

/** Where one play's audio comes from, as [HostSession] streams it. */
internal interface Feed {
    /** Null once it can play, or why it cannot play at all. Said before anybody is told to play. */
    fun prepare(): HostProblem?

    /** The next chunk, on the stream's thread. */
    fun nextChunk(): ByteArray

    /** What the room is told is playing, or null. */
    fun name(): String?

    /** False once there is nothing left; the room then stops by itself. */
    fun hasMore(): Boolean

    /** Called once, however the play ended. */
    fun close()
}

/** A list of songs, one after another - see [SongList]. */
internal class SongFeed(private val songs: SongList) : Feed {
    override fun prepare(): HostProblem? =
        if (songs.prepare()) null else HostProblem.FileUnreadable(songs.skipped().joinToString("；"))

    override fun nextChunk(): ByteArray = songs.nextChunk() ?: ByteArray(HostStream.CHUNK_BYTES)

    override fun name(): String? = songs.playhead()?.name

    override fun hasMore(): Boolean = songs.hasMore()

    override fun close() = Unit
}

/**
 * Whatever one program is playing, for as long as the room plays - see [AppCapture], and
 * [AppTurnDown] for why the program goes quiet here while it lasts.
 */
internal class AppFeed(
    private val pid: Long,
    val app: String,
    private val openCapture: (pid: Long, onPcm: (ByteArray, Int) -> Unit) -> CaptureHandle,
    private val turnDown: AppTurnDown
) : Feed {
    private val feed = CaptureFeed()
    private var capture: CaptureHandle? = null

    fun padded(): Int = feed.paddedChunks

    /**
     * Opens the capture before turning anything down, so a program that cannot be captured - an
     * older Windows, a program that has just quit - is never left quiet for nothing.
     */
    override fun prepare(): HostProblem? = try {
        val opened = openCapture(pid) { bytes, length -> feed.push(bytes, length) }
        capture = opened
        opened.gain = turnDown.turnDown(pid, app)
        opened.start()
        null
    } catch (e: Exception) {
        close()
        HostProblem.CaptureFailed(app, e.message ?: e.toString())
    }

    override fun nextChunk(): ByteArray = feed.nextChunk()

    override fun name(): String = app

    override fun hasMore(): Boolean = true

    override fun close() {
        runCatching { capture?.close() }
        capture = null
        feed.close()
        turnDown.putBack()
    }
}
