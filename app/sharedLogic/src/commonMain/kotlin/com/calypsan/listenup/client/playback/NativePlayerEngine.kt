package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

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
    scope: CoroutineScope,
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

    private fun send(command: EngineCommand) {
        commandChannel.trySend(command)
    }

    /** Silence the outgoing book at once when another one is asked for (the old coordinator's switch rule). */
    override fun beginLoad(bookId: BookId) {
        val current = loadedBookId.value
        if (current != null && current != bookId) send(EngineCommand.Pause)
    }

    override suspend fun load(
        bookId: BookId,
        start: PlaybackStart,
    ): AppResult<Unit> =
        when (val result = prepare(bookId, start)) {
            is AppResult.Failure -> {
                result
            }

            is AppResult.Success -> {
                val prepared = result.data
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
                AppResult.Success(Unit)
            }
        }

    // Nothing is armed before the Load command, so there is nothing to take back.
    override fun abandonLoad() = Unit

    override fun play() {
        send(EngineCommand.Play)
    }

    override fun pause() {
        send(EngineCommand.Pause)
    }

    override fun seekTo(positionMs: Long) {
        this.positionMs.value = positionMs
        send(EngineCommand.SeekTo(positionMs))
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
        if (phase == EnginePhase.PLAYING) error.value = null
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

    /** A network-domain item failure. */
    fun reportConnectionLost(
        generation: Int,
        debugInfo: String?,
    ) {
        if (!isCurrent(generation)) return
        error.value = PlaybackError.ConnectionLost(debugInfo = debugInfo)
    }

    /** A format/decoder failure. */
    fun reportUnsupportedFormat(
        generation: Int,
        debugInfo: String?,
    ) {
        if (!isCurrent(generation)) return
        error.value = PlaybackError.UnsupportedFormat(debugInfo = debugInfo)
    }

    /** Any other load or playback failure (no audio, a session that would not activate, a load timeout). */
    fun reportCouldNotStart(
        generation: Int,
        debugInfo: String?,
    ) {
        if (!isCurrent(generation)) return
        error.value = PlaybackError.CouldNotStart(debugInfo = debugInfo)
    }

    /** Swift unloaded the engine on its own (sign-out's awaited stop). */
    fun onUnloaded(generation: Int) {
        if (!isCurrent(generation)) return
        unload()
    }

    private fun unload() {
        loadedBookId.value = null
        phase.value = EnginePhase.IDLE
        positionMs.value = 0L
        durationMs.value = 0L
        chapters.value = emptyList()
        timeline.value = null
        error.value = null
    }
}
