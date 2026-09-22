package com.soundmesh.session

import android.util.Log
import com.soundmesh.core.AudioChunk
import com.soundmesh.core.ClockEstimate
import com.soundmesh.core.ClockHealth
import com.soundmesh.core.ClockOffsetEstimator
import com.soundmesh.core.DriftController
import com.soundmesh.core.PlaybackScheduler
import com.soundmesh.core.SessionState
import com.soundmesh.probe.sync.ChunkClient
import com.soundmesh.probe.sync.Playhead
import com.soundmesh.probe.sync.ClockSyncClient
import com.soundmesh.probe.sync.SpatialFieldClient
import com.soundmesh.probe.sync.StoredApproximateCalibration
import com.soundmesh.probe.sync.StoredCalibration
import com.soundmesh.probe.sync.SyncActivity
import com.soundmesh.probe.sync.SyncRenderer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Whether a sink should stop dialling a host that is not coming back.
 *
 * A session used to dial for ever, so a host that stopped left this handset holding an AudioTrack,
 * a foreground notification and a wakelock, writing silence, until somebody picked the phone up
 * and stopped it by hand. There is no message that says the host has gone - a stopped host, a host
 * whose battery died and a host carried out of range all look the same from here - so the only
 * honest reading is how long it has been since there was one.
 *
 * [everConnected] is the whole of the care this needs. A session that has never reached its host
 * is not one that lost it: somebody who pressed play here and is still walking to the other phone
 * has lost nothing, and the host opens its file before it binds anything, so the first connection
 * has always been allowed to arrive whenever it arrives.
 */
internal fun hostIsGone(
    everConnected: Boolean,
    nowNanos: Long,
    lastContactNanos: Long,
    budgetNanos: Long
): Boolean = everConnected && nowNanos - lastContactNanos > budgetNanos

/**
 * The handset that follows: it converts the host's instants into its own clock and plays what
 * arrives at the instant it was told to.
 *
 * Every chunk carries the host instant it must be heard at, so nothing here decides when to play.
 * That is what makes an interruption survivable: a session that comes back plays whatever is due
 * now, and the chunks that were due during the interruption are simply past. The design's section
 * 11.2 states the rule the other way round - never resume from where playback paused - and this is
 * the shape that makes obeying it the only thing the code can do.
 *
 * [peerId] names the host, and the alignment correction measured for that host is what makes this
 * handset's output land on the other's. A peer nobody has calibrated yet plays with no correction,
 * which is what the harness measures in order to produce one.
 */
class SinkSession(
    hostAddress: String,
    private val chunkPort: Int,
    private val peerId: String,
    private val calibrationDirectory: File,
    /** As on [HostSession]: both handsets edit their own waveform, so both arms have to move. */
    deadbandFrames: Int = DriftController.DEFAULT_DEADBAND_FRAMES,
    /** As on [HostSession], and for the same reason: an experiment moves both handsets or neither. */
    trimFrames: Int = PRODUCT_TRIM_FRAMES,
    /**
     * Where the host is now, asked again after the network moved under this handset.
     *
     * A lambda rather than a discovery object because discovery needs a Context and this class has
     * none: the service that owns both hands one in. Returning null means "no answer this time",
     * which leaves the address alone - a failed search is not evidence that the host moved.
     */
    private val resolveHost: () -> String? = { null },
    /**
     * This handset's own name, which is a different thing from [peerId]: that one names the host
     * this session follows, this one names the icon on the host's drawing that is this phone.
     *
     * Null - the default - means no spatial control channel is dialled and no gain is ever applied.
     */
    private val spatialId: String? = null,
    /**
     * Called once, from the connection thread, when this session gives up on a host that has gone.
     *
     * A lambda for the same reason [resolveHost] is one: tearing a session down means releasing
     * the audio focus, withdrawing the notification and stopping a service, none of which this
     * class can reach. What it does before calling this is mark itself stopped, so that a caller
     * which has not been taught to pass anything still gets the part that matters - a renderer
     * that stops writing silence into a room nobody is in.
     */
    private val onHostGone: () -> Unit = {},
    private val flags: SessionFlags = SessionFlags()
) : SyncSession {
    private val estimator = ClockOffsetEstimator(CLOCK_WINDOW, CLOCK_BEST)

    /**
     * Where the host is, as far as this handset knows. Moves only when [rediscover] finds it
     * somewhere else.
     */
    @Volatile private var address: String = hostAddress

    // Rebuilt rather than reconfigured when the address moves: the client resolves the address
    // once and holds a socket for the whole of runFor. The estimator is not rebuilt with it - the
    // host's clock did not change when its address did, so throwing the window away would cost the
    // session sixteen silent seconds for a change that told it nothing about the clock.
    @Volatile private var clockClient = ClockSyncClient(hostAddress, SyncActivity.CLOCK_PORT, estimator)

    // Likewise rebuilt per connection, and null between them.
    @Volatile private var chunkClient: ChunkClient? = null

    // currentEstimate() is cleared back to null by any cycle whose fit is rejected, and an
    // ill-conditioned window is not rare on a busy link. Falling back to no offset there would
    // replace host time with this handset's raw nanoTime - wrong by however far apart the two
    // phones were last booted. The last estimate that did succeed is kept and used instead.
    private val cachedEstimate = AtomicReference<ClockEstimate?>(null)

    /** When the most recent chunk arrived, in local time, for the link watchdog below. */
    private val lastArrivalNanos = AtomicLong(0L)

    /**
     * When this handset last had its host at all: a chunk arrived, or a connection was accepted.
     *
     * Deliberately not [lastArrivalNanos], which [onNetworkChanged] clears so that the watchdog
     * stops trusting a chunk that came over a network this phone has left. Clearing this one there
     * too would make every WiFi change read as "the host has been gone since the session started",
     * and [hostIsGone] would end the session on the spot - the one moment it must not.
     */
    private val lastContactNanos = AtomicLong(0L)

    /** Set by [onNetworkChanged], cleared by the connection loop once it has acted on it. */
    private val rediscoverRequested = AtomicBoolean(false)

    // What the session did to stay alive, as against what the renderer did to stay in step. The
    // renderer's own counters cannot show a reconnection: to them an outage is a stretch of chunks
    // that did not arrive, which is also what a host playing silence looks like.
    private val reconnects = AtomicInteger(0)
    private val rediscoveries = AtomicInteger(0)

    // The grading, kept so a run can be read afterwards against the bounds it was graded by - a
    // threshold justified by a distribution is only as good as the next distribution measured.
    @Volatile private var clockHealth: ClockHealth? = null
    @Volatile private var worstUncertaintyNanos = 0L

    // How long this handset was silent waiting for its first estimate, which is the whole of
    // what a listener sees when a second phone joins and says nothing. It was never recorded, so
    // the only account of it was that the room said "十几秒" - which turned out to match
    // CLOCK_INTERVAL_MILLIS times MIN_SAMPLES exactly. Recorded now so whatever shortens it - a
    // burst then, the cadence itself now - is checked rather than argued.
    @Volatile private var clockStartedNanos = 0L
    @Volatile private var firstEstimateNanos = 0L

    // The measurement first, and a room round's approximation only when there is none. Zero was
    // the whole of the fallback until 2026-09-13, when two handsets that had never been measured
    // against this host played a whole afternoon 32 and 50 ms out, with every screen in the room
    // saying it was fine. Zero is still what is left when neither file exists, and that is right:
    // an unmeasured pair is not a pair whose offset is known to be nothing.
    private val alignmentOffsetNanos =
        (StoredCalibration(calibrationDirectory, peerId).read()?.micros
            ?: StoredApproximateCalibration(calibrationDirectory, peerId).read()
            ?: 0L) * 1_000L

    private val scheduler = PlaybackScheduler(
        SyncRenderer.FRAMES_PER_CHUNK,
        SCHEDULER_CAPACITY_CHUNKS,
        earlyReleaseNanos = SyncRenderer.earlyReleaseNanos(trimFrames)
    )
    private val renderer = SyncRenderer(
        scheduler,
        DriftController(deadbandFrames),
        trimDeadbandFrames = trimFrames,
        offsetNanosNow = { latestEstimate()?.offsetNanos ?: 0L },
        spatialPeerId = spatialId,
        hostNanosNow = ::hostNanosNow
    )

    // Rebuilt with the chunk client and null between connections, because it is the same host on
    // the same address and an outage that took one took the other.
    @Volatile private var spatialClient: SpatialFieldClient? = null

    // The last name the host said. Written from the channel thread, read by the screen.
    @Volatile private var playing: String? = null

    /**
     * Which colour this handset holds in the room, or null until the host has said.
     *
     * Null is ordinary rather than a fault, and there are two of them: a host on an older build
     * never sends a table, and a host on this one sends it a moment after this handset joins.
     * Both show as a number with no colour, which is the identity with one half missing rather
     * than nothing at all.
     */
    @Volatile private var place: Int? = null

    // Rules that arrived and could not be read, carried across reconnections. Non-zero means the
    // two handsets are running different builds, which otherwise presents as this phone alone
    // ignoring the room's shape - and nobody looks at one silent feature to find a version skew.
    private val unreadableRules = AtomicInteger(0)

    /** Whether a connection has ever been made, so the first one is not counted as a return. */
    private var connected = false

    /**
     * Whether this session ended itself because its host went away.
     *
     * On the record, because from outside it is indistinguishable from a session somebody stopped:
     * both end with a quiet phone. Only this says which of the two happened.
     */
    @Volatile private var hostGone = false

    private var clockThread: Thread? = null
    private var rendererThread: Thread? = null
    private var watchdogThread: Thread? = null
    private var connectThread: Thread? = null

    override fun state(): SessionState = flags.state()

    // Null before start(): the renderer exists but has never run, and a report of zeroes reads
    // like a session that played nothing rather than one that has not begun.
    override fun report(): String? {
        if (flags.state() == SessionState.IDLE) return null
        // Spliced into the renderer's object rather than nested beside it: everything that reads
        // one of these reads a flat set of names, and one more name costs nothing while one more
        // level costs every reader.
        return renderer.report(null).dropLast(1) + sessionCounters() + "}"
    }

    /** What the session survived, which no counter of the renderer's can show. */
    private fun sessionCounters(): String =
        ",\"unreadableSpatialRules\":${unreadableRules.get() + (spatialClient?.unreadableRules() ?: 0)}" +
        ",\"reconnects\":${reconnects.get()}" +
            ",\"rediscoveries\":${rediscoveries.get()}" +
            ",\"clockHealth\":\"${clockHealth ?: "NONE"}\"" +
            ",\"hostGone\":$hostGone" +
            ",\"worstUncertaintyNanos\":$worstUncertaintyNanos" +
            ",\"silentUntilFirstEstimateNanos\":${silentUntilFirstEstimateNanos()}" +
            // The shape that produced these numbers, read off the estimator rather than restated
            // from the constants: a run has to be readable against what it ran, not against what
            // the source says by the time somebody opens the run.
            ",\"clockIntervalMillis\":$CLOCK_INTERVAL_MILLIS" +
            ",\"estimatorWindow\":${estimator.windowSize}" +
            ",\"estimatorBest\":${estimator.bestCount}"

    /** Minus one while still silent, so "has not answered yet" cannot be read as "answered at once". */
    private fun silentUntilFirstEstimateNanos(): Long =
        if (firstEstimateNanos == 0L || clockStartedNanos == 0L) -1L
        else firstEstimateNanos - clockStartedNanos

    override fun onAudioFocusChanged(hasFocus: Boolean) = flags.setAudioFocus(hasFocus)

    /**
     * Drops what is certainly stale and asks the connection loop to look for the host again.
     *
     * The link is marked down here rather than left to the watchdog's idle threshold, which is
     * what section 11.2 means by not waiting for the timeout. The arrival clock is reset with it,
     * or the watchdog's next pass - two hundred milliseconds later, still inside the threshold -
     * would put the link straight back up on the strength of a chunk that arrived over a network
     * this handset is no longer on.
     */
    override fun onNetworkChanged() {
        if (flags.isStopped()) return
        rediscoverRequested.set(true)
        lastArrivalNanos.set(0L)
        flags.setLinkUp(false)
        // Closing the socket is what wakes the reader thread; the loop itself is woken by the flag.
        runCatching { chunkClient?.stop() }
    }

    private fun latestEstimate(): ClockEstimate? {
        val fresh = clockClient.currentEstimate()
        if (fresh != null) cachedEstimate.set(fresh)
        return fresh ?: cachedEstimate.get()
    }

    /**
     * This handset's clock, expressed on the host's timeline and corrected by the stored alignment.
     *
     * Throws rather than falling back to an uncorrected reading: with no estimate having ever
     * succeeded there is no host time on this device at all, and inventing one plays a whole
     * session at the wrong instant while every counter reads healthy.
     */
    private fun hostNanosNow(): Long {
        val estimate = latestEstimate() ?: throw ClockOffsetUnavailable()
        return System.nanoTime() + estimate.offsetNanos - alignmentOffsetNanos
    }

    override fun start() {
        flags.markStarted()
        // Started now rather than left at zero: the budget is measured from the last time there
        // was a host, and before the first connection there has to be some instant to measure
        // from. It is never read until a connection has been made - see [hostIsGone].
        lastContactNanos.set(System.nanoTime())
        flags.setClockConverged(false)
        flags.setLinkUp(false)
        clockThread = guarded("SoundMeshSinkClock", ::exchangeClock)
        watchdogThread = guarded("SoundMeshSinkWatch", ::watch)
        // The renderer converts through hostNanosNow, which throws until the estimator has a fit,
        // so it is only started once one exists.
        rendererThread = guarded("SoundMeshSinkRender", ::renderOnceConverged)
        connectThread = guarded("SoundMeshSinkConnect", ::connect)
    }

    /**
     * A started thread whose failure ends the session instead of the process.
     *
     * An uncaught throw on any thread takes the whole app down, which from the room looks like the
     * app vanishing rather than like a session ending. One refused connection did exactly that on
     * hardware. Marked stopped rather than merely logged, because a session missing one of these
     * threads is over whether or not anything says so.
     */
    private fun guarded(name: String, body: () -> Unit): Thread = Thread({
        try {
            body()
        } catch (error: Throwable) {
            // stop() interrupts the clock thread, so an already-stopped session unwinding through
            // here is the ordinary ending rather than a fault worth a line in the log.
            if (flags.isStopped()) return@Thread
            Log.e(LOG_TAG, "$name stopped", error)
            flags.markStopped()
        }
    }, name).also { it.start() }

    /**
     * Exchanges with the host for as long as the session lasts, across however many addresses it
     * has in that time.
     *
     * The loop exists for the address, not for failure: [ClockSyncClient.runFor] survives a host
     * that stops answering on its own, and the only thing it cannot survive is being pointed
     * somewhere else. An interrupt that did not come from [stop] is therefore read as "the address
     * moved", which is the only other thing that interrupts this thread.
     */
    private fun exchangeClock() {
        clockStartedNanos = System.nanoTime()
        while (!flags.isStopped()) {
            val client = ClockSyncClient(address, SyncActivity.CLOCK_PORT, estimator)
            clockClient = client
            runCatching {
                client.runFor(FOREVER_SECONDS, CLOCK_INTERVAL_MILLIS)
            }
            if (flags.isStopped()) return
            // The interrupt is cleared here rather than left set, or the rebuilt client's first
            // sleep would throw immediately and spin this loop.
            Thread.interrupted()
            Thread.sleep(REBUILD_PAUSE_MILLIS)
        }
    }

    /**
     * Holds a connection to the host for as long as the session lasts, dialling again whenever one
     * ends.
     *
     * The first connection has to be allowed to arrive early: a host opens its file before it binds
     * anything, and decoding takes as long as it takes, so whoever started the two handsets cannot
     * know when the host began listening. It refused once on hardware, and a refusal on the
     * starting thread took the whole process down with it.
     *
     * Every connection after the first is section 11.2's dropped link. What keeps that inaudible is
     * not this loop but the lead time: chunks are handed over about a second and a half before they
     * are due, so an outage shorter than the buffer already in the scheduler is played straight
     * through and only the counters know it happened. This loop's job is to be finished dialling
     * before that buffer runs out.
     */
    private fun connect() {
        var failures = 0
        while (!flags.isStopped()) {
            if (rediscoverRequested.compareAndSet(true, false)) rediscover()
            if (dial()) {
                failures = 0
                awaitLinkLoss()
            } else if (++failures >= DIAL_FAILURES_BEFORE_REDISCOVERY) {
                // The address came off a code scanned at some point in the past, and a lease that
                // expired since is indistinguishable from a host that has not started yet - until
                // enough attempts have gone by that a host still decoding is no longer the
                // explanation. Then it is worth a look rather than another decade of dialling.
                failures = 0
                rediscoverRequested.set(true)
            }
            releaseClients()
            if (hostIsGone(connected, System.nanoTime(), lastContactNanos.get(), HOST_GONE_NANOS)) {
                Log.i(LOG_TAG, "the host has been gone too long; this session is ending itself")
                hostGone = true
                // Marked before the callback, and not left to it: this is what stops the renderer
                // writing silence, and it has to happen even for a caller that passed nothing.
                flags.markStopped()
                onHostGone()
                break
            }
        }
        releaseClients()
    }

    /**
     * Lets go of one connection's clients, keeping what the next one cannot rebuild.
     *
     * The unreadable-rule count is carried over rather than dropped with the client that counted
     * it: a version skew produces one such rule per connection, and a session that reconnects
     * eleven times would otherwise report the last one and read as almost healthy.
     */
    private fun releaseClients() {
        runCatching { chunkClient?.stop() }
        chunkClient = null
        spatialClient?.let {
            unreadableRules.addAndGet(it.unreadableRules())
            runCatching { it.stop() }
        }
        spatialClient = null
    }

    /** One attempt. The caller's loop is the retry, so a host that is not up yet costs one sleep. */
    private fun dial(): Boolean {
        val client = ChunkClient(address, chunkPort, peerId = spatialId, onChunk = ::receive)
        if (runCatching { client.start() }.isFailure) {
            Thread.sleep(CONNECT_RETRY_MILLIS)
            return false
        }
        chunkClient = client
        // A host that accepted a connection is a host that is here, whether or not a chunk has
        // come through it yet. Its own server is closed while no session is running, so an accept
        // cannot come from a handset that has stopped.
        lastContactNanos.set(System.nanoTime())
        // Dialled after the audio and allowed to fail on its own. A host running a build with no
        // control channel refuses this connection, and a session that gave up there would trade a
        // room playing in step for a room not playing at all.
        spatialClient = spatialId?.let { name ->
            SpatialFieldClient(
                address,
                SyncActivity.SPATIAL_PORT,
                name,
                onNowPlaying = { playing = it },
                onBadges = { place = it[name] },
                onField = renderer::applySpatialField
            )
                .takeIf { runCatching { it.start() }.isSuccess }
        }
        // Counted after the first, so the number reads as "times this session came back" rather
        // than "times it connected", which is one larger and means something else.
        if (connected) reconnects.incrementAndGet()
        connected = true
        return true
    }

    /**
     * Returns once the connection has stopped carrying chunks, or once the network moved.
     *
     * Silence is measured from the dial rather than from the last arrival so that a fresh
     * connection is not judged by the previous one's clock, which stopped at whatever instant that
     * connection died.
     */
    private fun awaitLinkLoss() {
        val dialledAtNanos = System.nanoTime()
        while (!flags.isStopped() && !rediscoverRequested.get()) {
            val quietSinceNanos = maxOf(dialledAtNanos, lastArrivalNanos.get())
            if (System.nanoTime() - quietSinceNanos > IDLE_THRESHOLD_NANOS) return
            Thread.sleep(WATCH_INTERVAL_MILLIS)
        }
    }

    /**
     * Looks for the host on the network this handset is on now.
     *
     * An answer from some other host is not an answer: the search is filtered by [peerId] on the
     * far side, so the two ways this returns nothing - nobody answered, and somebody else did -
     * both leave the known address in place. The alternative is a session that quietly follows a
     * stranger after a WiFi switch, which nothing in the room would explain.
     */
    private fun rediscover() {
        val found = runCatching { resolveHost() }.getOrNull() ?: return
        if (found == address) return
        Log.i(LOG_TAG, "the host moved to another address")
        address = found
        rediscoveries.incrementAndGet()
        // The clock client resolved the old address at construction; only a new one can follow.
        clockThread?.interrupt()
    }

    /**
     * A sequence that went backwards is the host having jumped, and what is queued here is wrong.
     *
     * Nothing is sent to say so, and nothing needs to be: the sequence is the stream's own
     * position counter and a stream position cannot go backwards for any other reason. What it
     * buys over a field of its own is that an older build on the other end still plays - it hears
     * a second and a half of where the song used to be, which is the behaviour this replaced,
     * rather than a header it cannot parse.
     *
     * The rule lives here rather than in [PlaybackScheduler] because that class is also the
     * harness's, where a chirp is submitted far above the streaming sequences and streaming then
     * resumes below it again. There it would fire on every calibration run in the archive.
     */
    private var lastSequence = Int.MIN_VALUE

    private fun receive(chunk: AudioChunk) {
        val arrivedAt = System.nanoTime()
        lastArrivalNanos.set(arrivedAt)
        lastContactNanos.set(arrivedAt)
        flags.setLinkUp(true)
        if (chunk.sequence < lastSequence) {
            // Cleared whether or not this handset is emitting: a sink that lost the audio focus is
            // still holding a queue, and it would play it out when the focus came back.
            val thrown = scheduler.clear()
            Log.i(LOG_TAG, "the host jumped; $thrown queued chunks were thrown away")
        }
        lastSequence = chunk.sequence
        if (flags.state().mayEmit) scheduler.submit(chunk)
    }

    /** Nothing. A sink holds no source: the handset that does is the one that can jump. */
    override fun seekTo(micros: Long) = Unit

    // Nor this one, and for the same reason: a sink plays the instants it is handed. A step
    // reaches it as a sequence that went backwards, which it already knows what to do with.
    override fun stepSong(by: Int) = Unit

    /**
     * Nothing, and nothing is needed. A host that pauses empties this sink's queue the way a
     * jump does and then keeps broadcasting silence, so a paused room reaches here as audio
     * that happens to be quiet - which is the one thing a sink already knows how to play.
     */
    override fun setPaused(wanted: Boolean) = Unit

    /** Null. Positions are the source's, and a sink is handed instants instead. */
    override fun playhead(): Playhead? = null

    /**
     * Held rather than derived. A sink has no list of songs and no position in one; this is the
     * last thing the host said, and it survives a reconnection because the host re-sends it to
     * every connection as it registers.
     */
    override fun nowPlaying(): String? = playing

    override fun badgePlace(): Int? = place

    override fun loudness(): Float = renderer.loudness

    /**
     * Keeps [SessionFlags] told what is true, at a cadence fast enough for the state a person sees.
     *
     * Convergence and the link are polled rather than pushed because neither has an event to push:
     * the estimator publishes a value once per exchange cycle, and a TCP stream that stopped
     * carrying chunks looks exactly like one carrying silence until enough time has passed. The
     * harness reads the same threshold the same way.
     */
    private fun watch() {
        while (!flags.isStopped()) {
            grade(latestEstimate())
            val last = lastArrivalNanos.get()
            if (last != 0L) flags.setLinkUp(System.nanoTime() - last < IDLE_THRESHOLD_NANOS)
            Thread.sleep(WATCH_INTERVAL_MILLIS)
        }
    }

    /**
     * Turns one estimate into the two conditions section 11.2 asks for.
     *
     * An estimate that has never succeeded and one whose uncertainty is past the point of being
     * usable are reported the same way, as an unconverged clock, and both silence the session. They
     * are the same answer to the only question that matters here: is there a timeline this handset
     * can be trusted to emit onto. Everything between the two bounds keeps playing and says so.
     */
    private fun grade(estimate: ClockEstimate?) {
        if (estimate != null && firstEstimateNanos == 0L) firstEstimateNanos = System.nanoTime()
        val health = estimate?.let { ClockHealth.of(it.uncertaintyNanos) }
        clockHealth = health
        if (estimate != null) worstUncertaintyNanos = maxOf(worstUncertaintyNanos, estimate.uncertaintyNanos)
        flags.setClockConverged(health != null && health != ClockHealth.UNUSABLE)
        flags.setClockUncertain(health == ClockHealth.DEGRADED)
    }

    private fun renderOnceConverged() {
        while (!flags.isStopped() && latestEstimate() == null) {
            Thread.sleep(WATCH_INTERVAL_MILLIS)
        }
        if (flags.isStopped()) return
        renderer.endAt(Long.MAX_VALUE)
        atAudioPriority()
        renderer.run()
    }

    override fun stop() {
        flags.markStopped()
        // Moved to an instant the renderer's own clock has already passed. Reading host time here
        // is safe: the renderer only runs once an estimate exists, and stopping before that leaves
        // the renderer's own wait loop to end it instead.
        runCatching { renderer.endAt(hostNanosNow()) }
        runCatching { chunkClient?.stop() }
        runCatching { spatialClient?.stop() }
        // runFor sleeps between exchanges and has no stop of its own; the interrupt lands in that
        // sleep and unwinds through the socket's own close.
        clockThread?.interrupt()
        clockThread?.join(JOIN_TIMEOUT_MILLIS)
        connectThread?.join(JOIN_TIMEOUT_MILLIS)
        watchdogThread?.join(JOIN_TIMEOUT_MILLIS)
        rendererThread?.join(JOIN_TIMEOUT_MILLIS)
        clockThread = null
        connectThread = null
        watchdogThread = null
        rendererThread = null
    }

    private companion object {
        /** ~3s of audio at 20ms/chunk. */
        const val SCHEDULER_CAPACITY_CHUNKS = 150

        /**
         * Four exchanges a second, against a window of [CLOCK_WINDOW], so the fit still spans the
         * same 128 seconds two seconds apart spanned with sixty-four.
         *
         * Not a faster version of the same thing. What sets the estimator's walk is how long its
         * kept set spans, not how many exchanges it holds, so the span is what was held fixed and
         * the density is what was bought: over six archived rounds a side, the offset's 5-95 band
         * falls from 1.051 ms to 0.302 - four standard errors - and the worst round past a full
         * window from 1.596 ms to 0.385, which is the size of the pairing spread a room measures.
         * The lag does not move, and the cost measured zero: both arms ran with no underrun, no
         * drop and no reacquisition. See cross-platform.md, experiments 27 and 28.
         *
         * This is also what retired the burst the session used to open with. That burst was
         * thirty-two exchanges 250 ms apart in front of a two-second cadence, there because the
         * estimator answers nothing below MIN_SAMPLES and this session plays nothing until it
         * answers - at two seconds apart that is fourteen seconds of a joining handset standing
         * silent, which is what the room always reported. At this cadence the burst's own rate IS
         * the settled rate. The first exchanges go out at the times they went out before, and they
         * are selected from by the same rule: keepFor is a fraction of the window while the window
         * fills, and eight of sixty-four and sixty-four of five hundred and twelve are the same
         * eighth, so the two shapes publish the same numbers until sixty-four are held. Not
         * approximately - ClockOffsetEstimatorTest pins it, and six archived rounds disagreed by
         * 0.0e+0 ms. How this session starts did not change, which is why it was not re-measured.
         */
        const val CLOCK_INTERVAL_MILLIS = 250L

        /**
         * The window and kept count this session runs, rather than the core defaults.
         *
         * Named here instead of moved into ClockOffsetEstimator because those defaults have two
         * other readers - the pair calibration round, which waits out one whole window on purpose
         * and was sized against sixteen seconds, and the desktop peer - and neither was measured
         * under this shape. Widening a default is how a screen designed around sixteen seconds
         * quietly becomes one that waits out two minutes.
         */
        const val CLOCK_WINDOW = 512
        const val CLOCK_BEST = 64

        /** No new chunk for this long means the host has stopped sending. The harness's value. */
        const val IDLE_THRESHOLD_NANOS = 800_000_000L

        const val WATCH_INTERVAL_MILLIS = 200L

        /** Between attempts on a host that is not listening yet. Its decode is the thing waited on. */
        const val CONNECT_RETRY_MILLIS = 500L

        /**
         * Ten seconds of refusals before the address itself is doubted.
         *
         * Long enough that a host still opening its file is never the reason - it answers within
         * two or three attempts - and short enough that nobody waits out a stale lease by hand.
         */
        const val DIAL_FAILURES_BEFORE_REDISCOVERY = 20

        /**
         * How long a sink keeps looking for a host that has gone quiet before it ends itself.
         *
         * Generous on purpose. Everything shorter than this is what the reconnection loop is for,
         * and the cost of waiting is a phone playing silence, while the cost of being too eager is
         * a room that stops because somebody walked between two rooms. A minute is several
         * rediscovery cycles - twenty dials of half a second each, then a look on the network -
         * so a host that moved has been searched for properly before this gives up on it.
         */
        const val HOST_GONE_NANOS = 60_000_000_000L

        /** Between a clock client that ended and its replacement, so a stuck one cannot spin. */
        const val REBUILD_PAUSE_MILLIS = 200L

        /** A duration no session reaches, in place of a deadline the product does not have. */
        const val FOREVER_SECONDS = 365 * 24 * 3600

        const val JOIN_TIMEOUT_MILLIS = 5_000L

        const val LOG_TAG = "SoundMeshSession"
    }
}

/**
 * No clock offset estimate has ever succeeded, so this device has no host time to convert to.
 * Thrown rather than answered with zero, which reads as an ordinary session while every instant on
 * it is wrong by the two handsets' boot times apart.
 */
class ClockOffsetUnavailable : IllegalStateException("no clock offset estimate has ever succeeded")
