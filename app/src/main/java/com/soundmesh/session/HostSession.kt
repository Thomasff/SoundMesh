package com.soundmesh.session

import android.util.Log
import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ChunkTimeline
import com.soundmesh.core.DriftController
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.SessionState
import com.soundmesh.core.SpatialField
import com.soundmesh.probe.PlaybackUsage
import com.soundmesh.probe.sync.ChunkServer
import com.soundmesh.probe.sync.Playhead
import com.soundmesh.probe.sync.ClockSyncServer
import com.soundmesh.probe.sync.SpatialFieldServer
import com.soundmesh.probe.sync.SyncActivity
import com.soundmesh.probe.sync.SyncRenderer

/**
 * The fields a host adds to its renderer's report.
 *
 * A function of its values rather than a method on the session, so that it can be tested at all: a
 * started HostSession binds three sockets and its renderer opens an AudioTrack. Same shape and the
 * same reason as trackProfileJson next door.
 *
 * [roomPeerIds] is the whole room with this handset first, while [sinks] counts open audio
 * sockets, so a room where nobody has left has exactly one more name than it has connections.
 *
 * The two are gathered over different channels on purpose, and their disagreement is the only
 * thing this host can say about a handset that left. A sink announces its name on the control
 * channel and that name is held until a write to its socket fails - which happens when the
 * listener next touches the drawing, and not before - so the roster is a record of who joined
 * rather than of who is still here. The connection count is the live one, late by however long
 * the kernel keeps retransmitting to a handset that walked out of the network, measured at 26.9
 * seconds.
 *
 * [audioPeerIds] is the same room seen from the audio channel, and it is why the two above no
 * longer only pose the question: a name in [roomPeerIds] and not in here is the handset that
 * stopped being sent audio, by name. It is shorter than [sinks] by however many sinks are of a
 * build that predates the name, and those are the ones this still cannot tell apart.
 *
 * [replacedSinks] tells the two ways the numbers disagree apart: a connection replaced is a
 * handset that came back, so a run whose sinks and roster disagree with this at zero is a handset
 * that left and did not return.
 */
internal fun hostReportFields(
    maxBroadcastNanos: Long,
    generated: Int,
    droppedToSinks: Int,
    sinks: Int,
    replacedSinks: Int,
    roomPeerIds: List<String>,
    audioPeerIds: List<String>,
    unnamedSinks: Int,
    lateChunks: Int,
    skippedSongs: Int
): String =
    "\"maxBroadcastNanos\":$maxBroadcastNanos,\"generated\":$generated" +
        ",\"droppedToSinks\":$droppedToSinks,\"sinks\":$sinks" +
        // A returning handset used to be a second sink for as long as the kernel kept writing
        // to the socket it left. It is not any more, and this is the only trace it leaves.
        ",\"replacedSinks\":$replacedSinks" +
        // Whole names rather than the four characters a screen shows. This report is also written
        // to a file that a later run reads, and a prefix cannot be matched back against a stored
        // calibration or a stored separation, which are kept under the whole name.
        ",\"roomPeerIds\":\"${roomPeerIds.joinToString(",")}\"" +
        // The same room as the audio channel has it. Whole names for the same reason, and read
        // against the line above rather than alone: which of them fell silent is the difference
        // of the two lists, and neither list on its own says it.
        ",\"audioPeerIds\":\"${audioPeerIds.joinToString(",")}\"" +
        // A handset that could not say its name gets no rule and is in no drawing, which from the
        // room looks like one phone quietly staying flat. A build speaking another version would
        // do it to all of them at once, and only this says so.
        ",\"unnamedSinks\":$unnamedSinks" +
        // The renderer waiting on a source that decodes while it plays. Nothing else says it: the
        // 1.5 s lead and the scheduler's three seconds absorb a burst completely, so a decoder
        // that fell behind and caught up again leaves no other mark anywhere in a run.
        ",\"lateChunks\":$lateChunks" +
        // Songs in a folder that would not play. A folder plays on without them and sounds exactly
        // like a folder that never held them.
        ",\"skippedSongs\":$skippedSongs"

/**
 * The host instant at which a song that has just run out has finished being heard.
 *
 * A function of its values rather than a method on the session, so that it can be tested at all -
 * the same reason, and the same shape, as hostReportFields above.
 *
 * The number that matters is that this is **not** now. Every chunk is stamped 1.5 s into its own
 * future and the sinks are holding their copies of those same instants, so a session that ended
 * the renderer the moment its source ran dry would cut a second and a half off the end of every
 * song, on every handset at once, and would sound exactly like somebody pressing stop early.
 * Nothing downstream would report it: the chunks were generated, broadcast and scheduled, and the
 * counters would say so.
 *
 * [NOTHING_PLAYED] is a source that ended before its first chunk. The file sources refuse that at
 * the moment the song is chosen, so this is here only so that a source which ever did would end
 * the session rather than name an instant a quarter of a millennium ago.
 */
internal fun endOfAudioNanos(lastDueNanos: Long, nowNanos: Long): Long =
    if (lastDueNanos == NOTHING_PLAYED) nowNanos else lastDueNanos + SyncRenderer.CHUNK_NANOS

/** No chunk has been produced yet, so there is no last one to play out. */
internal const val NOTHING_PLAYED = Long.MIN_VALUE

/**
 * Where the room is in a song, given where the source has read to.
 *
 * A function of its values so it can be tested off a handset, like [endOfAudioNanos] above.
 *
 * Every chunk is stamped a lead into its own future, so what is being heard now was read from the
 * source that long ago. Drawn at the source's own position instead, a slider sits a lead ahead of
 * the music - and for the first 1.5 s of every song it would be showing a place the song has not
 * reached, which is where the clamp comes in rather than as tidiness.
 */
internal fun heardMicros(sourceMicros: Long, durationMicros: Long, leadMicros: Long): Long =
    (sourceMicros - leadMicros).coerceIn(0L, maxOf(0L, durationMicros))

/**
 * The handset that holds the timeline: it decides when every chunk is heard and plays its own copy
 * alongside the sinks.
 *
 * Host time is this device's own `nanoTime`, so there is nothing to converge and no offset to
 * apply. That is the whole of the asymmetry between the two roles - everything else here has a
 * mirror in [SinkSession].
 *
 * [readChunk] supplies one chunk of PCM per call and is asked for the next one only when the
 * timeline is ready for it, so a source may block in it for as long as a chunk lasts without
 * costing anything. Null from it is the song reaching its end - see [onEnded].
 */
class HostSession(
    private val readChunk: () -> ByteArray?,
    /**
     * How far the drift loop lets the write position wander before it corrects it.
     *
     * Settable, and known not to be the knob that matters here. It was the first guess at the
     * clicks - the 48-frame default is 1 ms and one handset's emission moves in ~56-frame steps,
     * so a step would cross it every time - and the guess was wrong. Widening it to 96 frames on a
     * product session moved the trim rate from 3.30 a second to 3.32. What this governs is when
     * the loop corrects; what decides whether the waveform gets cut is [trimFrames].
     */
    deadbandFrames: Int = DriftController.DEFAULT_DEADBAND_FRAMES,
    /**
     * The renderer's trim band, and with it the scheduler's early release.
     *
     * The knob that does move the clicks - see [PRODUCT_TRIM_FRAMES] for the measurement and for
     * why the product's default is not the renderer's own. Settable so a run can go back to the
     * archive's 48 without a rebuild.
     */
    trimFrames: Int = PRODUCT_TRIM_FRAMES,
    /**
     * Which output this handset's own copy goes to. Defaults to media, the archive's attribution.
     *
     * A capturing host has to be something else: capture reads a stream whose media volume is at
     * zero, and a media-usage output is muted along with it. See [SyncRenderer]'s own note.
     */
    playbackUsage: PlaybackUsage = PlaybackUsage.MEDIA,
    /**
     * How far ahead of the media output this handset's chosen output comes out - see
     * [com.soundmesh.probe.sync.StoredOutputLead] for the measurement.
     *
     * Applied to the renderer's clock rather than to the timeline, and that distinction is the
     * whole reason it is safe. The chunks this host stamps, the clock its sinks converge on, and
     * the standing peer correction they apply are all left exactly where they were; only this
     * handset's own view of when a chunk is due moves, so its own speaker fires later by this much
     * and nothing a sink sees changes at all.
     *
     * Zero on the media output and zero on a handset nobody has measured.
     */
    private val outputLeadNanos: Long = 0L,
    /**
     * Released when the session stops, for a source that holds something a file does not.
     *
     * [readChunk] alone cannot do it: it is called until the session ends, so there is no last
     * call to close on. A capture source holds an AudioRecord and a MediaProjection callback, and
     * a session that ended without releasing them leaves the phone recording.
     */
    private val closeSource: () -> Unit = {},
    /**
     * How often the source made this session wait for audio it had not produced yet.
     *
     * A function rather than a number, because it is read when the report is written and a count
     * taken at construction would say zero for the life of the session. Zero by default, which is
     * the truthful answer for the two sources that do not queue anything: a prefix already in
     * memory hands over a chunk the instant it is asked, and a capture has no future to read
     * ahead into, so neither can be behind.
     */
    private val lateChunks: () -> Int = { 0 },
    /**
     * How many songs a folder held that would not play.
     *
     * A function for the same reason [lateChunks] is, and zero by default for the same reason: a
     * source that is one song has nothing to pass over, and a capture has no list at all.
     */
    private val skippedSongs: () -> Int = { 0 },
    /**
     * Asks the source to start again from an instant within what it is playing.
     *
     * Does nothing by default, which is the truthful answer for the two sources that cannot: the
     * ruler's prefix is a loop with no notion of a place in a song, and a capture has no future to
     * jump into.
     */
    private val seekSource: (Long) -> Unit = {},
    /**
     * Asks the source to move [by] songs along the list it is playing.
     *
     * Does nothing by default, for the reason [seekSource] gives and one more: a source that is
     * one song has no list to move along, so a step on it is a press with nowhere to go.
     */
    private val stepSongSource: (Int) -> Unit = {},
    /**
     * Where the source has got to, before this session's own lead is taken off it.
     *
     * Null by default and null from a source that cannot say how long its audio is - a slider
     * whose right-hand end is a guess is worse than no slider.
     */
    private val sourcePlayhead: () -> Playhead? = { null },
    /**
     * Called once, from the producer's own thread, when the source has no more audio.
     *
     * A song that ends is not a session that fails, and it is not a session that keeps going
     * either: the renderer would write silence to an open AudioTrack until somebody pressed stop,
     * and the screen would go on saying PLAYING. Whoever owns the session is the only one who can
     * take it down, so it is told.
     *
     * Default does nothing, which is the right answer for the two sources that never end: the
     * ruler's prefix wraps to its own first chunk forever, and a capture has no end to reach.
     */
    private val onEnded: () -> Unit = {},
    /**
     * This handset's own name, and with it whether the room can have a shape at all.
     *
     * Null - the default - means no spatial control channel is bound and no gain is ever applied,
     * which is what every session before spatial audio did. Passed rather than read from disk here
     * because this class holds no Context and no directory; the service that owns both hands it in.
     */
    private val spatialId: String? = null,
    /**
     * What the songs this session was opened with are called, in the order they play.
     *
     * Empty - the default - is every source that is not a list of files: a capture of another app,
     * and the ruler's own fixed-name runs. A room playing one of those is told no name, which is
     * what every build before this one did for all of them.
     */
    private val songNames: List<String> = emptyList(),
    private val flags: SessionFlags = SessionFlags()
) : SyncSession {
    private val clockServer = ClockSyncServer(SyncActivity.CLOCK_PORT)
    private val chunkServer = ChunkServer(SyncActivity.CHUNK_PORT)
    private val scheduler = PlaybackScheduler(
        SyncRenderer.FRAMES_PER_CHUNK,
        SCHEDULER_CAPACITY_CHUNKS,
        earlyReleaseNanos = SyncRenderer.earlyReleaseNanos(trimFrames)
        // exactReleaseFromSequence deliberately left unset: it exists to release calibration chirps
        // without the trim tolerance, and a product session emits no chirps. Passing the harness's
        // value would arm it on ordinary audio after CHIRP_SEQUENCE_BASE chunks - about five and a
        // half hours of continuous playback, which a session can reach.
    )
    private val renderer = SyncRenderer(
        scheduler,
        DriftController(deadbandFrames),
        trimDeadbandFrames = trimFrames,
        playbackUsage = playbackUsage,
        spatialPeerId = spatialId,
        hostNanosNow = { System.nanoTime() - outputLeadNanos }
    )

    // Null when this session has no name of its own: an unbound port rather than an idle one, so a
    // build without the feature is not listening on 45126 either.
    private val spatialServer = spatialId?.let { SpatialFieldServer(SyncActivity.SPATIAL_PORT, it) }
    // How long the slowest call to broadcast took, and how many chunks the source produced.
    //
    // Instrumentation rather than a health counter, and it earned its place: it is what measured
    // the defect that produced it. A socket whose peer vanished without closing does not fail a
    // write - the send buffer fills and the write blocks - and broadcast used to write on this
    // thread, which is also the thread that feeds this handset's own output. Twenty-seven seconds
    // in one call, on hardware, with both handsets silent for all of it. What this reads now is
    // the enqueue, and it stays because a number that goes back up says the queue stopped working.
    @Volatile private var maxBroadcastNanos = 0L
    @Volatile private var generated = 0

    /** How many times the listener has jumped. Only ever compared with itself - see [generate]. */
    @Volatile private var jumps = 0

    // Read by the producer once a chunk and written by whoever pressed the button, so volatile.
    @Volatile private var paused = false

    // One chunk of nothing, kept rather than made: a pause hands out fifty a second and every
    // one of them is the same. Sized like a real chunk because the room cannot be told that a
    // chunk is short - the timeline is frames, and a short one would move it.
    private val silence = ByteArray(SyncRenderer.FRAMES_PER_CHUNK * SyncRenderer.CHANNELS * 2)

    // Which song the room has already been told about. Written by the producer thread, read by
    // the screen, so volatile rather than plain.
    @Volatile private var announcedSong = -1

    private var rendererThread: Thread? = null
    private var producerThread: Thread? = null

    /**
     * Who is in the room: this handset first, then whoever has connected and said their name.
     *
     * The order is the drawing's order and it puts the host first deliberately - a listener who
     * drags an icon has to be able to tell which one is the phone in their hand, and the only
     * thing distinguishing them is which one this list names first.
     */
    fun roomPeerIds(): List<String> =
        listOfNotNull(spatialId) + (spatialServer?.peerIds() ?: emptyList())

    /**
     * Which colour each handset in the room holds.
     *
     * Read from the control channel rather than worked out here, because the sinks are told the
     * same table over that channel - and a colour the drawing shows that the handset itself does
     * not is worse than no colour, since the two screens are read side by side.
     */
    fun roomPlaces(): Map<String, Int> = spatialServer?.places() ?: emptyMap()

    /**
     * Tells the room which song it is on, when that has changed since the last time it was told.
     *
     * Called once a chunk, which is fifty times a second, and is an int compare on all but one of
     * them. The alternative was to notice the change where the songs are read, which is a decoder
     * on its own thread inside the source - and the source is the one part of this that a
     * measurement run also uses.
     *
     * **It is the song being decoded, not the song being heard.** The queue holds about three
     * seconds, so the name changes that far ahead of the music, in the same window and for exactly
     * the same reason the slider jumps early across a song boundary. Fixing it means carrying a
     * position with every chunk through the queue; the cost of not fixing it is three seconds of a
     * wrong name, once a song.
     *
     * Named from the list this session was opened with, so an index the list does not reach says
     * nothing rather than guessing - a source that is not a folder has no names at all.
     */
    private fun sayWhatIsPlaying() {
        val at = sourcePlayhead()?.songIndex ?: return
        if (at == announcedSong) return
        announcedSong = at
        songNames.getOrNull(at)?.let { name -> spatialServer?.publishNowPlaying(name) }
    }

    /**
     * Makes [field] the rule the whole room plays under, this handset included, from a shared
     * instant a little way off.
     *
     * This used to say no instant was needed, because the rule is a function of the host instant
     * and the instants are already in the chunks. That was true of the gain law it was written
     * for and quietly stopped being true when the fold and the spectrum arrived underneath it:
     * those step when a message lands, and the sinks are told over a network while this handset
     * is told by a method call. See [com.soundmesh.core.SpatialField.effectiveAtHostNanos].
     *
     * A rule that arrives after its own instant is taken at once, so the only thing a lead that
     * is too short can cost is the behaviour this had before - which is why it is set from what
     * a person notices rather than from what the link promises.
     */
    fun publishSpatialField(field: SpatialField) {
        val stamped = field.copy(effectiveAtHostNanos = System.nanoTime() + SPATIAL_LEAD_NANOS)
        spatialServer?.publish(stamped)
        renderer.applySpatialField(stamped)
    }

    /**
     * Throws away everything in flight and starts the source again at [micros].
     *
     * Three places hold audio that is now wrong, and all three have to let go: the source's own
     * decoded queue (three seconds), this handset's scheduler, and every sink's scheduler. The
     * first two are done here; the sinks do it themselves when the sequence they are handed goes
     * backwards, which is why [generate] starts counting again.
     *
     * One chunk can still slip through - the producer may be holding one it took before the
     * source let go - and it is 20 ms of the old place, played inside the silence that follows.
     * Chasing it would mean a lock between this thread and the producer's, on the path that feeds
     * the room, to save something nobody can hear.
     */
    override fun seekTo(micros: Long) = jumped("to ${micros / 1000} ms") { seekSource(micros) }

    /**
     * The next song, or the one before it. Everything [seekTo] lets go of is let go of here too.
     *
     * A step is a jump like any other as far as the room is concerned: the sinks are told nothing
     * except that the sequence went backwards, which is what makes both of these one mechanism
     * rather than two.
     */
    override fun stepSong(by: Int) = jumped("by $by song(s)") { stepSongSource(by) }

    /**
     * Stops taking audio from the source, or starts again, without taking the session down.
     *
     * Three things happen on the way in and only one on the way out, and the asymmetry is the
     * whole design. Going in, everything in flight is wrong - a second and a half of it in every
     * handset in the room - so it is thrown away exactly the way [seekTo] throws it away, and the
     * source is asked to reopen at the place the room had actually reached. Coming out, nothing is
     * thrown away at all: the timeline never stopped, the sequence never went backwards, and every
     * handset stayed in TRACKING throughout. The room simply starts hearing music again where it
     * has been hearing silence.
     *
     * **Silence rather than nothing.** A host that stopped broadcasting would look to its sinks
     * exactly like a host that walked out: they would write blind silence against SinkSession's
     * host-gone budget and then tear the session down, and a pause longer than that budget would
     * end the evening. Anything still acquiring when the chunks stopped could not converge either
     * - see SyncRenderer's completedAcquiringNanos for what that costs. Broadcasting silence keeps
     * the cadence, the drift loop and the clock exactly as they were.
     *
     * **It costs a lead of silence to come back**, the same second and a half every jump costs and
     * for the same reason: what is in flight when play is pressed was stamped before it. Pressing
     * pause is what had to be instant, because a room that keeps playing after the button is a
     * room that looks broken; a room that takes a moment to start looks like a room starting.
     *
     * Asked for where the room is rather than where the source has decoded to. Those differ by the
     * lead, and pausing at the second one would silently skip a second and a half of the song.
     *
     * One thing to know before reading a report off a run that used this: silence is broadcast
     * chunk for chunk, so `generated` counts a pause. It has been read as a duration - a folder
     * run was checked for a cut ending by multiplying it by the chunk length - and that reading
     * is only right on a run nobody paused.
     */
    override fun setPaused(wanted: Boolean) {
        if (wanted == paused) return
        if (!wanted) {
            paused = false
            return
        }
        val room = playhead()?.positionMicros
        // Before the queues are emptied, so the producer cannot take one more real chunk between
        // the emptying and the flag - it would be a chunk of the old place inside the new silence.
        paused = true
        jumped("into a pause") { room?.let { seekSource(it) } }
    }

    /** Whether the room is hearing silence on purpose. A screen has no other way to know. */
    override fun paused(): Boolean = paused
    private fun jumped(where: String, ask: () -> Unit) {
        ask()
        val thrown = scheduler.clear()
        jumps++
        Log.i(LOG_TAG, "jumped $where; $thrown queued chunks were thrown away")
    }

    /**
     * Where the room is in the song, which is behind where the source has read to by the lead.
     *
     * Every chunk is stamped 1.5 s into its own future, so what is being heard now was read from
     * the source 1.5 s ago. A slider drawn at the source's own position would sit a second and a
     * half ahead of the music and look like it was running fast.
     */
    override fun playhead(): Playhead? {
        val source = sourcePlayhead() ?: return null
        return source.copy(
            positionMicros = heardMicros(source.positionMicros, source.durationMicros, LEAD_NANOS / 1_000L)
        )
    }

    override fun nowPlaying(): String? = songNames.getOrNull(announcedSong)

    override fun state(): SessionState = flags.state()

    // Null before start(): the renderer exists but has never run, and a report of zeroes reads
    // like a session that played nothing rather than one that has not begun.
    override fun report(): String? {
        if (flags.state() == SessionState.IDLE) return null
        return renderer.report(null).dropLast(1) + "," + hostReportFields(
            maxBroadcastNanos = maxBroadcastNanos,
            generated = generated,
            droppedToSinks = chunkServer.droppedChunks(),
            sinks = chunkServer.clientCount(),
            replacedSinks = chunkServer.replacedSinks(),
            roomPeerIds = roomPeerIds(),
            audioPeerIds = chunkServer.peerIds(),
            unnamedSinks = spatialServer?.unnamedSinks() ?: 0,
            lateChunks = lateChunks(),
            skippedSongs = skippedSongs()
        ) + "}"
    }

    override fun onAudioFocusChanged(hasFocus: Boolean) = flags.setAudioFocus(hasFocus)

    /**
     * Nothing. A host dials nobody: it binds every interface and waits, so an address that moved
     * under it costs it no connection of its own.
     *
     * The one thing a moved host does owe its sinks is a corrected mDNS record, and that belongs
     * to whoever registered it - [SessionService], which holds the Context the registration needs
     * and re-registers on the same signal that reaches here.
     */
    override fun onNetworkChanged() = Unit

    override fun start() {
        clockServer.start()
        chunkServer.start()
        // Not fatal. A room that cannot be shaped is still a room playing in step, and refusing to
        // start a session over a garnish would be the wrong trade by a wide margin.
        runCatching { spatialServer?.start() }
        // No end instant is known, so the renderer is given one it cannot reach and stop() moves it
        // back to now. endAt is @Volatile precisely so it can be moved from another thread.
        renderer.endAt(Long.MAX_VALUE)
        flags.markStarted()
        rendererThread = Thread({ renderer.run() }, "SoundMeshHostRender").also { it.start() }
        producerThread = Thread(::produce, "SoundMeshHostSource").also { it.start() }
    }

    /**
     * Generates the shared timeline, one chunk at a time.
     *
     * The chunk goes out on the wire whether or not this handset may emit it. A host that lost the
     * audio focus is silent here and only here: the sinks are on their own outputs, their own focus
     * is their own business, and stopping the broadcast would silence a room because one phone rang.
     */
    private fun produce() {
        try {
            generate()
        } catch (error: Throwable) {
            // An uncaught throw on any of a session's threads takes the whole process with it,
            // which on hardware looks like the app vanishing rather than like a session ending.
            // Marked stopped rather than merely logged: the timeline has no producer any more, and
            // a state that still said PLAYING would be a lie a person acts on.
            Log.e(LOG_TAG, "the host's source stopped", error)
            flags.markStopped()
        }
    }

    private fun generate() {
        var sequence = 0
        var jumpsSeen = jumps
        // The instant a chunk is due comes from here rather than from the clock, because once the
        // source is a capture the two are not the same thing: readChunk blocks until the recorder
        // has audio and hands it over on the recorder's own cadence, and stamping chunks with the
        // moment they happened to arrive put that cadence into the timeline for both handsets to
        // reproduce faithfully. See [ChunkTimeline] for what it cost and how it was measured.
        var timeline = ChunkTimeline(SyncRenderer.FRAMES_PER_CHUNK, SyncRenderer.SAMPLE_RATE)
        var lastDueNanos = NOTHING_PLAYED
        while (!flags.isStopped()) {
            // A pause takes nothing from the source, which is what makes it a pause rather than
            // a mute: the decoder stays parked on a full queue at the place the room stopped.
            val pcm = if (paused) silence else (readChunk() ?: return endOfSong(lastDueNanos))
            sayWhatIsPlaying()
            if (jumpsSeen != jumps) {
                jumpsSeen = jumps
                // A new grid, because the old one is a ruler laid down when the session started
                // and this thread has just spent the length of a lead waiting for a decoder. Its
                // next instant is in the past, and a chunk stamped there is one every handset
                // throws away as late.
                timeline = ChunkTimeline(SyncRenderer.FRAMES_PER_CHUNK, SyncRenderer.SAMPLE_RATE)
                // And a fresh count, which is the whole of what tells the sinks to let go. See
                // seekTo: nothing else is sent, and nothing else needs to be.
                sequence = 0
            }
            val dueNanos = timeline.accept(System.nanoTime()) + LEAD_NANOS
            lastDueNanos = dueNanos
            val chunk = AudioChunk(sequence, dueNanos, pcm)
            val startedBroadcastAt = System.nanoTime()
            chunkServer.broadcast(chunk)
            maxBroadcastNanos = maxOf(maxBroadcastNanos, System.nanoTime() - startedBroadcastAt)
            if (flags.state().mayEmit) scheduler.submit(chunk)
            // Counted rather than read off the sequence, which now starts again at every jump.
            generated++
            sequence++
            // Paced against the session's own start rather than the previous pass, so a slow pass
            // is absorbed instead of pushing every later chunk out by the same amount. Against the
            // anchored timeline rather than a fixed grid, so a recorder running fast is not held
            // below its own rate until its buffer overruns; a source that answers instantly holds
            // the anchor at zero and is paced by the grid exactly as before.
            val sleepNanos = (timeline.nextDueNanos() ?: System.nanoTime()) - System.nanoTime()
            if (sleepNanos > 0) Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
        }
    }

    /**
     * The song ran out: play what is already scheduled, then end.
     *
     * Every chunk is stamped 1.5 s into its own future and the sinks are holding their copies of
     * those same instants, so ending the renderer here and now would cut off audio that has
     * already been handed out. Cutting it off is precisely what the stop button does, and a song
     * reaching its last bar should not sound like somebody pressed stop.
     *
     * The wait is polled rather than slept through in one go so the stop button still answers
     * within a chunk, and [onEnded] is skipped if it got there first - the session is being taken
     * down already, and taking it down twice is not more down.
     */
    private fun endOfSong(lastDueNanos: Long) {
        val endNanos = endOfAudioNanos(lastDueNanos, System.nanoTime())
        renderer.endAt(endNanos)
        while (!flags.isStopped() && System.nanoTime() < endNanos) Thread.sleep(END_POLL_MILLIS)
        if (flags.isStopped()) return
        flags.markStopped()
        onEnded()
    }

    override fun stop() {
        flags.markStopped()
        renderer.endAt(System.nanoTime())
        producerThread?.join(JOIN_TIMEOUT_MILLIS)
        rendererThread?.join(JOIN_TIMEOUT_MILLIS)
        producerThread = null
        rendererThread = null
        runCatching { chunkServer.stop() }
        runCatching { clockServer.stop() }
        runCatching { spatialServer?.stop() }
        runCatching { closeSource() }
    }

    private companion object {
        /** playAtHostNanos = generation instant + this lead. The harness's own value. */
        const val LEAD_NANOS = 1_500_000_000L

        /**
         * How far ahead a new spatial rule is stamped to take effect.
         *
         * Long enough that every handset has been told before the instant arrives, and short
         * enough that a slider still feels attached to the sound. It is not [LEAD_NANOS]: chunks
         * are stamped a second and a half out but shaped at release, a few tens of milliseconds
         * before they are heard, so this only has to cover one control message and the output
         * depth under it - single digit milliseconds on a link the clock sync is converging on.
         *
         * Two hundred is that with two orders of margin, and well under where a control starts
         * feeling detached from what it does. Being too short costs nothing that was not already
         * being paid: a handset told late applies at once, which is what all of them did before.
         */
        const val SPATIAL_LEAD_NANOS = 200_000_000L

        /** ~3s of audio at 20ms/chunk. */
        const val SCHEDULER_CAPACITY_CHUNKS = 150

        const val JOIN_TIMEOUT_MILLIS = 5_000L

        /** Keeps the stop button answering within a chunk while the tail plays out. */
        const val END_POLL_MILLIS = 20L

        const val LOG_TAG = "SoundMeshSession"
    }
}
