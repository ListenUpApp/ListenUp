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
import com.calypsan.listenup.client.automotive.AutoBrowseErrors
import com.calypsan.listenup.client.automotive.BrowseTree
import com.calypsan.listenup.client.automotive.BrowseTreeProvider
import com.calypsan.listenup.client.automotive.CustomActions
import com.calypsan.listenup.client.automotive.browseNeedsSignIn
import com.calypsan.listenup.client.automotive.isLastPage
import com.calypsan.listenup.client.automotive.paginate
import com.calypsan.listenup.client.playback.cast.CastMediaItemFactory
import com.calypsan.listenup.client.playback.cast.CastPreparer
import com.calypsan.listenup.client.playback.cast.CastSessionController
import com.calypsan.listenup.client.playback.cast.CastSourceItem
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.client.domain.repository.AuthSession
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
import org.koin.android.ext.android.inject
import com.calypsan.listenup.client.core.DurationFormatter
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

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
class PlaybackService :
    MediaLibraryService(),
    PlaybackTransport {
    private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var chapterWindowPlayer: ChapterWindowPlayer? = null
    private var notificationProvider: AudiobookNotificationProvider? = null
    private var castSessionController: CastSessionController? = null

    // True while the session player is the cast player. Main-thread only (set in the
    // cast handoffs, read by the idle-timer guard). The PlayerListener is attached only
    // to the local ExoPlayer, so cast play/pause never reaches it — this flag is how the
    // idle timer learns to stand down while casting (see startIdleTimer).
    private var casting = false

    /**
     * Whether audio was actually sounding when the last transport change arrived.
     *
     * Read by [isPlaybackRefused] to separate a refused start from an ordinary interruption —
     * Media3 reports both with `PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS`.
     */
    private var wasPlaying = false

    /** Offers a way back in when the platform refuses a background start. */
    private val refusalNotifier by lazy { PlaybackRefusalNotifier(this) }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var idleJob: Job? = null
    private var positionUpdateJob: Job? = null
    private var uiPositionJob: Job? = null

    // Inject dependencies
    private val playbackManager: PlaybackManager by inject()
    private val playbackStateWriter: PlaybackStateWriter by inject()
    private val reporter: PlaybackProgressReporter by inject()
    private val progressTracker: ProgressTracker by inject()
    private val listeningEventRecorder: com.calypsan.listenup.client.playback.ListeningEventRecorder by inject()
    private val positionRepository: PlaybackPositionRepository by inject()
    private val errorHandler: PlaybackErrorHandler by inject()
    private val errorBus: ErrorBus by inject()
    private val tokenProvider: AndroidAudioTokenProvider by inject()
    private val sleepTimerManager: SleepTimerManager by inject()
    private val browseTreeProvider: BrowseTreeProvider by inject()
    private val authSession: AuthSession by inject()
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
     * Reads the active transport player — the local ExoPlayer normally, the cast
     * player while casting — so position recording follows wherever audio is playing.
     *
     * @return Book-relative position in milliseconds, or 0 if unavailable
     */
    private fun getBookRelativePosition(): Long {
        val player = activeTransportPlayer() ?: return 0L
        val timeline = playbackManager.currentTimeline.value ?: return player.currentPosition
        return timeline.toBookPosition(player.currentMediaItemIndex, player.currentPosition)
    }

    /**
     * The player actually producing audio right now — the cast player while [casting],
     * otherwise the raw local ExoPlayer.
     *
     * The session player (`mediaLibrarySession?.player`) is a presentation wrapper once local
     * playback is active: [ChapterWindowPlayer] re-presents the book's timeline as a single
     * chapter-scoped window for system surfaces (Auto, notification, lock screen, Bluetooth), so
     * its media-item index and positions are chapter-relative, not book-relative. Every internal
     * consumer of transport state — progress sync, listening-event recording, the sleep timer,
     * custom-command seek math — must read this instead of the session player, or it will
     * silently compute against the wrong coordinate space. Cast surfaces stay file-scoped
     * (pre-existing, tracked separately), so the cast player never needs this distinction.
     */
    override fun activeTransportPlayer(): Player? = if (casting) castSessionController?.castPlayer else player

    /** [PlaybackTransport] view of [getBookRelativePosition], which the rest of the service uses directly. */
    override fun bookRelativePositionMs(): Long = getBookRelativePosition()

    companion object {
        // Idle timeout tiers
        private val IDLE_TIMEOUT_SHORT = 30.minutes // After natural pause
        private val IDLE_TIMEOUT_LONG = 2.hours // After book completion
        private val IDLE_TIMEOUT_SLEEP = 5.minutes // After sleep timer fires

        // Position update interval
        private const val POSITION_UPDATE_INTERVAL = 30_000L // 30 seconds

        // UI-rate position poll interval — matches MediaControllerHolder's former poll cadence
        // (see startPositionUpdates' KDoc for why this now lives here instead).
        private const val POSITION_UI_UPDATE_INTERVAL = 250L

        // ExoPlayer's COMMAND_SEEK_BACK/SEEK_FORWARD increments — matches the in-app
        // 10s-back/30s-forward skip amounts (see initializePlayer's comment for why).
        private const val SEEK_BACK_INCREMENT_MS = 10_000L
        private const val SEEK_FORWARD_INCREMENT_MS = 30_000L
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
            // No underlying-player event fires for a chapter boundary crossed purely by elapsed
            // playback time — invalidate() is the explicit hook so connected controllers pick up
            // the new chapter window (see ChapterWindowPlayer's "Invalidation" KDoc).
            chapterWindowPlayer?.invalidate()
            updateNotificationForChapter(chapterInfo)
        }

        // Auto-rewind-on-resume actuator (#1220): PlaybackManagerImpl.setPlaying feeds every
        // Playing/Paused transition (from the Player.Listener below, via MediaControllerHolder)
        // into the reporter, which owns the pause-window/ladder decision and calls back here
        // with a relative rewind once one applies. Mirrors COMMAND_SKIP_BACK_30's book-relative
        // seek — routes to the active session player so it also seeks correctly while casting.
        reporter.onAutoRewindSeek = { rewindMs ->
            val p = mediaLibrarySession?.player ?: player
            if (p != null) {
                val timeline = playbackManager.currentTimeline.value
                val newPosition = (getBookRelativePosition() - rewindMs).coerceAtLeast(0)
                if (timeline != null) {
                    val resolved = timeline.resolve(newPosition)
                    p.seekTo(resolved.mediaItemIndex, resolved.positionInFileMs)
                } else {
                    p.seekTo((p.currentPosition - rewindMs).coerceAtLeast(0))
                }
                playbackManager.updatePosition(newPosition)
                logger.debug { "Auto-rewind on resume: backed up ${rewindMs}ms to ${newPosition}ms" }
            }
        }
    }

    private fun initializePlayer() {
        // Create OkHttp client with auth interceptor. Timeouts — and why they are all
        // finite — live in StallRecovery.kt.
        val okHttpClient =
            buildStreamingHttpClient(
                authInterceptor = tokenProvider.createInterceptor(),
                tokenAuthenticator = tokenProvider.createAuthenticator(),
            )

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
                // Match the in-app 10s-back/30s-forward skip amounts (NowPlayingViewModel's
                // defaults) rather than Media3's 5s/15s defaults, so head units/watches that
                // render COMMAND_SEEK_BACK/SEEK_FORWARD (car steering-wheel buttons, Wear tiles)
                // skip by the same amount the in-app buttons do.
                .setSeekBackIncrementMs(SEEK_BACK_INCREMENT_MS)
                .setSeekForwardIncrementMs(SEEK_FORWARD_INCREMENT_MS)
                // Backstop for a stall the socket-read timeout can't see — see StallRecovery.kt.
                // Media3's own default is ten minutes, which strands the listener.
                .setStuckBufferingDetectionTimeoutMs(STUCK_BUFFERING_TIMEOUT_MS)
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

        // System surfaces (Auto, notification, lock screen, Bluetooth) get the chapter-scoped
        // presentation wrapper, never the raw local player — see ChapterWindowPlayer's class KDoc.
        // In-app UI still reads PlaybackManager directly for its state (now fed by this service's
        // raw-player poll — see startPositionUpdates), and in-app seeks ride the
        // SEEK_TO_BOOK_POSITION custom command (see onCustomCommand) rather than a plain
        // controller seek, which the wrapper would reinterpret chapter-relatively. Plain
        // play/pause/speed controller calls are coordinate-agnostic and still go through the
        // session unmodified.
        val wrapper =
            ChapterWindowPlayer(
                player = activePlayer,
                chaptersProvider = { playbackManager.chapters.value },
                timelineProvider = { playbackManager.currentTimeline.value },
            )
        chapterWindowPlayer = wrapper

        val builder =
            MediaLibrarySession.Builder(
                this,
                wrapper,
                ListenUpSessionCallback(
                    context = applicationContext,
                    playbackManager = playbackManager,
                    browseTreeProvider = browseTreeProvider,
                    voiceIntentResolver = voiceIntentResolver,
                    homeRepository = homeRepository,
                    authSession = authSession,
                    positionRepository = positionRepository,
                    serviceScope = serviceScope,
                    transport = this,
                ),
            )
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
        val wrapper = chapterWindowPlayer ?: return
        // If the session isn't currently on the cast player, nothing to do (we never swapped).
        // (Was an identity check against `local` before ChapterWindowPlayer existed — the session
        // player is never `local` anymore even when not casting, since it's now the wrapper.)
        if (!casting) return
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
        session.player = wrapper
        casting = false // normal idle logic resumes now that the local player drives the session
        logger.info { "Swapped back to local at index=$index pos=$positionInItem" }
        saveCurrentPosition() // existing method — persists through the normal recorder
    }

    /**
     * Push the chapter subtitle to session extras when the chapter changes.
     *
     * Chapter title/track number/total count for system surfaces (Android Auto display,
     * Bluetooth metadata, the lock screen) now come from [ChapterWindowPlayer]'s window
     * `MediaItemData` — the single metadata source for the session player, kept in sync via
     * [ChapterWindowPlayer.invalidate] (called alongside this function in `onCreate`'s
     * `onChapterChanged` hook). This function's remaining job is the session-extras push:
     * `AudiobookNotificationProvider` builds the phone notification's subtitle straight from
     * `playbackManager.currentChapter` rather than session extras, but the extra is kept for any
     * other consumer relying on it.
     */
    private fun updateNotificationForChapter(chapterInfo: PlaybackManager.ChapterInfo) {
        val session = mediaLibrarySession ?: return

        val chapterText =
            if (chapterInfo.isGenericTitle) {
                "Chapter ${chapterInfo.index + 1} of ${chapterInfo.totalChapters}"
            } else {
                chapterInfo.title
            }

        val timeRemaining = DurationFormatter.hoursMinutesOrUnderMinute(chapterInfo.remainingMs.milliseconds)
        val displaySubtitle = "$chapterText • $timeRemaining left"

        session.setSessionExtras(Bundle().apply { putString("chapter_subtitle", displaySubtitle) })

        logger.debug {
            "Updated chapter subtitle: $chapterText (${chapterInfo.index + 1}/${chapterInfo.totalChapters})"
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaLibrarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Legitimately the session player, not activeTransportPlayer(): this only issues a
        // pause command (ForwardingSimpleBasePlayer forwards setPlayWhenReady verbatim to the
        // wrapped local player) and checks session liveness — it never reads a chapter-relative
        // position or index, so there's nothing here for the presentation wrapper to corrupt.
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
        uiPositionJob?.cancel()

        // Clear chapter change callback to avoid memory leaks
        playbackManager.onChapterChanged = null
        reporter.onAutoRewindSeek = null

        // Release session before player — the session holds a reference to the player.
        // player?.release() runs unconditionally so the native decoder is freed even
        // when mediaLibrarySession was never built or was already null.
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        chapterWindowPlayer = null
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
                    // Gate + read off the TRANSPORT player — the cast player while casting, the
                    // local ExoPlayer otherwise. Never the session player: once ChapterWindowPlayer
                    // presents the session, its positions are chapter-relative and would corrupt
                    // this data. The local player is paused during a cast (its audio focus is
                    // released), so gating on player.isPlaying froze all periodic persistence for
                    // the whole cast session: position stopped propagating to other devices and a
                    // battery-death mid-cast lost the session + dropped the span.
                    // getBookRelativePosition() already reads the transport player, so only the
                    // gate had to follow it here.
                    val transportPlayer = activeTransportPlayer() ?: break
                    val bookId = currentBookId ?: break

                    if (transportPlayer.isPlaying) {
                        val positionMs = getBookRelativePosition()
                        progressTracker.onPositionUpdate(
                            bookId = bookId,
                            positionMs = positionMs,
                            speed = transportPlayer.playbackParameters.speed,
                        )
                        listeningEventRecorder.onPeriodicTick(positionMs = positionMs)
                    }
                }
            }

        // Second, faster poll driving in-app UI position display. This used to be
        // MediaControllerHolder's job — it polled the MediaController it held on a 250ms loop —
        // but the session player is now ChapterWindowPlayer, the chapter-scoped presentation
        // wrapper (see activeTransportPlayer's KDoc), so a MediaController's coordinates are
        // chapter-relative and can no longer feed PlaybackStateWriter.updatePositionFromMediaItem
        // (it interprets its arguments as file coordinates). The service reads the raw transport
        // player instead, which was never re-presented. Running it here rather than in-app also
        // means the notification's chapter rollover (driven by playbackManager.onChapterChanged)
        // now advances even when no UI client is bound, since the service itself keeps polling.
        uiPositionJob?.cancel()
        uiPositionJob =
            serviceScope.launch {
                while (isActive) {
                    delay(POSITION_UI_UPDATE_INTERVAL)
                    val p = activeTransportPlayer() ?: break
                    if (p.isPlaying) {
                        playbackStateWriter.updatePositionFromMediaItem(p.currentMediaItemIndex, p.currentPosition)
                    }
                }
            }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        uiPositionJob?.cancel()
        uiPositionJob = null
    }

    /**
     * Apply restored playback speed to ExoPlayer.
     * Extracted to keep resolveMediaItems and onPlaybackResumption within complexity limits.
     */
    override fun applyResumeSpeed(speed: Float) {
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

            // Reaching READY means whatever we last recovered from is behind us — refill the
            // recovery budget so it is spent per incident, not per listening session.
            if (playbackState == Player.STATE_READY) {
                errorHandler.onPlaybackHealthy()
            }

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

        /**
         * Detects a play request the platform refused outright, as opposed to one interrupted
         * partway. See [isPlaybackRefused] for why the two need telling apart.
         */
        override fun onPlayWhenReadyChanged(
            playWhenReady: Boolean,
            reason: Int,
        ) {
            if (isPlaybackRefused(playWhenReady, reason, wasPlaying)) {
                logger.warn {
                    "Playback refused: audio focus denied while backgrounded. " +
                        "Check `adb shell dumpsys audio` for the AudioHardening entry."
                }
                val refusal =
                    PlaybackError.BlockedInBackground(
                        debugInfo = "playWhenReady=false reason=AUDIO_FOCUS_LOSS before playback started",
                    )
                // The bus reaches the UI only when the app is already open — which is precisely
                // when this can't happen. The notification is the path that actually reaches a
                // listener staring at a lock-screen button that did nothing.
                errorBus.emit(refusal)
                refusalNotifier.notifyRefused(refusal)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            logger.debug { "Is playing: $isPlaying" }

            wasPlaying = isPlaying

            val bookId = currentBookId
            val player = player

            if (isPlaying) {
                // Audio is sounding, so any refusal notice is stale — clear it rather than leave
                // the listener with a notification telling them to fix something already fixed.
                refusalNotifier.clearRefusal()
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
            } else if (casting) {
                // The local player just paused because we handed off to the cast player (it releases
                // local audio focus). This is NOT a real pause: the cast player is still playing. Do
                // NOT stop the periodic loop (it now gates on the session/cast player and must keep
                // persisting cast progress), do NOT finalize the span, and do NOT arm an idle timer
                // (already suppressed by the casting guard). The session's real pause/finalize is
                // handled when it stops on the cast device or on handoff back to local.
                logger.debug { "Local player paused for cast handoff — keeping periodic recording alive" }
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

        /**
         * A user seek splits the listening span: finalize the pre-seek span at the old book-relative
         * position and open a fresh one at the new position (see [ListeningEventRecorder.onSeek]).
         * Without this a seek would leave the open span to be extended across the jump on the next
         * heartbeat, fabricating content coverage (e.g. a seek from 0:12:00 to 5:00:00 would count
         * the whole ~5 h as listened) that corrupts the books-finished / coverage-derived stats.
         *
         * Only [Player.DISCONTINUITY_REASON_SEEK] discontinuities are user seeks; auto transitions
         * and period boundaries are ignored. Positions are converted to book-relative via the active
         * [PlaybackManager.currentTimeline]; the raw file-relative [Player.PositionInfo.positionMs]
         * would misplace the split on a multi-file book.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            currentBookId ?: return
            val timeline = playbackManager.currentTimeline.value
            val beforeMs =
                timeline?.toBookPosition(oldPosition.mediaItemIndex, oldPosition.positionMs)
                    ?: oldPosition.positionMs
            val afterMs =
                timeline?.toBookPosition(newPosition.mediaItemIndex, newPosition.positionMs)
                    ?: newPosition.positionMs
            logger.debug { "Seek discontinuity: $beforeMs -> $afterMs (book-relative)" }
            serviceScope.launch {
                listeningEventRecorder.onSeek(positionBeforeSeek = beforeMs, positionAfterSeek = afterMs)
            }
        }
    }
}
