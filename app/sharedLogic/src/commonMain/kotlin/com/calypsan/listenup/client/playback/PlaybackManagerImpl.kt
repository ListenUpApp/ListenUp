
package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.playback.loudness.VolumeGain
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

/**
 * Fraction of total duration a position must reach for an `Ended` state to count
 * as genuine completion. Guards against spurious `Ended` events on player
 * release/stop falsely marking a book finished.
 */
private const val BOOK_FINISHED_THRESHOLD = 0.90f

/**
 * Content-position delta (ms) between periodic durable persists on the built-in-player
 * (Desktop) observation path. The built-in player advances [AudioPlayer.positionMs]
 * continuously while playing, so persisting every time the position has moved this far
 * bounds crash loss to ~this much content and keeps the listening-span heartbeat alive
 * (without it `recoverOrphan` would finalize the span at `endedAt == startedAt` and drop
 * it). 10 s sits between iOS's 5 s and Android's 30 s. Measured in content-position rather
 * than wall-clock so it is deterministic under virtual-time tests and adds no scheduled
 * timer task that would trap `advanceUntilIdle()`.
 */
private const val POSITION_PERSIST_INTERVAL_MS = 10_000L

/**
 * Default [PlaybackManager] implementation. See the interface KDoc on
 * [PlaybackManager] for the contract; this class is the sole production
 * realisation, wired into Koin as `single<PlaybackManager> { PlaybackManagerImpl(...) }`.
 *
 * LongParameterList suppressed: forwards the same heterogeneous playback-prep
 * collaborators to [PlaybackPreparer] (auth, 3 DAOs + repo, cover storage, progress,
 * codec negotiation, download). A parameter object would only bag them and ripples
 * into platform code that also constructs this class.
 */
@Suppress("LongParameterList")
internal class PlaybackManagerImpl(
    private val serverConfig: ServerConfig,
    private val playbackPreferences: PlaybackPreferences,
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val chapterDao: ChapterDao,
    private val imageStorage: ImageStorage,
    private val progressTracker: ProgressTracker,
    private val reporter: PlaybackProgressReporter,
    private val tokenProvider: AudioTokenProvider,
    private val deviceContext: DeviceContext,
    private val downloadService: DownloadService,
    private val prepareRepository: PlaybackPrepareRepository,
    private val channel: RpcChannel<BookService>,
    private val scope: CoroutineScope,
    private val bookSyncDomainHandler: SyncDomainHandler<BookSyncPayload>,
    private val playbackBandwidthCoordinator: PlaybackBandwidthCoordinator,
    private val localPreferences: LocalPreferences,
    /**
     * When true, this instance is the reporter-based persistence owner: [setPlaybackState]
     * routes Playing/Paused transitions through [reporter] (position + listening span), and
     * [playerObservationJob] additionally persists periodically off the position stream (every
     * [POSITION_PERSIST_INTERVAL_MS] of content). This is the sole persistence path on the
     * built-in-player wiring (Desktop; iOS drives [reporter] via its own coordinator).
     *
     * Android sets this false: its Media3 `PlaybackService.PlayerListener` already owns
     * book-relative transition persistence, periodic position persistence, AND listening-event
     * recording, and those same signals also reach this class via `MediaControllerHolder`.
     * Letting both persist would double-write the outbox. Android keeps this class as the
     * UI/StateFlow source of truth only; it remains the persistence path for explicit speed
     * changes ([onSpeedChanged]/[onSpeedReset]). (Android also never invokes the built-in-player
     * [startPlayback] overload, so [playerObservationJob] does not run there — the flag is a
     * second, structural guarantee against a double periodic writer.)
     */
    private val persistTransitionsViaReporter: Boolean = true,
) : PlaybackManager {
    private val preparer =
        PlaybackPreparer(
            serverConfig = serverConfig,
            playbackPreferences = playbackPreferences,
            bookDao = bookDao,
            audioFileDao = audioFileDao,
            chapterDao = chapterDao,
            imageStorage = imageStorage,
            progressTracker = progressTracker,
            tokenProvider = tokenProvider,
            deviceContext = deviceContext,
            downloadService = downloadService,
            prepareRepository = prepareRepository,
            channel = channel,
            scope = scope,
            bookSyncDomainHandler = bookSyncDomainHandler,
            localPreferences = localPreferences,
        )

    override val currentBookId: StateFlow<BookId?>
        field = MutableStateFlow<BookId?>(null)

    /** String version of currentBookId for Swift/SKIE (value classes dont bridge to flows) */
    val currentBookIdString: StateFlow<String?>
        field = MutableStateFlow<String?>(null)

    override val currentTimeline: StateFlow<PlaybackTimeline?>
        field = MutableStateFlow<PlaybackTimeline?>(null)

    override val isPlaying: StateFlow<Boolean>
        field = MutableStateFlow(false)

    override val currentPositionMs: StateFlow<Long>
        field = MutableStateFlow(0L)

    override val totalDurationMs: StateFlow<Long>
        field = MutableStateFlow(0L)

    override val playbackSpeed: StateFlow<Float>
        field = MutableStateFlow(1.0f)

    override val volumeBoostDb: StateFlow<Float>
        field = MutableStateFlow(0f)

    // Error state for displaying playback errors to the user
    // Null means no error, non-null means error to display
    override val playbackError: StateFlow<PlaybackManager.PlaybackErrorUiState?>
        field = MutableStateFlow<PlaybackManager.PlaybackErrorUiState?>(null)

    override val isBuffering: StateFlow<Boolean>
        field = MutableStateFlow(false)

    override val playbackState: StateFlow<PlaybackState>
        field = MutableStateFlow<PlaybackState>(PlaybackState.Idle)

    // Chapter state for notification and UI
    override val chapters: StateFlow<List<Chapter>>
        field = MutableStateFlow<List<Chapter>>(emptyList())

    override val preparingBookId: StateFlow<BookId?>
        field = MutableStateFlow<BookId?>(null)

    // A plain, COLD Flow — deliberately NOT `.stateIn(scope, ...)` here. `stateIn` launches its
    // sharing coroutine into `scope` at the moment this property is initialized, regardless of
    // SharingStarted mode (Eagerly/Lazily/WhileSubscribed all park a coroutine there — only WHEN it
    // starts collecting differs, not WHETHER a coroutine gets created); that coroutine never
    // completes, and every PlaybackManagerImpl ever constructed — including test-only instances that
    // never touch this flow — got one for free. jvmTests that bind `scope` to their TestScope (needed
    // so `advanceUntilIdle` can drain ProgressTracker/PlaybackManager coroutines) then failed with
    // `UncompletedCoroutinesError` on the dangling collector; see PlaybackManagerPreparingSharingTest.
    // Per the rubric ("Streaming is Flow<T>. State is .stateIn(scope) at the call site"), state-ifying
    // this is the CONSUMER's job — NowPlayingViewModel already folds it into its own
    // `combine(...).stateIn(viewModelScope, WhileSubscribed(...))` for [NowPlayingScreenState].
    override val preparingBookIdUi: Flow<BookId?> = preparingBookIdUiFlow(preparingBookId)

    override val currentChapter: StateFlow<PlaybackManager.ChapterInfo?>
        field = MutableStateFlow<PlaybackManager.ChapterInfo?>(null)

    override val effectiveGainDb: StateFlow<Float>
        field = MutableStateFlow(0f)

    // The per-book normalization inputs behind [effectiveGainDb], set on prepare and read (never
    // mutated) by [onVolumeBoostChanged]/[onBoostReset] to recompute the combined gain. A new
    // measurement arriving mid-book never touches these — the manager doesn't observe measurements
    // at all, so effectiveGainDb only ever moves on prepare or an explicit user boost change.
    private var measuredGainDb: Float? = null
    private var normalizationGainDb: Float? = null

    // Tracks the coroutine that observes AudioPlayer state/position on Desktop/Apple.
    // Cancelled by clearPlayback so observations don't outlive a playback session.
    private var playerObservationJob: Job? = null

    // Callback for chapter changes - used by PlaybackService to update notification
    override var onChapterChanged: ((PlaybackManager.ChapterInfo) -> Unit)? = null

    /** Open the pending-play window for [bookId]. See [PlaybackManager.markPreparing]. */
    override fun markPreparing(bookId: BookId) {
        preparingBookId.value = bookId
    }

    /** Close the pending-play window. See [PlaybackManager.clearPreparing]. */
    override fun clearPreparing() {
        preparingBookId.value = null
    }

    /** Set the current book ID — call this only when playback is confirmed to proceed. */
    override fun activateBook(bookId: BookId) {
        currentBookId.value = bookId
        currentBookIdString.value = bookId.value
        // A pause window left open by the PREVIOUS book must never leak a transition rewind
        // onto this one's first play, stacking on top of the prepare-time offset already
        // applied for THIS book — see PlaybackProgressReporter.resetAutoRewindWindow KDoc.
        reporter.resetAutoRewindWindow()
    }

    /**
     * Prepare for playback of a book.
     *
     * Steps:
     * 1. Ensure fresh auth token
     * 2. Get book from database
     * 3. Parse audio files from JSON
     * 4. Build PlaybackTimeline
     * 5. Get resume position
     *
     * @return PrepareResult with timeline and resume position, or null on failure
     */
    override suspend fun prepareForPlayback(bookId: BookId): PlaybackManager.PrepareResult? {
        val prepared = preparer.prepare(bookId) ?: return null

        currentTimeline.value = prepared.timeline
        // Note: currentBookId is set by caller after reachability checks pass
        totalDurationMs.value = prepared.timeline.totalDurationMs
        chapters.value = prepared.chapters

        // Seed the gain inputs and recompute effectiveGainDb once, here, at prepare time — never
        // live off a mid-book measurement (the manager doesn't observe those; see the field KDoc).
        measuredGainDb = prepared.measuredGainDb
        normalizationGainDb = prepared.normalizationGainDb
        volumeBoostDb.value = prepared.resumeBoostDb
        effectiveGainDb.value =
            VolumeGain.effectiveGainDb(prepared.measuredGainDb, prepared.normalizationGainDb, prepared.resumeBoostDb)

        return PlaybackManager.PrepareResult(
            timeline = prepared.timeline,
            bookTitle = prepared.bookTitle,
            bookAuthor = prepared.bookAuthor,
            seriesName = prepared.seriesName,
            coverPath = prepared.coverPath,
            totalChapters = prepared.chapters.size,
            resumePositionMs = prepared.resumePositionMs,
            resumeSpeed = prepared.resumeSpeed,
            resumeBoostDb = prepared.resumeBoostDb,
            measuredGainDb = prepared.measuredGainDb,
            normalizationGainDb = prepared.normalizationGainDb,
        )
    }

    /**
     * Start playback using a platform AudioPlayer.
     *
     * Bridges the prepared timeline to the AudioPlayer and connects
     * state flows back to PlaybackManager for position tracking.
     *
     * @param player The platform-specific audio player implementation
     * @param resumePositionMs Position to resume from (0 to start from beginning)
     * @param resumeSpeed Playback speed to use
     */
    override suspend fun startPlayback(
        player: AudioPlayer,
        resumePositionMs: Long,
        resumeSpeed: Float,
    ) {
        val timeline = currentTimeline.value
        if (timeline == null) {
            logger.error { "Cannot start playback: no timeline prepared" }
            return
        }

        val bookId = currentBookId.value
        if (bookId == null) {
            logger.error { "Cannot start playback: no book ID" }
            return
        }

        // Build segments from timeline
        val segments =
            timeline.files.map { file ->
                AudioSegment(
                    url = file.streamingUrl,
                    hlsUrl = file.hlsUrl,
                    localPath = file.localPath,
                    durationMs = file.durationMs,
                    offsetMs = file.startOffsetMs,
                )
            }

        // Load segments into player
        player.load(segments)

        // Set speed before seeking/playing
        player.setSpeed(resumeSpeed)
        playbackSpeed.value = resumeSpeed

        // Resume from saved position
        if (resumePositionMs > 0) {
            player.seekTo(resumePositionMs)
        }
        currentPositionMs.value = resumePositionMs

        // Auto-rewind-on-resume actuator (#1220) for the built-in-player (Desktop) path: the
        // reporter owns the pause-window/ladder decision, but only this function has the
        // AudioPlayer handle needed to actually seek. Re-registered every call so the closure
        // always captures the CURRENT session's player, never a stale one from a prior book.
        reporter.onAutoRewindSeek = { rewindMs ->
            val newPositionMs = (currentPositionMs.value - rewindMs).coerceAtLeast(0L)
            player.seekTo(newPositionMs)
            updatePosition(newPositionMs)
        }

        // Bridge player state and position back to PlaybackManager.
        // Both child launches are parented to playerObservationJob so a single
        // cancel() in clearPlayback stops both collectors together.
        playerObservationJob?.cancel()
        playerObservationJob =
            scope.launch {
                launch {
                    // Desktop's built-in-player path has no external periodic persister — Android's
                    // is `PlaybackService` (30 s), iOS's is `PlayerCoordinator` (5 s), and both reach
                    // this class through their own seams, NOT through this observation job (which runs
                    // ONLY on the built-in-player/Desktop path). Without a periodic writer here a
                    // desktop crash mid-session would lose everything since `onPlaybackStarted`, and
                    // the listening span's heartbeat would never advance. Persist through [reporter]
                    // every POSITION_PERSIST_INTERVAL_MS of content movement while playing. Gated by
                    // [persistTransitionsViaReporter]: this instance is the reporter-persistence owner
                    // only on the Desktop/iOS-style wiring; Android sets it false so its own loop stays
                    // the sole periodic writer (no double-drive). Driven off the position stream rather
                    // than a delay loop, so it schedules no timer task.
                    var lastPersistedPositionMs = resumePositionMs
                    player.positionMs.collect { position ->
                        updatePosition(position)
                        if (persistTransitionsViaReporter &&
                            isPlaying.value &&
                            abs(position - lastPersistedPositionMs) >= POSITION_PERSIST_INTERVAL_MS
                        ) {
                            lastPersistedPositionMs = position
                            currentBookId.value?.let { activeBookId ->
                                reporter.onPositionUpdate(activeBookId, position, playbackSpeed.value)
                            }
                        }
                    }
                }
                launch {
                    player.state.collect { playbackState ->
                        setPlaybackState(playbackState)
                        setBuffering(playbackState == PlaybackState.Buffering)

                        val playing = playbackState == PlaybackState.Playing
                        setPlaying(playing)

                        // Error routing: AudioPlayer actuals emit
                        // PlaybackState.Error(message?) for platform-native failures;
                        // PlaybackManager turns that into PlaybackError on the public flow.
                        // (Android emits errors via [reportError] from MediaControllerHolder;
                        // setPlaybackState never carries Error on the Android path.)
                        // Playing/Paused → [reporter] (position + listening-event)
                        // routing lives in [setPlaybackState] so both Desktop (via this collect)
                        // and Android (via PlaybackStateWriter.setPlaybackState from
                        // MediaControllerHolder.Player.Listener) flow through one path.
                        if (playbackState is PlaybackState.Error) {
                            playbackError.value =
                                PlaybackManager.PlaybackErrorUiState(
                                    message = playbackState.message ?: "Playback failed.",
                                    isRecoverable = playbackState.isRecoverable,
                                    timestampMs =
                                        com.calypsan.listenup.core
                                            .currentEpochMilliseconds(),
                                )
                        }

                        if (playbackState == PlaybackState.Ended) {
                            val duration = totalDurationMs.value
                            val position = currentPositionMs.value
                            // Guard: only mark finished if position is actually near the end.
                            // Prevents false completion from spurious Ended events on player
                            // release/stop.
                            if (duration > 0 && position.toFloat() / duration >= BOOK_FINISHED_THRESHOLD) {
                                reporter.onBookFinished(bookId, duration)
                            } else {
                                logger.warn {
                                    "Ignoring Ended state: position=${position}ms " +
                                        "not near end (duration=${duration}ms)"
                                }
                            }
                        }
                    }
                }
            }

        // Start playback. The Playing transition is routed through progressTracker
        // by [setPlaybackState] when the collect above forwards the player's
        // emission; no explicit call here.
        player.play()

        logger.info { "Playback started via AudioPlayer at position ${resumePositionMs}ms, speed ${resumeSpeed}x" }
    }

    /**
     * Update playing flag. Called by platform-specific event sources
     * (Android: MediaControllerHolder's Player.Listener; Desktop: PlaybackManager's
     * own AudioPlayer.state observation in startPlayback).
     *
     * The single shared isPlaying-transition seam for #1220's in-session auto-rewind: a
     * Playing→Paused edge marks the pause moment ([PlaybackProgressReporter.notePlaybackPaused]),
     * a Paused→Playing edge applies the graduated ladder for however long that pause lasted
     * ([PlaybackProgressReporter.notePlaybackResumed]). Unconditional — unlike
     * [setPlaybackState]'s persistence branches, these carry no persistence side effect, so they
     * run the same way regardless of [persistTransitionsViaReporter] (see that reporter's KDoc for
     * why Android is safe here too, and why iOS needs its own native wiring).
     */
    override fun setPlaying(playing: Boolean) {
        val wasPlaying = isPlaying.value
        isPlaying.value = playing
        when {
            wasPlaying && !playing -> reporter.notePlaybackPaused()
            !wasPlaying && playing -> reporter.notePlaybackResumed()
        }
    }

    /**
     * Update buffering flag. Called by platform-specific event sources
     * (Android: MediaControllerHolder's Player.Listener; Desktop: PlaybackManager's
     * own AudioPlayer.state observation in startPlayback).
     */
    override fun setBuffering(buffering: Boolean) {
        isBuffering.value = buffering
        // Feed the "playback preempts downloads" signal: yield bandwidth only when a
        // NOT-fully-downloaded book is buffering — a local book needs no help, a stream does.
        val streaming = currentTimeline.value?.isFullyDownloaded != true
        playbackBandwidthCoordinator.setStreamingBuffering(buffering && streaming)
    }

    /**
     * Update playback state (Idle/Buffering/Playing/Paused/Ended/Error). Same
     * caller scheme as [setBuffering].
     *
     * Every Playing/Paused transition (whether triggered by Desktop's
     * AudioPlayer state observation in [playerObservationJob] or Android's
     * [MediaControllerHolder.Player.Listener] pushing through this seam) routes
     * through [reporter] here, which persists position and (on Desktop/macOS, where a
     * recorder is bound) records the listening span. VMs no longer call it directly.
     * Playing also clears any previous [playbackError] so transient failures
     * resolve as soon as the player recovers.
     */
    override fun setPlaybackState(state: PlaybackState) {
        playbackState.value = state
        when (state) {
            PlaybackState.Playing -> {
                playbackError.value = null
                if (persistTransitionsViaReporter) {
                    currentBookId.value?.let { activeBookId ->
                        reporter.onPlaybackStarted(
                            activeBookId,
                            currentPositionMs.value,
                            playbackSpeed.value,
                        )
                    }
                }
            }

            PlaybackState.Paused -> {
                if (persistTransitionsViaReporter) {
                    currentBookId.value?.let { activeBookId ->
                        reporter.onPlaybackPaused(
                            activeBookId,
                            currentPositionMs.value,
                            playbackSpeed.value,
                        )
                    }
                }
            }

            else -> {}
        }
    }

    /**
     * Update current position. Called by platform-specific event sources
     * (Android: MediaControllerHolder's position polling loop; Desktop:
     * PlaybackManager's own AudioPlayer.state observation in startPlayback).
     */
    override fun updatePosition(positionMs: Long) {
        currentPositionMs.value = positionMs
        updateCurrentChapter(positionMs)
    }

    /**
     * Convert ExoPlayer per-file coordinates to a book-relative position via the active
     * [currentTimeline], then delegate to [updatePosition]. See [PlaybackStateWriter] for
     * why the raw file offset must never be stored directly. Falls back to the raw
     * [positionInItemMs] when no timeline is active (single-item/degenerate case).
     */
    override fun updatePositionFromMediaItem(
        mediaItemIndex: Int,
        positionInItemMs: Long,
    ) {
        val bookPositionMs =
            currentTimeline.value?.toBookPosition(mediaItemIndex, positionInItemMs)
                ?: positionInItemMs
        updatePosition(bookPositionMs)
    }

    /**
     * Update playback speed. Called by platform-specific event sources
     * (Android: MediaControllerHolder's Player.Listener on PlaybackParameters
     * change; Desktop: PlaybackManager's own AudioPlayer.state observation in
     * startPlayback).
     */
    override fun updateSpeed(speed: Float) {
        playbackSpeed.value = speed
    }

    /**
     * Called when user explicitly changes playback speed for the current book.
     *
     * Writes per-book only via [reporter] → [progressTracker.onSpeedChanged], which sets
     * `hasCustomSpeed = true`. The global default is changed only via
     * Settings → Default Speed; per-book changes do NOT mutate the global default.
     */
    override fun onSpeedChanged(speed: Float) {
        val bookId = currentBookId.value ?: return
        val positionMs = currentPositionMs.value
        playbackSpeed.value = speed
        reporter.onSpeedChanged(bookId, positionMs, speed)
    }

    /**
     * Reset book's speed to universal default.
     * Called when user explicitly resets to default speed.
     *
     * @param defaultSpeed The universal default speed from settings
     */
    override fun onSpeedReset(defaultSpeed: Float) {
        val bookId = currentBookId.value ?: return
        val positionMs = currentPositionMs.value
        playbackSpeed.value = defaultSpeed
        reporter.onSpeedReset(bookId, positionMs, defaultSpeed)
    }

    /**
     * Called when user explicitly changes volume boost for the current book.
     *
     * Writes per-book only via [reporter] → [progressTracker.onVolumeBoostChanged], which sets
     * `hasCustomBoost = true`. The global default is changed only via Settings → Default Boost;
     * per-book changes do NOT mutate the global default. Recomputes [effectiveGainDb] immediately
     * from the new boost plus this book's [measuredGainDb]/[normalizationGainDb] — never waits for
     * a fresh measurement, which the manager never observes mid-book anyway.
     */
    override fun onVolumeBoostChanged(boostDb: Float) {
        val bookId = currentBookId.value ?: return
        val positionMs = currentPositionMs.value
        volumeBoostDb.value = boostDb
        effectiveGainDb.value = VolumeGain.effectiveGainDb(measuredGainDb, normalizationGainDb, boostDb)
        reporter.onVolumeBoostChanged(bookId, positionMs, boostDb)
    }

    /**
     * Reset book's volume boost to universal default.
     * Called when user explicitly resets to default boost.
     *
     * @param defaultBoostDb The universal default boost from settings
     */
    override fun onBoostReset(defaultBoostDb: Float) {
        val bookId = currentBookId.value ?: return
        val positionMs = currentPositionMs.value
        volumeBoostDb.value = defaultBoostDb
        effectiveGainDb.value = VolumeGain.effectiveGainDb(measuredGainDb, normalizationGainDb, defaultBoostDb)
        reporter.onBoostReset(bookId, positionMs, defaultBoostDb)
    }

    /**
     * Clear current playback state.
     * Called when playback stops or when access is revoked.
     */
    override fun clearPlayback() {
        playerObservationJob?.cancel()
        playerObservationJob = null
        currentBookId.value = null
        currentBookIdString.value = null
        currentTimeline.value = null
        chapters.value = emptyList()
        currentChapter.value = null
        isPlaying.value = false
        currentPositionMs.value = 0L
        totalDurationMs.value = 0L
        playbackSpeed.value = 1.0f
        volumeBoostDb.value = 0f
        effectiveGainDb.value = 0f
        measuredGainDb = null
        normalizationGainDb = null
        playbackError.value = null
        isBuffering.value = false
        // Release the download-yield signal on teardown too — this path clears `isBuffering`
        // directly (not via `setBuffering`), so tell the coordinator explicitly or a clear while
        // buffering could leave downloads yielded until some later state change.
        playbackBandwidthCoordinator.setStreamingBuffering(false)
        playbackState.value = PlaybackState.Idle
        reporter.resetAutoRewindWindow()
    }

    /**
     * Report a playback error to be displayed to the user.
     * Called by platform-specific error handlers.
     */
    override fun reportError(
        message: String,
        isRecoverable: Boolean,
    ) {
        playbackError.value =
            PlaybackManager.PlaybackErrorUiState(
                message = message,
                isRecoverable = isRecoverable,
                timestampMs =
                    com.calypsan.listenup.core
                        .currentEpochMilliseconds(),
            )
    }

    /**
     * Clear the current playback error.
     * Called when user dismisses the error or error condition is resolved.
     */
    override fun clearError() {
        playbackError.value = null
    }

    /**
     * Update current chapter based on position.
     * Called from updatePosition() to track chapter changes.
     */
    internal fun updateCurrentChapter(positionMs: Long) {
        val chapterList = chapters.value
        if (chapterList.isEmpty()) {
            currentChapter.value = null
            return
        }

        val index =
            chapterList
                .indexOfLast { it.startTime <= positionMs }
                .coerceAtLeast(0)

        val chapter = chapterList[index]
        val endMs =
            chapterList.getOrNull(index + 1)?.startTime
                ?: currentTimeline.value?.totalDurationMs
                ?: chapter.startTime

        val newChapter =
            PlaybackManager.ChapterInfo(
                index = index,
                title = chapter.title,
                startMs = chapter.startTime,
                endMs = endMs,
                remainingMs = (endMs - positionMs).coerceAtLeast(0),
                totalChapters = chapterList.size,
                isGenericTitle = isGenericChapterTitle(chapter.title),
            )

        // Only trigger notification update on chapter change
        if (newChapter.index != currentChapter.value?.index) {
            currentChapter.value = newChapter
            onChapterChanged?.invoke(newChapter)
        } else {
            // Update remaining time without triggering notification
            currentChapter.value = newChapter
        }
    }

    /**
     * Detect if a chapter title is generic (e.g., "Chapter 14", "Track 7", or empty).
     */
    private fun isGenericChapterTitle(title: String): Boolean {
        val normalized = title.trim().lowercase()
        return normalized.isEmpty() ||
            normalized.matches(Regex("""^(chapter|part|track|section)\s*\d+$""")) ||
            normalized.matches(Regex("""^\d+$"""))
    }
}
