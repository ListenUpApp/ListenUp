package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** Waits before each fresh prepare of a book a network drop took away; Android's service backs off the same way. */
private val DROP_RECOVERY_BACKOFF = listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds)

/**
 * [PlayerEngine] for iOS (spec §4.1, S1). Kotlin prepares through the shared choke point and issues
 * [EngineCommand]s on [commands]; Swift's `PlayerCoordinator` executes them on AVAudioEngine and pushes
 * what the engine does back through the `on…` / `report…` methods. AVAudioSession policy, interruptions,
 * route changes, the gain tap and position persistence stay in Swift, where they are tested.
 *
 * Bound only by the iOS Koin module. It lives in commonMain because nothing in it is Apple-specific, which
 * lets its spec run on the JVM lane as well as the Apple lane.
 *
 * The constructor is `internal`: Koin builds it, and Swift Export cannot bridge its suspend `prepare`
 * function type (an `AppResult<PreparedPlayback>` return), so exposing it would break the iOS framework.
 */
class NativePlayerEngine internal constructor(
    private val prepare: suspend (BookId, PlaybackStart) -> AppResult<PreparedPlayback>,
    private val scope: CoroutineScope,
) : PlayerEngine {
    private val commandChannel = Channel<EngineCommand>(Channel.UNLIMITED)

    /** The ordered command stream. Exactly one collector: Swift's `PlayerCoordinator`. */
    val commands: Flow<EngineCommand> = commandChannel.receiveAsFlow()

    override val loadedBookId: StateFlow<BookId?>
        field = MutableStateFlow<BookId?>(null)
    override val phase: StateFlow<EnginePhase>
        field = MutableStateFlow(EnginePhase.IDLE)
    override val positionMs: StateFlow<Long>
        field = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long>
        field = MutableStateFlow(0L)
    override val speed: StateFlow<Float>
        field = MutableStateFlow(1.0f)
    override val boostDb: StateFlow<Float>
        field = MutableStateFlow(0f)
    override val chapters: StateFlow<List<Chapter>>
        field = MutableStateFlow<List<Chapter>>(emptyList())
    override val timeline: StateFlow<PlaybackTimeline?>
        field = MutableStateFlow<PlaybackTimeline?>(null)
    override val outputDeviceName: StateFlow<String?>
        field = MutableStateFlow<String?>(null)
    override val error: StateFlow<AppError?>
        field = MutableStateFlow<AppError?>(null)

    override val currentChapter: StateFlow<PlaybackManager.ChapterInfo?> =
        combine(
            positionMs,
            chapters,
            durationMs,
        ) { position, marks, duration -> chapterInfoAt(position, marks, duration) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The current load's tag, carried by its [EngineCommand.Load] and by every fact Swift reports about it. Moved on by
     * each load and by [stop], so a fact Swift sends about a book it was told to drop is recognised and ignored.
     */
    private var generation = 0

    private fun isCurrent(factGeneration: Int): Boolean = factGeneration == generation

    /**
     * Where the loaded book starts when it is played again, or null while Swift holds its audio. Swift drops a book
     * whose engine failed; playing it again prepares it afresh here (fresh URLs, paths and tokens), never replays the
     * preparation that just failed (never stranded).
     */
    private var restartFrom: PlaybackStart? = null

    /** The fresh prepare for a dropped book, while it runs. */
    private var reprepare: Job? = null

    /** Audio has moved for this load and the listener has not paused it since: a drop now interrupts a listen. */
    private var isListening = false

    /** The wait before the next fresh prepare of a book a network drop took away, while one is scheduled. */
    private var dropRecovery: Job? = null

    /** Fresh prepares spent on the current drop; back to zero once audio moves again. */
    private var dropRecoveryAttempts = 0

    /** The load a drop's recovery handed Swift, while it is the current one: its failures are the same drop. */
    private var dropRecoveryGeneration: Int? = null

    private fun send(command: EngineCommand) {
        commandChannel.trySend(command)
    }

    /** Silence the outgoing book at once when another one is asked for (the old coordinator's switch rule). */
    override fun beginLoad(bookId: BookId) {
        cancelReprepare()
        cancelDropRecovery()
        val current = loadedBookId.value
        if (current != null && current != bookId) send(EngineCommand.Pause)
    }

    override suspend fun load(
        bookId: BookId,
        start: PlaybackStart,
    ): AppResult<Unit> {
        // The session's load wins over a dropped book's fresh prepare.
        cancelReprepare()
        cancelDropRecovery()
        restartFrom = null
        return when (val result = prepare(bookId, start)) {
            is AppResult.Failure -> {
                result
            }

            is AppResult.Success -> {
                handOver(bookId, result.data)
                AppResult.Success(Unit)
            }
        }
    }

    /** Adopt [prepared] as the loaded book and tell Swift to load it, under a new generation. */
    private fun handOver(
        bookId: BookId,
        prepared: PreparedPlayback,
    ) {
        restartFrom = null
        isListening = false
        dropRecoveryGeneration = null
        chapters.value = prepared.chapters
        timeline.value = prepared.timeline
        durationMs.value = prepared.timeline.totalDurationMs
        positionMs.value = prepared.resumePositionMs
        speed.value = prepared.resumeSpeed
        boostDb.value = prepared.resumeBoostDb
        error.value = null
        phase.value = EnginePhase.BUFFERING
        loadedBookId.value = bookId
        generation++
        send(EngineCommand.Load(bookId = bookId.value, prepared = prepared, generation = generation))
    }

    /**
     * Prepare the dropped book again from [restartFrom] and hand it to Swift: a play after an engine failure, or
     * a step of a drop's recovery ([isDropRecovery]), which backs off and tries again when the network is still out.
     */
    private fun prepareAgain(
        bookId: BookId,
        start: PlaybackStart,
        isDropRecovery: Boolean = false,
    ) {
        if (reprepare?.isActive == true) return
        error.value = null
        phase.value = EnginePhase.BUFFERING
        // Lazy, so the job is recorded before it runs: a prepare that returns at once still finds itself current.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val result =
                    try {
                        prepare(bookId, start)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "Preparing ${bookId.value} again threw" }
                        AppResult.Failure(PlaybackError.CouldNotStart(debugInfo = e.toString()))
                    }
                if (reprepare !== coroutineContext[Job]) return@launch
                reprepare = null
                when (result) {
                    is AppResult.Success -> {
                        handOver(bookId, result.data)
                        if (isDropRecovery) dropRecoveryGeneration = generation
                    }

                    is AppResult.Failure -> {
                        error.value = result.error
                        phase.value = EnginePhase.PAUSED
                        if (isDropRecovery && result.error.isNetworkDrop()) scheduleDropRecovery()
                    }
                }
            }
        reprepare = job
        job.start()
    }

    private fun cancelReprepare() {
        reprepare?.cancel()
        reprepare = null
    }

    /**
     * Prepare the dropped book afresh after the next backoff, as Media3 re-prepares and hls.js retries: the session
     * shows nothing for [PLAYBACK_DROP_GRACE], and its halt (a pause) ends this. A spent backoff stops trying.
     */
    private fun scheduleDropRecovery() {
        val backoff = DROP_RECOVERY_BACKOFF.getOrNull(dropRecoveryAttempts) ?: return
        dropRecoveryAttempts++
        dropRecovery?.cancel()
        dropRecovery =
            scope.launch {
                delay(backoff)
                dropRecovery = null
                val bookId = loadedBookId.value ?: return@launch
                val start = restartFrom ?: return@launch
                prepareAgain(bookId, start, isDropRecovery = true)
            }
    }

    private fun cancelDropRecovery() {
        dropRecovery?.cancel()
        dropRecovery = null
        dropRecoveryAttempts = 0
        dropRecoveryGeneration = null
    }

    // Nothing is armed before the Load command, so there is nothing to take back.
    override fun abandonLoad() = Unit

    override fun play() {
        cancelDropRecovery()
        val start = restartFrom
        val bookId = loadedBookId.value
        if (start != null && bookId != null) {
            prepareAgain(bookId, start)
        } else {
            send(EngineCommand.Play)
        }
    }

    override fun pause() {
        isListening = false
        cancelDropRecovery()
        if (reprepare?.isActive == true) {
            cancelReprepare()
            phase.value = EnginePhase.PAUSED
            return
        }
        send(EngineCommand.Pause)
    }

    override fun seekTo(positionMs: Long) {
        this.positionMs.value = positionMs
        if (restartFrom == null) {
            send(EngineCommand.SeekTo(positionMs))
            return
        }
        // Swift holds no audio to seek: the fresh prepare starts here instead.
        restartFrom = PlaybackStart.At(positionMs)
        if (reprepare?.isActive == true) {
            cancelReprepare()
            play()
        }
    }

    override fun setSpeed(speed: Float) {
        this.speed.value = speed
        send(EngineCommand.SetRate(speed, isReset = false))
    }

    override fun resetSpeed(defaultSpeed: Float) {
        speed.value = defaultSpeed
        send(EngineCommand.SetRate(defaultSpeed, isReset = true))
    }

    override fun setBoost(boostDb: Float) {
        this.boostDb.value = boostDb
        send(EngineCommand.SetBoostDb(boostDb, isReset = false))
    }

    override fun resetBoost(defaultBoostDb: Float) {
        boostDb.value = defaultBoostDb
        send(EngineCommand.SetBoostDb(defaultBoostDb, isReset = true))
    }

    override fun setVolume(volume: Float) {
        send(EngineCommand.SetVolume(volume))
    }

    override fun clearError() {
        error.value = null
    }

    override fun stop() {
        cancelReprepare()
        cancelDropRecovery()
        send(EngineCommand.Stop)
        generation++
        unload()
    }

    // ── Facts Swift pushes in (plain, non-suspend: callable from the main actor without a bridge await) ──
    // Each carries the generation of the Load Swift was executing when it happened; one from any other load is
    // about a book this engine no longer holds, and is dropped.

    /**
     * The engine's phase changed (timeControlStatus, an interruption, a remote command, the end). Audio playing is
     * proof the engine recovered, so it clears a standing failure: a recovered engine never stays Failed.
     */
    fun onPhase(
        generation: Int,
        phase: EnginePhase,
    ) {
        if (!isCurrent(generation)) return
        when (phase) {
            EnginePhase.PLAYING -> {
                error.value = null
                isListening = true
                // Through: the next drop's backoff starts from the beginning.
                dropRecoveryAttempts = 0
                dropRecoveryGeneration = null
            }

            EnginePhase.ENDED -> {
                isListening = false
            }

            // Not read here: Swift reports PAUSED right after it drops a failed book, mid-listen, and IDLE and
            // BUFFERING are where a drop passes through.
            EnginePhase.PAUSED, EnginePhase.IDLE, EnginePhase.BUFFERING -> {}
        }
        this.phase.value = phase
    }

    /** The engine's playhead, book-relative. */
    fun onPosition(
        generation: Int,
        positionMs: Long,
    ) {
        if (!isCurrent(generation)) return
        this.positionMs.value = positionMs
    }

    /** The engine's rate changed outside a command (the lock screen's rate control). */
    fun onRate(
        generation: Int,
        speed: Float,
    ) {
        if (!isCurrent(generation)) return
        this.speed.value = speed
    }

    /** The boost changed outside a command. */
    fun onBoost(
        generation: Int,
        boostDb: Float,
    ) {
        if (!isCurrent(generation)) return
        this.boostDb.value = boostDb
    }

    /** The AirPlay route's name, or null when audio plays on the device. Not about a book, so never stale. */
    fun onRoute(deviceName: String?) {
        outputDeviceName.value = deviceName
    }

    /** A network-domain item failure. Swift has dropped the book's audio. */
    fun reportConnectionLost(
        generation: Int,
        debugInfo: String?,
    ) {
        onDropped(generation, PlaybackError.ConnectionLost(debugInfo = debugInfo))
    }

    /** A format/decoder failure. Swift has dropped the book's audio. */
    fun reportUnsupportedFormat(
        generation: Int,
        debugInfo: String?,
    ) {
        onDropped(generation, PlaybackError.UnsupportedFormat(debugInfo = debugInfo))
    }

    /**
     * Any other load or playback failure (no audio, a session that would not activate, a load timeout). Swift has
     * dropped the book's audio.
     */
    fun reportCouldNotStart(
        generation: Int,
        debugInfo: String?,
    ) {
        onDropped(generation, PlaybackError.CouldNotStart(debugInfo = debugInfo))
    }

    /**
     * Swift's engine failed and let go of the book: show [failure], and prepare afresh on the next play. A network
     * drop that interrupts a listen also recovers by itself ([scheduleDropRecovery]); any other failure ends that.
     * A reload the recovery handed Swift that could not start is the same drop: Swift's load reports "could not
     * start" whatever stopped it.
     */
    private fun onDropped(
        generation: Int,
        failure: AppError,
    ) {
        if (!isCurrent(generation)) return
        val isRecoveryReload = generation == dropRecoveryGeneration
        val dropped =
            if (isRecoveryReload && failure is PlaybackError.CouldNotStart) {
                PlaybackError.ConnectionLost(debugInfo = failure.debugInfo)
            } else {
                failure
            }
        error.value = dropped
        if (loadedBookId.value != null) restartFrom = PlaybackStart.Resume
        if (dropped is PlaybackError.ConnectionLost && (isListening || isRecoveryReload)) {
            scheduleDropRecovery()
        } else {
            cancelDropRecovery()
        }
    }

    /**
     * Swift was asked to play (the lock screen, CarPlay, AirPods) while it holds no audio for the book, because a
     * failure dropped it: play it here, which prepares it afresh.
     */
    fun onPlayRequested(generation: Int) {
        if (!isCurrent(generation)) return
        play()
    }

    /** Swift unloaded the engine on its own (sign-out's awaited stop). */
    fun onUnloaded(generation: Int) {
        if (!isCurrent(generation)) return
        unload()
    }

    private fun unload() {
        cancelReprepare()
        cancelDropRecovery()
        isListening = false
        restartFrom = null
        loadedBookId.value = null
        phase.value = EnginePhase.IDLE
        positionMs.value = 0L
        durationMs.value = 0L
        chapters.value = emptyList()
        timeline.value = null
        error.value = null
    }
}
