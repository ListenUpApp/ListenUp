package com.calypsan.listenup.client.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.composeapp.R
import com.calypsan.listenup.client.automotive.BrowseTree
import com.calypsan.listenup.client.automotive.BrowseTreeProvider
import com.calypsan.listenup.client.automotive.CustomActions
import com.calypsan.listenup.client.playback.cast.CastMediaItemFactory
import com.calypsan.listenup.client.playback.cast.CastPreparer
import com.calypsan.listenup.client.playback.cast.CastSessionController
import com.calypsan.listenup.client.playback.cast.CastSourceItem
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.voice.MediaFocus
import com.calypsan.listenup.client.voice.PlaybackIntent
import com.calypsan.listenup.client.voice.VoiceHints
import com.calypsan.listenup.client.voice.VoiceIntentResolver
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import com.calypsan.listenup.client.core.DurationFormatter
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

/**
 * Background playback service using Media3.
 *
 * Responsibilities:
 * - Manages ExoPlayer instance
 * - Exposes MediaSession for system integration (notification, lock screen, Bluetooth)
 * - Handles audio focus
 * - Survives Activity destruction for background playback
 *
 * Lifecycle:
 * - Tiered idle timeouts (30min pause, 5min sleep timer, 2hr book finish)
 * - Explicit stop via notification
 * - Survives app swipe-away while timer is active
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var notificationProvider: AudiobookNotificationProvider? = null
    private var castSessionController: CastSessionController? = null

    // True while the session player is the cast player. Main-thread only (set in the
    // cast handoffs, read by the idle-timer guard). The PlayerListener is attached only
    // to the local ExoPlayer, so cast play/pause never reaches it — this flag is how the
    // idle timer learns to stand down while casting (see startIdleTimer).
    private var casting = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var idleJob: Job? = null
    private var positionUpdateJob: Job? = null

    // Inject dependencies
    private val playbackManager: PlaybackManager by inject()
    private val progressTracker: ProgressTracker by inject()
    private val listeningEventRecorder: com.calypsan.listenup.client.playback.ListeningEventRecorder by inject()
    private val positionRepository: PlaybackPositionRepository by inject()
    private val errorHandler: PlaybackErrorHandler by inject()
    private val errorBus: ErrorBus by inject()
    private val tokenProvider: AndroidAudioTokenProvider by inject()
    private val sleepTimerManager: SleepTimerManager by inject()
    private val browseTreeProvider: BrowseTreeProvider by inject()
    private val voiceIntentResolver: VoiceIntentResolver by inject()
    private val homeRepository: HomeRepository by inject()
    private val castPreparer: CastPreparer by inject()

    // Current book ID is read from PlaybackManager (single source of truth)
    private val currentBookId: BookId?
        get() = playbackManager.currentBookId.value

    /**
     * Get the current book-relative position.
     *
     * ExoPlayer tracks position within the current file, but we need position
     * relative to the entire book for progress tracking. The PlaybackTimeline
     * handles this translation.
     *
     * Reads the active session player — the local ExoPlayer normally, the cast
     * player while casting — so position recording follows wherever audio is playing.
     *
     * @return Book-relative position in milliseconds, or 0 if unavailable
     */
    private fun getBookRelativePosition(): Long {
        val player = mediaLibrarySession?.player ?: player ?: return 0L
        val timeline = playbackManager.currentTimeline.value ?: return player.currentPosition
        return timeline.toBookPosition(player.currentMediaItemIndex, player.currentPosition)
    }

    companion object {
        // Idle timeout tiers
        private val IDLE_TIMEOUT_SHORT = 30.minutes // After natural pause
        private val IDLE_TIMEOUT_LONG = 2.hours // After book completion
        private val IDLE_TIMEOUT_SLEEP = 5.minutes // After sleep timer fires

        // Position update interval
        private const val POSITION_UPDATE_INTERVAL = 30_000L // 30 seconds

        // Search cache configuration
        private const val MAX_SEARCH_CACHE_SIZE = 5
    }

    override fun onCreate() {
        super.onCreate()
        logger.info { "PlaybackService created" }

        initializePlayer()
        initializeMediaSession()
        initializeCast()
        initializeNotificationProvider()

        // Register callback for chapter changes to update notification
        playbackManager.onChapterChanged = { chapterInfo ->
            logger.debug { "Chapter changed: ${chapterInfo.title}" }
            updateNotificationForChapter(chapterInfo)
        }
    }

    private fun initializePlayer() {
        // Create OkHttp client with auth interceptor
        val okHttpClient =
            OkHttpClient
                .Builder()
                .addInterceptor(tokenProvider.createInterceptor())
                .authenticator(tokenProvider.createAuthenticator())
                .connectTimeout(30.seconds.toJavaDuration())
                .readTimeout(0.seconds.toJavaDuration()) // No read timeout for streaming
                .writeTimeout(30.seconds.toJavaDuration())
                .build()

        // Create DataSource factory: OkHttp for HTTP(S) streaming, DefaultDataSource
        // wrapper to also handle file:// URIs for downloaded audiobooks
        val httpDataSourceFactory: DataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        // Create media source factory
        val mediaSourceFactory =
            DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)

        // Create renderers factory with AAC DRC for consistent loudness + decoder fallback
        val renderersFactory =
            AacDrcRenderersFactory(this)
                .setEnableDecoderFallback(true)

        // Build ExoPlayer with audiobook-optimized settings
        player =
            ExoPlayer
                .Builder(this)
                .setRenderersFactory(renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH) // Audiobooks!
                        .build(),
                    // handleAudioFocus =
                    true,
                ).setHandleAudioBecomingNoisy(true) // Pause when headphones unplugged
                .setWakeMode(C.WAKE_MODE_LOCAL) // Keep CPU awake during playback
                .build()
                .apply {
                    addListener(PlayerListener())

                    // Enable audio offload for battery savings during long listening sessions
                    // DSP-based decoding while CPU sleeps
                    val audioOffloadPreferences =
                        TrackSelectionParameters.AudioOffloadPreferences
                            .Builder()
                            .setAudioOffloadMode(
                                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                            ).setIsGaplessSupportRequired(true)
                            .build()

                    trackSelectionParameters =
                        trackSelectionParameters
                            .buildUpon()
                            .setAudioOffloadPreferences(audioOffloadPreferences)
                            .build()
                }

        logger.info { "ExoPlayer initialized with audio offload enabled" }
    }

    private fun initializeMediaSession() {
        val sessionIntent =
            packageManager?.getLaunchIntentForPackage(packageName)?.let { intent ->
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        val activePlayer =
            player ?: run {
                logger.error { "initializeMediaSession called before player init" }
                return
            }
        val builder = MediaLibrarySession.Builder(this, activePlayer, LibrarySessionCallback())
        if (sessionIntent != null) {
            builder.setSessionActivity(sessionIntent)
        }

        mediaLibrarySession = builder.build()

        logger.info { "MediaLibrarySession initialized" }
    }

    private fun initializeNotificationProvider() {
        notificationProvider = AudiobookNotificationProvider(this, playbackManager)
        val provider = notificationProvider ?: return
        setMediaNotificationProvider(provider)
        logger.info { "Notification provider initialized" }
    }

    /**
     * Initialize Cast if Google Play Services is present. On a de-Googled device
     * this is a no-op — no cast button, local playback unaffected (never stranded).
     */
    private fun initializeCast() {
        castSessionController =
            CastSessionController.createOrNull(
                context = this,
                onConnected = { serviceScope.launch { handoffToCast() } },
                onDisconnected = { serviceScope.launch { handoffToLocal() } },
            )
        if (castSessionController != null) logger.info { "Cast initialized" }
    }

    /** Local → Cast: re-fetch network URLs, build the cast queue at the current position, swap the session player. */
    private suspend fun handoffToCast() {
        val controller = castSessionController ?: return
        val session = mediaLibrarySession ?: return
        val local = player ?: return
        val bookId = playbackManager.currentBookId.value ?: return

        val prepared = castPreparer.prepareForCast(bookId)
        if (prepared == null) {
            logger.warn { "Cast prepare failed — staying local" }
            // Honest-over-silent: the user tapped cast and nothing audible happened.
            Toast.makeText(this, "Couldn't start casting.", Toast.LENGTH_LONG).show()
            return
        }
        // Connect→disconnect race: the session can drop while prepareForCast suspends. Re-check
        // before committing so we never swap onto a dead cast session with no path back.
        if (!controller.isSessionAvailable) {
            logger.warn { "Cast session gone during prepare — staying local" }
            return
        }
        // Snapshot the currently-loaded items for queue order + on-TV metadata.
        val sourceItems =
            (0 until local.mediaItemCount).map { i ->
                val mi = local.getMediaItemAt(i)
                CastSourceItem(
                    fileId = mi.mediaId,
                    title = mi.mediaMetadata.title?.toString(),
                    artist = mi.mediaMetadata.artist?.toString(),
                    albumTitle = mi.mediaMetadata.albumTitle?.toString(),
                )
            }
        val factory = CastMediaItemFactory()
        val built = factory.build(sourceItems, prepared.files, prepared.coverUrlAbsolute)
        // Index-shift safety: only cast a complete, in-order queue. A dropped file would
        // shift indices and resume on the wrong file — fall back to local instead.
        if (built.tracks.size != sourceItems.size) {
            logger.warn {
                "Cannot cast this book (uncastable=${built.droppedUncastable}, " +
                    "unmatched=${built.droppedUnmatched}) — staying local"
            }
            // Honest-over-silent: tell the user why casting didn't start. Staying local is the
            // never-stranded fallback. (A typed AppError + snackbar is a tracked follow-up; a
            // Toast is the v1 honest-over-silent signal.)
            Toast.makeText(this, "This book's format can't be cast.", Toast.LENGTH_LONG).show()
            return
        }
        val mediaItems = built.tracks.map { factory.toMediaItem(it) }
        val wasPlaying = local.playWhenReady
        val speed = local.playbackParameters.speed
        val index = local.currentMediaItemIndex
        val positionInItem = local.currentPosition

        // Commit to the swap. Set `casting` BEFORE pausing local, because pausing fires the
        // local PlayerListener's onIsPlayingChanged(false) → startIdleTimer, which must stand
        // down while casting (the cast player has no listener to ever cancel it). While casting
        // the local player is paused, so periodic progress saves + listening-event recording
        // pause too; the position is still persisted on disconnect via the cast-aware
        // getBookRelativePosition(). (Tracked follow-up: drive the position job off
        // session.player so cast time counts toward stats.)
        casting = true
        val cast = controller.castPlayer
        cast.setMediaItems(mediaItems, index, positionInItem)
        cast.playWhenReady = wasPlaying
        cast.prepare()
        cast.setPlaybackSpeed(speed) // routes via SpeedAwareCastPlayer → RemoteMediaClient.setPlaybackRate
        session.player = cast
        local.playWhenReady = false // release local audio focus; keep its items loaded for fast return
        cancelIdleTimer() // belt-and-suspenders: the `casting` guard already blocks new timers
        logger.info { "Swapped to Cast at index=$index pos=$positionInItem" }
    }

    /** Cast → Local: read cast position, seek the retained ExoPlayer there, swap back, persist progress. */
    private fun handoffToLocal() {
        val controller = castSessionController ?: return
        val session = mediaLibrarySession ?: return
        val local = player ?: return
        // If the session isn't currently on the cast player, nothing to do (we never swapped).
        if (session.player === local) return
        val cast = controller.castPlayer
        val index = cast.currentMediaItemIndex
        val positionInItem = cast.currentPosition
        val wasPlaying = cast.playWhenReady
        // SpeedAwareCastPlayer mirrors the live receiver rate, so a speed change made while casting
        // is carried back to the local player — otherwise saveCurrentPosition() below persists a
        // stale speed.
        val speed = cast.playbackParameters.speed
        // Known v1 limitation: the retained local player still holds the book that was loaded
        // at hand-off. If the user changed books on the cast device mid-session, swapping back
        // could seek the wrong book. (Tracked follow-up.)
        local.seekTo(index, positionInItem)
        local.playWhenReady = wasPlaying
        local.setPlaybackSpeed(speed)
        session.player = local
        casting = false // normal idle logic resumes now that the local player drives the session
        logger.info { "Swapped back to local at index=$index pos=$positionInItem" }
        saveCurrentPosition() // existing method — persists through the normal recorder
    }

    /**
     * Update metadata when chapter changes.
     *
     * Updates MediaMetadata on the player to show chapter info in:
     * - Android notification
     * - Android Auto display
     * - Bluetooth metadata (car displays, headphones)
     */
    private fun updateNotificationForChapter(chapterInfo: PlaybackManager.ChapterInfo) {
        val session = mediaLibrarySession ?: return
        val p = player ?: return

        // Build chapter subtitle for display
        val chapterText =
            if (chapterInfo.isGenericTitle) {
                "Chapter ${chapterInfo.index + 1} of ${chapterInfo.totalChapters}"
            } else {
                chapterInfo.title
            }

        val timeRemaining = DurationFormatter.hoursMinutesOrUnderMinute(chapterInfo.remainingMs.milliseconds)
        val displaySubtitle = "$chapterText • $timeRemaining left"

        // Get current book info from the existing metadata
        val currentMetadata = p.mediaMetadata
        val bookTitle = currentMetadata.title ?: "Unknown Book"
        val author = currentMetadata.artist
        val seriesName = currentMetadata.albumTitle
        val artworkUri = currentMetadata.artworkUri

        // Build updated metadata with chapter info
        // MEDIA_TYPE_AUDIO_BOOK_CHAPTER tells Android Auto this is a chapter
        val updatedMetadata =
            MediaMetadata
                .Builder()
                .setTitle(bookTitle)
                .setDisplayTitle(bookTitle)
                .setSubtitle(chapterText)
                .setDescription(displaySubtitle)
                .setArtist(author)
                .setAlbumTitle(seriesName)
                .setArtworkUri(artworkUri)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                .setTrackNumber(chapterInfo.index + 1)
                .setTotalTrackCount(chapterInfo.totalChapters)
                .build()

        // Update the current MediaItem's metadata
        // This propagates to Android Auto, notifications, and Bluetooth
        val currentIndex = p.currentMediaItemIndex
        val currentItem = p.currentMediaItem
        if (currentItem != null && currentIndex >= 0) {
            val updatedItem =
                currentItem
                    .buildUpon()
                    .setMediaMetadata(updatedMetadata)
                    .build()
            p.replaceMediaItem(currentIndex, updatedItem)
        }

        // Also update session extras for backward compatibility
        session.setSessionExtras(Bundle().apply { putString("chapter_subtitle", displaySubtitle) })

        logger.debug {
            "Updated chapter metadata: $chapterText (${chapterInfo.index + 1}/${chapterInfo.totalChapters})"
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaLibrarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession?.player

        // Pause playback when user swipes app away — notification remains for resume
        player?.playWhenReady = false

        // Always save position when the user swipes the app away — if the system
        // later kills the process, onDestroy may not get a chance to run.
        // Must be synchronous: saveCurrentPosition() is fire-and-forget and races
        // process death; saveCurrentPositionBlocking() completes before returning.
        saveCurrentPositionBlocking()

        // Don't stop immediately - keep the idle timer running
        // User can still resume from notification
        if (player == null || idleJob == null) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        logger.info { "PlaybackService destroying" }

        // Save position before releasing player — player.release() does not fire
        // onIsPlayingChanged, so without this the position would be lost.
        // Must be synchronous: saveCurrentPosition() is fire-and-forget and races
        // serviceScope.cancel() below; saveCurrentPositionBlocking() completes before returning.
        saveCurrentPositionBlocking()

        idleJob?.cancel()
        positionUpdateJob?.cancel()

        // Clear chapter change callback to avoid memory leaks
        playbackManager.onChapterChanged = null

        // Release session before player — the session holds a reference to the player.
        // player?.release() runs unconditionally so the native decoder is freed even
        // when mediaLibrarySession was never built or was already null.
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        castSessionController?.release()
        castSessionController = null
        player?.release()
        player = null
        notificationProvider = null

        serviceScope.cancel()
        super.onDestroy()
    }

    private fun saveCurrentPosition() {
        val player = player ?: return
        val bookId = currentBookId ?: return

        progressTracker.onPlaybackPaused(
            bookId = bookId,
            positionMs = getBookRelativePosition(),
            speed = player.playbackParameters.speed,
        )
    }

    /**
     * Durable position save for Android lifecycle teardown callbacks.
     *
     * [onDestroy] and [onTaskRemoved] are synchronous Android callbacks that cannot
     * suspend. [saveCurrentPosition] uses [ProgressTracker.onPlaybackPaused], which
     * is fire-and-forget (`scope.launch { }`): the launched coroutine races
     * [serviceScope.cancel] in [onDestroy] and races OS process death in
     * [onTaskRemoved], so the Room write may never run.
     *
     * This function uses [runBlocking] with [NonCancellable] to ensure the write
     * completes before the callback returns. [runBlocking] is intentional and correct
     * here — this is a platform-boundary use where suspension is impossible, not the
     * anti-pattern banned in coroutine-path code.
     */
    private fun saveCurrentPositionBlocking() {
        val bookId = currentBookId ?: return
        val positionMs = getBookRelativePosition()
        runBlocking(NonCancellable) {
            progressTracker.savePositionNow(bookId, positionMs)
        }
    }

    private fun startIdleTimer(
        timeout: kotlin.time.Duration,
        reason: String,
    ) {
        // Never idle-out while casting: the cast player lives inside THIS service's session,
        // and its play/pause events never reach the local PlayerListener that would cancel the
        // timer — so a timer started here (e.g. by the pause that hand-off triggers on the local
        // player) would tear the cast session down after the timeout. The flag check is the
        // robust guard because onIsPlayingChanged is posted asynchronously and could fire after
        // a bare cancel. Covers every startIdleTimer path (pause, idle, book-finished, error).
        if (casting) return
        idleJob?.cancel()
        idleJob =
            serviceScope.launch {
                logger.debug { "Idle timer started: $timeout ($reason)" }
                delay(timeout)
                logger.info { "Idle timeout reached, stopping service ($reason)" }
                saveCurrentPosition()
                stopSelf()
            }
    }

    private fun cancelIdleTimer() {
        idleJob?.cancel()
        idleJob = null
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob =
            serviceScope.launch {
                while (isActive) {
                    delay(POSITION_UPDATE_INTERVAL)
                    val player = player ?: break
                    val bookId = currentBookId ?: break

                    if (player.isPlaying) {
                        val positionMs = getBookRelativePosition()
                        progressTracker.onPositionUpdate(
                            bookId = bookId,
                            positionMs = positionMs,
                            speed = player.playbackParameters.speed,
                        )
                        listeningEventRecorder.onPeriodicTick(positionMs = positionMs)
                    }
                }
            }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /**
     * Apply restored playback speed to ExoPlayer.
     * Extracted to keep onAddMediaItems and onPlaybackResumption within complexity limits.
     */
    private fun applyResumeSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    /**
     * Listens to player events for logging and progress tracking.
     */
    private inner class PlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName =
                when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
            logger.debug { "Playback state: $stateName" }

            when (playbackState) {
                Player.STATE_ENDED -> {
                    // Book finished - longer grace period
                    currentBookId?.let { bookId ->
                        val p = this@PlaybackService.player
                        val timeline = playbackManager.currentTimeline.value
                        val finalPosition =
                            timeline?.totalDurationMs
                                ?: p?.duration
                                ?: 0L
                        progressTracker.onBookFinished(bookId, finalPosition)
                    }
                    startIdleTimer(IDLE_TIMEOUT_LONG, "book_finished")
                }

                Player.STATE_IDLE -> {
                    // Player cleared
                    startIdleTimer(IDLE_TIMEOUT_SHORT, "idle")
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            logger.debug { "Is playing: $isPlaying" }

            val bookId = currentBookId
            val player = player

            if (isPlaying) {
                cancelIdleTimer()
                startPositionUpdates()

                if (bookId != null && player != null) {
                    val positionMs = getBookRelativePosition()
                    val speed = player.playbackParameters.speed
                    progressTracker.onPlaybackStarted(
                        bookId = bookId,
                        positionMs = positionMs,
                        speed = speed,
                    )
                    serviceScope.launch {
                        listeningEventRecorder.onPlay(
                            bookId = bookId.value,
                            positionMs = positionMs,
                            playbackSpeed = speed,
                        )
                    }
                }
            } else {
                stopPositionUpdates()

                if (bookId != null && player != null) {
                    val positionMs = getBookRelativePosition()
                    progressTracker.onPlaybackPaused(
                        bookId = bookId,
                        positionMs = positionMs,
                        speed = player.playbackParameters.speed,
                    )
                    serviceScope.launch {
                        listeningEventRecorder.onPause(positionMs = positionMs)
                    }
                }

                // Context-aware idle timer based on why playback stopped
                val isSleepTimerPause = sleepTimerManager.state.value is SleepTimerState.FadingOut
                if (isSleepTimerPause) {
                    startIdleTimer(IDLE_TIMEOUT_SLEEP, "sleep_timer")
                } else {
                    startIdleTimer(IDLE_TIMEOUT_SHORT, "paused")
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            logger.error(error) { "Playback error: ${error.message}" }

            // Surface stuck-player as a typed PlaybackError.Stalled so the global
            // error bus and UI can offer a retry affordance.
            if (error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) {
                errorBus.emit(PlaybackError.Stalled(debugInfo = error.message))
            }

            serviceScope.launch {
                val classified = errorHandler.classify(error)

                val handled =
                    errorHandler.handle(
                        error = classified,
                        player = player!!,
                        currentBookId = currentBookId,
                        // Book-relative (sum of prior file durations + file offset); never the
                        // raw file-relative player.currentPosition read inside the handler.
                        bookPositionMs = getBookRelativePosition(),
                        onShowError = { message ->
                            // Report error to PlaybackManager for UI display
                            playbackManager.reportError(
                                message = message,
                                isRecoverable = false,
                            )
                        },
                    )

                if (!handled) {
                    // Error couldn't be recovered
                    startIdleTimer(IDLE_TIMEOUT_SHORT, "error")
                }
            }
        }

        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int,
        ) {
            logger.debug { "Media item transition: ${mediaItem?.mediaId}, reason: $reason" }
        }
    }

    /**
     * MediaLibrarySession callback for handling browse operations, custom commands,
     * and playback resumption.
     *
     * Handles:
     * - Browse tree navigation for Android Auto
     * - Audiobook-specific commands (skip, chapter navigation)
     * - Playback preparation when user selects a book
     */
    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val customCommands =
                AudiobookNotificationProvider.getCustomCommands() +
                    listOf(CustomActions.cycleSpeedCommand())

            // Limited to 3 custom actions by Android Auto guidelines.
            val customLayout =
                listOf(
                    CommandButton
                        .Builder(CommandButton.ICON_SKIP_BACK_30)
                        .setDisplayName("Back 30s")
                        .setSessionCommand(
                            SessionCommand(AudiobookNotificationProvider.COMMAND_SKIP_BACK_30, Bundle.EMPTY),
                        ).build(),
                    CommandButton
                        .Builder(CommandButton.ICON_UNDEFINED)
                        .setDisplayName("Speed")
                        .setCustomIconResId(R.drawable.ic_speed)
                        .setSessionCommand(CustomActions.cycleSpeedCommand())
                        .build(),
                    CommandButton
                        .Builder(CommandButton.ICON_SKIP_FORWARD_30)
                        .setDisplayName("Forward 30s")
                        .setSessionCommand(
                            SessionCommand(AudiobookNotificationProvider.COMMAND_SKIP_FORWARD_30, Bundle.EMPTY),
                        ).build(),
                )

            val trust = session.classifyController(controller)
            logger.debug { "onConnect from ${controller.packageName} classified as $trust" }
            return session.buildConnectionResultFor(trust, customCommands, customLayout)
        }

        // ========== Browse Operations ==========

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (session.classifyController(browser) == ControllerTrust.UNKNOWN) {
                logger.debug { "onGetLibraryRoot rejected for untrusted controller: ${browser.packageName}" }
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            logger.debug { "onGetLibraryRoot" }
            val root = browseTreeProvider.getRoot()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            logger.debug { "onGetChildren: parentId=$parentId, page=$page, pageSize=$pageSize" }

            return CallbackToFutureAdapter.getFuture { completer ->
                serviceScope.launch {
                    try {
                        val children = browseTreeProvider.getChildren(parentId)
                        completer.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to get children for $parentId" }
                        completer.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                    }
                }
                "GetChildren:$parentId"
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            logger.debug { "onGetItem: mediaId=$mediaId" }

            return CallbackToFutureAdapter.getFuture { completer ->
                serviceScope.launch {
                    try {
                        val item = browseTreeProvider.getItem(mediaId)
                        if (item != null) {
                            completer.set(LibraryResult.ofItem(item, null))
                        } else {
                            completer.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to get item $mediaId" }
                        completer.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                    }
                }
                "GetItem:$mediaId"
            }
        }

        // ========== Search ==========

        /**
         * LRU cache for search results.
         *
         * Bridges the gap between onSearch() and onGetSearchResult() in Media3's
         * async search pattern. Entries are removed after retrieval to prevent
         * memory leaks. Max size provides safety net for unretrieved results.
         */
        private val searchResultsCache =
            object : LinkedHashMap<String, List<MediaItem>>(
                MAX_SEARCH_CACHE_SIZE,
                0.75f,
                true, // access-order for LRU behavior
            ) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaItem>>?) =
                    size > MAX_SEARCH_CACHE_SIZE
            }

        /**
         * Handle search queries from Google Assistant / Android Auto.
         *
         * This is the primary entry point for voice commands like:
         * "Hey Google, play The Hobbit on ListenUp"
         *
         * The Media3 search flow:
         * 1. Google sends the query to onSearch()
         * 2. We resolve via VoiceIntentResolver and cache results
         * 3. Return LibraryResult.ofVoid() to accept the query
         * 4. Call notifySearchResultChanged() to signal results are ready
         * 5. Google calls onGetSearchResult() to retrieve results
         */
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            logger.info { "onSearch: query='$query'" }

            // Perform search asynchronously
            serviceScope.launch {
                try {
                    // Extract hints from params extras if available
                    val extras = params?.extras
                    val hints =
                        VoiceHints(
                            title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
                            artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
                            album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
                            focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)?.toMediaFocus(),
                        )

                    logger.debug { "Search hints: title=${hints.title}, artist=${hints.artist}, focus=${hints.focus}" }

                    val intent = voiceIntentResolver.resolve(query, hints)
                    logger.info { "Search resolved to: $intent" }

                    val items: List<MediaItem> =
                        when (intent) {
                            is PlaybackIntent.PlayBook -> {
                                // Single match - return as playable item
                                listOfNotNull(browseTreeProvider.getBookItem(intent.bookId))
                            }

                            is PlaybackIntent.Resume -> {
                                // Resume - get the last played book
                                val lastBook =
                                    homeRepository
                                        .getContinueListening(1)
                                        .getOrNull()
                                        ?.firstOrNull()
                                if (lastBook != null) {
                                    listOfNotNull(browseTreeProvider.getBookItem(lastBook.bookId))
                                } else {
                                    emptyList()
                                }
                            }

                            is PlaybackIntent.PlaySeriesFrom -> {
                                // Series navigation - return the target book
                                listOfNotNull(browseTreeProvider.getBookItem(intent.startBookId))
                            }

                            is PlaybackIntent.Ambiguous -> {
                                // Multiple matches - return all candidates
                                intent.candidates.mapNotNull { match ->
                                    browseTreeProvider.getBookItem(match.bookId)
                                }
                            }

                            is PlaybackIntent.NotFound -> {
                                logger.warn { "No search results for: $query" }
                                emptyList()
                            }
                        }

                    logger.info { "Search found ${items.size} items for query: $query" }

                    // Cache results for onGetSearchResult
                    searchResultsCache[query] = items

                    // Notify that search results are ready
                    session.notifySearchResultChanged(browser, query, items.size, params)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Search failed for query: $query" }
                    // Still notify with 0 results on error
                    session.notifySearchResultChanged(browser, query, 0, params)
                }
            }

            // Return immediately - results come via onGetSearchResult
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        /**
         * Return cached search results.
         *
         * Called by Android Auto after we notify that search results are ready.
         * Removes cache entry after the last page is retrieved to prevent memory leaks.
         */
        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            logger.debug { "onGetSearchResult: query='$query', page=$page, pageSize=$pageSize" }

            val items = searchResultsCache[query] ?: emptyList()

            // Apply pagination
            val startIndex = page * pageSize
            val endIndex = minOf(startIndex + pageSize, items.size)
            val pageItems =
                if (startIndex < items.size) {
                    items.subList(startIndex, endIndex)
                } else {
                    emptyList()
                }

            // Clean up cache after last page is retrieved
            val isLastPage = endIndex >= items.size
            if (isLastPage) {
                searchResultsCache.remove(query)
                logger.debug { "Search cache cleared for query: $query" }
            }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params),
            )
        }

        // ========== Playback Preparation ==========

        /**
         * Called when Android Auto requests to play media items.
         *
         * Resolves book IDs from media items and prepares full playback timeline.
         * Also handles voice search via requestMetadata.searchQuery (Media3 pattern).
         *
         * Voice search flow:
         * 1. User says "Hey Google, play [book name] on ListenUp"
         * 2. Android Auto/Google Assistant sends MediaItem with searchQuery in requestMetadata
         * 3. We resolve the query through VoiceIntentResolver
         * 4. Return resolved media items for playback
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            logger.info { "onAddMediaItems: ${mediaItems.size} items" }

            return CallbackToFutureAdapter.getFuture { completer ->
                serviceScope.launch {
                    try {
                        val resolvedItems = mutableListOf<MediaItem>()

                        for (item in mediaItems) {
                            // Check for voice search query (Media3 pattern)
                            val searchQuery = item.requestMetadata.searchQuery
                            if (searchQuery != null) {
                                logger.info { "Voice search detected: query='$searchQuery'" }
                                val voiceItems = handleVoiceSearch(item)
                                resolvedItems.addAll(voiceItems)
                                continue
                            }

                            val bookId = BrowseTree.extractBookId(item.mediaId)
                            if (bookId != null) {
                                // Prepare playback for this book
                                val prepareResult = playbackManager.prepareForPlayback(BookId(bookId))
                                if (prepareResult != null) {
                                    // Build MediaItems from timeline
                                    val bookItems =
                                        prepareResult.timeline.files.map { file ->
                                            MediaItem
                                                .Builder()
                                                .setMediaId(file.audioFileId)
                                                .setUri(file.playbackUri)
                                                .setMediaMetadata(
                                                    MediaMetadata
                                                        .Builder()
                                                        .setTitle(prepareResult.bookTitle)
                                                        .setArtist(prepareResult.bookAuthor)
                                                        .setAlbumTitle(prepareResult.seriesName)
                                                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                                                        .build(),
                                                ).build()
                                        }
                                    // Apply the restored playback speed to ExoPlayer
                                    applyResumeSpeed(prepareResult.resumeSpeed)
                                    resolvedItems.addAll(bookItems)
                                }
                            } else {
                                // Not a book ID, pass through as-is
                                resolvedItems.add(item)
                            }
                        }

                        completer.set(resolvedItems)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to resolve media items" }
                        completer.setException(e)
                    }
                }
                "AddMediaItems"
            }
        }

        /**
         * Handle voice search from Android Auto / Google Assistant.
         *
         * Extracts search query and extras from MediaItem's requestMetadata,
         * resolves through VoiceIntentResolver, and returns playable media items.
         */
        private suspend fun handleVoiceSearch(item: MediaItem): List<MediaItem> {
            val searchQuery = item.requestMetadata.searchQuery ?: return emptyList()
            val extras = item.requestMetadata.extras

            // Extract structured hints from extras (if available)
            val hints =
                VoiceHints(
                    title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
                    artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
                    album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
                    focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)?.toMediaFocus(),
                )

            logger.debug { "Voice hints: title=${hints.title}, artist=${hints.artist}, focus=${hints.focus}" }

            val intent = voiceIntentResolver.resolve(searchQuery, hints)
            logger.debug { "Resolved voice intent: $intent" }

            // Convert intent to book ID
            val bookId =
                when (intent) {
                    is PlaybackIntent.PlayBook -> {
                        intent.bookId
                    }

                    is PlaybackIntent.Resume -> {
                        homeRepository
                            .getContinueListening(1)
                            .getOrNull()
                            ?.firstOrNull()
                            ?.bookId
                    }

                    is PlaybackIntent.PlaySeriesFrom -> {
                        intent.startBookId
                    }

                    is PlaybackIntent.Ambiguous -> {
                        // Auto-play best guess if available
                        intent.bestGuess?.bookId
                    }

                    is PlaybackIntent.NotFound -> {
                        logger.warn { "No match found for voice query: ${intent.originalQuery}" }
                        null
                    }
                }

            if (bookId == null) {
                logger.warn { "Could not resolve book ID from voice intent: $intent" }
                return emptyList()
            }

            logger.info { "Playing book from voice search: $bookId" }

            // Prepare playback for the book
            val prepareResult = playbackManager.prepareForPlayback(BookId(bookId))
            if (prepareResult == null) {
                logger.error { "Failed to prepare book for voice playback: $bookId" }
                return emptyList()
            }

            // Build MediaItems from timeline
            return prepareResult.timeline.files.map { file ->
                MediaItem
                    .Builder()
                    .setMediaId(file.audioFileId)
                    .setUri(file.playbackUri)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setTitle(prepareResult.bookTitle)
                            .setArtist(prepareResult.bookAuthor)
                            .setAlbumTitle(prepareResult.seriesName)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                            .build(),
                    ).build()
            }
        }

        /**
         * Handle playback resumption from system UI.
         *
         * Called when user taps "Resume ListenUp" from Android Auto, Wear OS,
         * or system notifications after device reboot.
         *
         * Note: This callback is deprecated in Media3 1.4+ in favor of browse-based
         * resumption via MediaLibraryService. We keep it for backward compatibility
         * with older system UI integrations and as a fallback when browse isn't used.
         */
        @Deprecated("Kept for backward compatibility with older system UI integrations")
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            logger.info { "Playback resumption requested" }

            return CallbackToFutureAdapter.getFuture { completer ->
                serviceScope.launch {
                    try {
                        // Get the last played book from the repository
                        val lastPlayed =
                            when (val r = positionRepository.getLastPlayedBook()) {
                                is AppResult.Success -> {
                                    r.data
                                }

                                is AppResult.Failure -> {
                                    logger.warn { "Failed to read last played book: ${r.error.message}" }
                                    null
                                }
                            }

                        if (lastPlayed == null) {
                            logger.warn { "No last played book found for resumption" }
                            completer.setException(IllegalStateException("No book to resume"))
                            return@launch
                        }

                        logger.info { "Resuming book: ${lastPlayed.bookId.value} at ${lastPlayed.positionMs}ms" }

                        // Prepare playback for the book
                        val prepareResult = playbackManager.prepareForPlayback(lastPlayed.bookId)

                        if (prepareResult == null) {
                            logger.error { "Failed to prepare book for resumption" }
                            completer.setException(IllegalStateException("Failed to prepare book"))
                            return@launch
                        }

                        // Build MediaItems from timeline
                        val resolvedMediaItems =
                            prepareResult.timeline.files.map { file ->
                                MediaItem
                                    .Builder()
                                    .setMediaId(file.audioFileId)
                                    .setUri(file.playbackUri)
                                    .setMediaMetadata(
                                        MediaMetadata
                                            .Builder()
                                            .setTitle(prepareResult.bookTitle)
                                            .setArtist(prepareResult.bookAuthor)
                                            .setAlbumTitle(prepareResult.seriesName)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                                            .build(),
                                    ).build()
                            }

                        // Apply the restored playback speed to ExoPlayer
                        applyResumeSpeed(prepareResult.resumeSpeed)
                        // Resolve start position
                        val startPosition = prepareResult.timeline.resolve(prepareResult.resumePositionMs)

                        completer.set(
                            MediaSession.MediaItemsWithStartPosition(
                                resolvedMediaItems,
                                startPosition.mediaItemIndex,
                                startPosition.positionInFileMs,
                            ),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "Playback resumption failed" }
                        completer.setException(e)
                    }
                }
                "PlaybackResumption"
            }
        }

        // ========== Custom Commands ==========

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            // Route transport controls to the ACTIVE session player — the cast player while
            // casting — so skip/chapter/speed reach the receiver, not the paused local player.
            // Queue order is identical on both (the index-shift guard in handoffToCast ensures
            // a 1:1 map), so the timeline's book-relative → index/offset math holds unchanged.
            val p =
                mediaLibrarySession?.player ?: player ?: return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_UNKNOWN),
                )
            val chapters = playbackManager.chapters.value
            val currentChapter = playbackManager.currentChapter.value
            val timeline = playbackManager.currentTimeline.value

            when (customCommand.customAction) {
                AudiobookNotificationProvider.COMMAND_SKIP_BACK_30 -> {
                    // Get book-relative position, subtract 30s, seek
                    val currentBookPosition = getBookRelativePosition()
                    val newPosition = (currentBookPosition - 30_000).coerceAtLeast(0)

                    // Resolve new position to mediaItemIndex and filePosition
                    if (timeline != null) {
                        val resolved = timeline.resolve(newPosition)
                        p.seekTo(resolved.mediaItemIndex, resolved.positionInFileMs)
                    } else {
                        // Fallback to simple seek within current file
                        val newFilePosition = (p.currentPosition - 30_000).coerceAtLeast(0)
                        p.seekTo(newFilePosition)
                    }
                    logger.debug { "Skip back 30s: $currentBookPosition -> $newPosition" }
                }

                AudiobookNotificationProvider.COMMAND_SKIP_FORWARD_30 -> {
                    val currentBookPosition = getBookRelativePosition()
                    val maxPosition = timeline?.totalDurationMs ?: p.duration
                    val newPosition = (currentBookPosition + 30_000).coerceAtMost(maxPosition)

                    if (timeline != null) {
                        val resolved = timeline.resolve(newPosition)
                        p.seekTo(resolved.mediaItemIndex, resolved.positionInFileMs)
                    } else {
                        val newFilePosition = (p.currentPosition + 30_000).coerceAtMost(p.duration)
                        p.seekTo(newFilePosition)
                    }
                    logger.debug { "Skip forward 30s: $currentBookPosition -> $newPosition" }
                }

                AudiobookNotificationProvider.COMMAND_PREV_CHAPTER -> {
                    if (currentChapter != null && chapters.isNotEmpty() && timeline != null) {
                        val prevIndex = (currentChapter.index - 1).coerceAtLeast(0)
                        val prevChapter = chapters[prevIndex]
                        val resolved = timeline.resolve(prevChapter.startTime)
                        p.seekTo(resolved.mediaItemIndex, resolved.positionInFileMs)
                        logger.debug { "Previous chapter: ${prevIndex + 1} of ${chapters.size}" }
                    }
                }

                AudiobookNotificationProvider.COMMAND_NEXT_CHAPTER -> {
                    if (currentChapter != null && chapters.isNotEmpty() && timeline != null) {
                        val nextIndex = (currentChapter.index + 1).coerceAtMost(chapters.size - 1)
                        val nextChapter = chapters[nextIndex]
                        val resolved = timeline.resolve(nextChapter.startTime)
                        p.seekTo(resolved.mediaItemIndex, resolved.positionInFileMs)
                        logger.debug { "Next chapter: ${nextIndex + 1} of ${chapters.size}" }
                    }
                }

                CustomActions.CYCLE_SPEED -> {
                    val currentSpeed = p.playbackParameters.speed
                    val newSpeed = CustomActions.getNextSpeed(currentSpeed)
                    p.setPlaybackSpeed(newSpeed)
                    playbackManager.onSpeedChanged(newSpeed)
                    logger.info {
                        "Speed cycled: ${CustomActions.formatSpeed(
                            currentSpeed,
                        )} -> ${CustomActions.formatSpeed(newSpeed)}"
                    }
                }
            }

            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    // ========== Voice Search Helpers ==========

    /**
     * Convert Android's EXTRA_MEDIA_FOCUS string to our MediaFocus enum.
     */
    private fun String.toMediaFocus(): MediaFocus? =
        when (this) {
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> MediaFocus.ARTIST
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> MediaFocus.ALBUM
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> MediaFocus.TITLE
            else -> MediaFocus.UNSPECIFIED
        }
}
