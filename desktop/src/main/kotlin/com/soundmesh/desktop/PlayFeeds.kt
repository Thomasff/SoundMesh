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
        if (songs.prepare()) null else HostProblem.FileUnreadable(songs.skipped().joinToString("; "))

    override fun nextChunk(): ByteArray = songs.nextChunk() ?: ByteArray(HostStream.CHUNK_BYTES)

    override fun name(): String? = songs.playhead()?.name

    override fun hasMore(): Boolean = songs.hasMore()

    override fun close() = Unit
}

/** How [HostSession] opens a capture: an [AppCapture], or a test's stand-in for one. */
internal typealias OpenCapture = (target: CaptureTarget, onPcm: (ByteArray, Int) -> Unit) -> CaptureHandle

/** A feed the room plays from a capture: [AppFeed] or [EverythingFeed]. */
internal interface CapturedFeed : Feed {
    /** What the room is told is playing, which the window gave. */
    val app: String

    /** Chunks made up with silence, for the diagnostics. */
    fun padded(): Int
}

/**
 * Whatever some programs are playing, for as long as the room plays - see [AppCapture], and
 * [AppTurnDown] for why each program goes quiet here while it lasts.
 *
 * One capture to a program, because that is what Windows offers: a capture hears one program and
 * whatever it started. Each one is cut into chunks on its own and the chunks are added, the
 * stream waiting on the first and the rest - on the same sound card's clock - having theirs by
 * then. A program with nothing playing is made up with silence, as one on its own is.
 */
internal class AppFeed(
    private val programs: List<AudioSession>,
    override val app: String,
    private val openCapture: OpenCapture,
    private val turnDown: AppTurnDown
) : CapturedFeed {
    private val feeds = programs.map { CaptureFeed() }
    private val captures = mutableListOf<CaptureHandle>()

    // The fewest: a program with nothing playing makes up every chunk, and says nothing about the rest.
    override fun padded(): Int = feeds.minOf { it.paddedChunks }

    /**
     * Opens every capture before turning anything down, so a program that cannot be captured - an
     * older Windows, a program that has just quit - never leaves it or the others quiet for nothing.
     */
    override fun prepare(): HostProblem? {
        for ((i, program) in programs.withIndex()) {
            try {
                captures.add(openCapture(CaptureTarget(program.pid)) { bytes, length -> feeds[i].push(bytes, length) })
            } catch (e: Exception) {
                close()
                return HostProblem.CaptureFailed(program.name, e.message ?: e.toString())
            }
        }
        return try {
            turnDown.putBack()
            for ((i, program) in programs.withIndex()) captures[i].gain = turnDown.turnDown(program.pid, program.name)
            captures.forEach { it.start() }
            null
        } catch (e: Exception) {
            close()
            HostProblem.CaptureFailed(app, e.message ?: e.toString())
        }
    }

    override fun nextChunk(): ByteArray = mixChunks(feeds.map { it.nextChunk() })

    override fun name(): String = app

    override fun hasMore(): Boolean = true

    override fun close() {
        for (capture in captures) runCatching { capture.close() }
        captures.clear()
        feeds.forEach { it.close() }
        turnDown.putBack()
    }
}

/**
 * 所有声音: everything this machine plays but SoundMesh itself, programs started after the room
 * did included - one capture that leaves this process out, see [AppCapture].
 *
 * The same [AppTurnDown] as a program on its own, done to every row in the mixer and the system
 * sounds, so nothing is heard here live and again a lead later from the room. A program that
 * starts while the room plays has a row nobody turned down: it is looked for every [rescanMillis]
 * and turned down when found. Until then it is on this machine's speakers as it would be, and its
 * packets, a thousand times too loud for the capture's gain, go to the room as silence - see
 * [AppCapture.overloads].
 */
internal class EverythingFeed(
    override val app: String,
    private val ownPid: Long,
    private val openCapture: OpenCapture,
    private val turnDown: AppTurnDown,
    private val mixer: AppMixer,
    private val rescanMillis: Long = RESCAN_MILLIS
) : CapturedFeed {
    private val feed = CaptureFeed()
    private var capture: CaptureHandle? = null
    @Volatile private var watching = false
    private var watcher: Thread? = null

    override fun padded(): Int = feed.paddedChunks

    override fun prepare(): HostProblem? = try {
        val opened = openCapture(CaptureTarget(ownPid, exclude = true)) { bytes, length -> feed.push(bytes, length) }
        capture = opened
        turnDown.putBack()
        turnDownEveryRow()
        opened.gain = AppTurnDown.GAIN
        opened.start()
        watching = true
        watcher = Thread(::watch, "turn-down-watch").apply {
            isDaemon = true
            start()
        }
        null
    } catch (e: Exception) {
        close()
        HostProblem.CaptureFailed(app, e.message ?: e.toString())
    }

    private fun turnDownEveryRow() {
        turnDown.turnDown(AppTurnDown.SYSTEM_SOUNDS, SYSTEM_SOUNDS_NAME)
        // The mixer's list leaves this process out, so what this machine plays of the room stays.
        for (row in mixer.list()) turnDown.turnDown(row.pid, row.name)
    }

    private fun watch() {
        while (watching) {
            try {
                Thread.sleep(rescanMillis)
            } catch (e: InterruptedException) {
                return
            }
            if (!watching) return
            runCatching { turnDownEveryRow() }
        }
    }

    override fun nextChunk(): ByteArray = feed.nextChunk()

    override fun name(): String = app

    override fun hasMore(): Boolean = true

    // The watcher stopped before anything is put back, so nothing is turned down after.
    override fun close() {
        watching = false
        watcher?.let {
            it.interrupt()
            it.join(JOIN_MILLIS)
        }
        watcher = null
        runCatching { capture?.close() }
        capture = null
        feed.close()
        turnDown.putBack()
    }

    companion object {
        /** A quarter of a second: how long a program that has just started can go unheard by the room. Picked. */
        const val RESCAN_MILLIS = 250L

        private const val JOIN_MILLIS = 2000L

        /** What the record calls the system sounds, and so what the window would, were they left down. */
        const val SYSTEM_SOUNDS_NAME = "System sounds"
    }
}

/** [chunks] of 16-bit samples added one sample to one, held at full scale rather than wrapped round. */
internal fun mixChunks(chunks: List<ByteArray>): ByteArray {
    if (chunks.size == 1) return chunks.single()
    val out = ByteArray(chunks.maxOf { it.size })
    for (i in 0 until out.size - 1 step 2) {
        var sum = 0
        for (chunk in chunks) if (i + 1 < chunk.size) sum += (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
        val held = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        out[i] = held.toByte()
        out[i + 1] = (held shr 8).toByte()
    }
    return out
}
