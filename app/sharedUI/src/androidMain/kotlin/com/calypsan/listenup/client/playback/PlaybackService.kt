package com.calypsan.listenup.client.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.calypsan.listenup.client.automotive.BrowseTree
import com.calypsan.listenup.client.automotive.BrowseTreeProvider
import com.calypsan.listenup.client.automotive.browseSignInEdges
import com.calypsan.listenup.client.di.activatePlaybackController
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.localization.SystemStrings
import com.calypsan.listenup.client.localization.SystemStringsHolder
import com.calypsan.listenup.client.playback.cast.CastPreparer
import com.calypsan.listenup.client.playback.cast.CastSessionController
import com.calypsan.listenup.client.voice.VoiceIntentResolver
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.android.inject

private val logger = KotlinLogging.logger {}

/**
 * Background playback service using Media3.
 *
 * Responsibilities:
 * - Manages ExoPlayer instance (built via [buildAudiobookExoPlayer])
 * - Exposes MediaSession for system integration (notification, lock screen, Bluetooth)
 * - Handles audio focus and survives Activity destruction for background playback
 * - Delegates progress/span bookkeeping and tiered idle timeouts to [PlaybackBookkeeper]
 * - Coordinates Local ↔ Cast swaps via [CastHandoffCoordinator]
 */
@OptIn(UnstableApi::class)
class PlaybackService :
    MediaLibraryService(),
    PlaybackTransport,
    PlaybackBookkeeperHost {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var chapterWindowPlayer: ChapterWindowPlayer? = null
    private var notificationProvider: AudiobookNotificationProvider? = null
    private var castSessionController: CastSessionController? = null

    /**
     * The transport state behind "was that play refused, or merely interrupted?".
     *
     * It used to be three loose fields on this class, which put the whole sequence — the part every
     * one of those bugs actually lived in — behind a service that cannot be instantiated in a test.
     * See [PlaybackRefusalTracker] for what it holds and why it is out on its own.
     *
     * Its [PlaybackRefusalTracker.isAudioSounding] is also what [transport] hands
     * [playbackTransitionFor] as `spanOpen`: "audio is sounding" and "a listening span is open" are
     * the same fact, and one writer is what keeps the two readings from disagreeing. The events the
     * transition ignores — either player reporting on a session the other one is driving — never
     * reach the tracker at all; [transport] is what routes them.
     */
    private val refusalTracker = PlaybackRefusalTracker()

    /** Which player is the transport; moved only by the cast hand-offs. See [ActiveTransport]. */
    private val transport = ActiveTransport(refusalTracker)

    // True while the session player is the cast player. Main-thread only (moved in the
    // cast handoffs, read by the idle-timer guard). The LocalPlayerListener is attached only
    // to the local ExoPlayer, so cast play/pause never reaches it — this flag is how the
    // idle timer learns to stand down while casting.
    private val casting: Boolean get() = transport.casting

    /**
     * The volume-boost gain stage, installed into the audio sink by [GainRenderersFactory] and
     * outliving individual books — it is a member of the player, not of a playback session.
     * [GainAudioProcessor.beginBook] is what scopes its loudness measurement to one book.
     */
    private val gainProcessor = GainAudioProcessor()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Inject dependencies
    private val playbackManager: PlaybackManager by inject()
    private val playbackStateWriter: PlaybackStateWriter by inject()
    private val reporter: PlaybackProgressReporter by inject()
    private val progressTracker: ProgressTracker by inject()
    private val listeningEventRecorder: ListeningEventRecorder by inject()
    private val loadedBookRepository: LoadedBookRepository by inject()
    private val errorHandler: PlaybackErrorHandler by inject()
    private val errorBus: ErrorBus by inject()
    private val tokenProvider: AndroidAudioTokenProvider by inject()
    private val sleepTimerManager: SleepTimerManager by inject()
    private val browseTreeProvider: BrowseTreeProvider by inject()
    private val authSession: AuthSession by inject()
    private val voiceIntentResolver: VoiceIntentResolver by inject()
    private val homeRepository: HomeRepository by inject()
    private val castPreparer: CastPreparer by inject()
    private val uriPermissionGranter: UriPermissionGranter by inject()
    private val systemStrings: SystemStringsHolder by inject()
    private val skipIntervals: SkipIntervalsHolder by inject()

    /** Offers a way back in when the platform refuses a background start. */
    private val refusalNotifier by lazy { PlaybackRefusalNotifier(this, systemStrings) }

    private val bookkeeper: PlaybackBookkeeper by lazy {
        PlaybackBookkeeper(
            host = this,
            serviceScope = serviceScope,
            transport = transport,
            gainProcessor = gainProcessor,
            playbackManager = playbackManager,
            playbackStateWriter = playbackStateWriter,
            progressTracker = progressTracker,
            listeningEventRecorder = listeningEventRecorder,
            sleepTimerManager = sleepTimerManager,
            refusalNotifier = refusalNotifier,
        )
    }

    /**
     * Play/pause bookkeeping for the Cast transport, and nothing else.
     *
     * Deliberately not the full [LocalPlayerListener]: that one's error handling acts on the *local*
     * ExoPlayer captured in its `onPlayerError`, so attaching it to the cast player would
     * recover the wrong player on a receiver-side failure.
     */
    private val castPlaybackListener: Player.Listener by lazy {
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) =
                bookkeeper.handleIsPlayingChanged(TransportSource.CAST, isPlaying)
        }
    }

    private val castHandoff: CastHandoffCoordinator by lazy {
        CastHandoffCoordinator(
            context = this,
            playbackManager = playbackManager,
            castPreparer = castPreparer,
            transport = transport,
            systemStrings = systemStrings,
            castPlaybackListener = castPlaybackListener,
            onEndListen = bookkeeper::endListen,
            onCancelIdleTimer = bookkeeper::cancelIdleTimer,
            onSaveCurrentPosition = bookkeeper::saveCurrentPosition,
        )
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

    override fun localPlayer(): ExoPlayer? = player

    /** [PlaybackTransport] view of [PlaybackBookkeeper.bookRelativePositionMs]. */
    override fun bookRelativePositionMs(): Long = bookkeeper.bookRelativePositionMs()

    /** Tells every controller the local player was released, so the app stops treating the engine as holding the book. */
    override fun announceIdleRelease() {
        mediaLibrarySession?.broadcastCustomCommand(SessionCommand(COMMAND_IDLE_RELEASED, Bundle.EMPTY), Bundle.EMPTY)
    }

    override fun stopService() {
        stopSelf()
    }

    companion object {
        /** One minute: how often the system card's time left can change. */
        private const val MS_PER_MINUTE = 60_000L
    }

    override fun onCreate() {
        super.onCreate()
        logger.info { "PlaybackService created" }

        refreshSystemStrings()
        followSkipIntervals()
        initializePlayer()
        initializeMediaSession()
        initializeCast()
        initializeNotificationProvider()
        bookkeeper.observeGain()
        observeSignInForBrowseRefresh()

        // Register callback for chapter changes to update notification
        playbackManager.onChapterChanged = { chapterInfo ->
            logger.debug { "Chapter changed: ${chapterInfo.title}" }
            // No underlying-player event fires for a chapter boundary crossed purely by elapsed
            // playback time — invalidate() is the explicit hook so connected controllers pick up
            // the new chapter window (see ChapterWindowPlayer's "Invalidation" KDoc).
            chapterWindowPlayer?.invalidate()
        }
        refreshCardTimeLeft()

        // Auto-rewind-on-resume actuator (#1220): PlaybackManagerImpl.setPlaying feeds every
        // Playing/Paused transition (from LocalPlayerListener below, via MediaControllerHolder)
        // into the reporter, which owns the pause-window/ladder decision and calls back here
        // with a relative rewind once one applies. The seek itself lives in AutoRewindSeeker,
        // which holds only the PlaybackTransport seam — see its KDoc for why aiming this at the
        // session player was a production bug, and why "unreachable" beats "documented".
        val autoRewindSeeker = AutoRewindSeeker(this) { playbackManager.currentTimeline.value }
        reporter.onAutoRewindSeek = { rewindMs ->
            autoRewindSeeker.seekBack(rewindMs)?.let { playbackManager.updatePosition(it) }
        }

        // The in-app MediaController is what feeds PlaybackManager its Playing/Paused, buffering,
        // speed and error transitions — and so the auto-rewind ladder above. It is no longer
        // acquired at Koin startup, so a session that starts with no activity (a media button,
        // Android Auto, a resumption request) must connect it here, or those transitions would go
        // unrecorded until someone opened the app. Idempotent: a no-op once MainActivity did it.
        getKoin().activatePlaybackController()
    }

    private fun initializePlayer() {
        val listener =
            LocalPlayerListener(
                localPlayer = { player },
                serviceScope = serviceScope,
                bookkeeper = bookkeeper,
                refusalTracker = refusalTracker,
                refusalNotifier = refusalNotifier,
                errorBus = errorBus,
                errorHandler = errorHandler,
                progressTracker = progressTracker,
                playbackManager = playbackManager,
                listeningEventRecorder = listeningEventRecorder,
            )
        player =
            buildAudiobookExoPlayer(
                context = this,
                tokenProvider = tokenProvider,
                gainProcessor = gainProcessor,
                listener = listener,
            )
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

        // Skip increments first, chapter window on top: the chapter wrapper routes
        // COMMAND_SEEK_BACK/SEEK_FORWARD straight down (they are not chapter-relative), which is
        // exactly where the configured-interval wrapper needs to sit. Not held as a field —
        // it owns nothing to release, and [activeTransportPlayer] must keep handing out the raw
        // player, whose coordinates every position calculation in this service speaks.
        val skipAware =
            SkipIntervalPlayer(
                player = activePlayer,
                forwardMs = { skipIntervals.forwardMs },
                backwardMs = { skipIntervals.backwardMs },
                speed = { activePlayer.playbackParameters.speed },
            )

        // System surfaces (Auto, notification, lock screen, Bluetooth) get the chapter-scoped
        // presentation wrapper, never the raw local player — see ChapterWindowPlayer's class KDoc.
        // In-app UI still reads PlaybackManager directly for its state (now fed by PlaybackBookkeeper's
        // raw-player poll), and in-app seeks ride the SEEK_TO_BOOK_POSITION custom command (see
        // SessionCustomCommandHandler) rather than a plain controller seek, which the wrapper would
        // reinterpret chapter-relatively. Plain play/pause/speed controller calls are
        // coordinate-agnostic and still go through the session unmodified.
        val wrapper =
            ChapterWindowPlayer(
                player = skipAware,
                chaptersProvider = { playbackManager.chapters.value },
                timelineProvider = { playbackManager.currentTimeline.value },
                strings = { systemStrings.current },
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
                    loadedBookRepository = loadedBookRepository,
                    serviceScope = serviceScope,
                    transport = this,
                    uriPermissionGranter = uriPermissionGranter,
                    strings = systemStrings,
                    skipIntervals = skipIntervals,
                ),
            )
        // ⛔ The other half of the Auto sign-in fix, and the half that is easy to miss because the
        // code reads fine without it. Android Auto is a *legacy* MediaBrowserCompat client, so an
        // error LibraryResult from onGetChildren is flattened to `sendResult(null)` before it
        // leaves Media3 — the typed SessionError's message, its sign-in label and its PendingIntent
        // are all discarded on that channel (MediaLibraryServiceLegacyStub.createMediaItemsTo-
        // BrowserItemsAsyncFunction). The head unit is simply told the request failed.
        //
        // They survive by a side channel instead: for legacy browsers only, Media3 replicates
        // ERROR_SESSION_AUTHENTICATION_EXPIRED into the platform PlaybackStateCompat
        // (MediaLibrarySessionImpl.maybeUpdateLegacyErrorState). But the default mode is
        // NON_FATAL, which merges the extras into an otherwise ordinary state — and with nothing
        // playing that state is STATE_NONE. Auto's sign-in template keys off STATE_ERROR, which
        // only the FATAL branch produces. So the default is a wall that renders as nothing at all.
        //
        // onGetLibraryRoot is exempt from replication by design (it is part of a legacy browser's
        // connection handshake), which is why the root must stay ungated and this must be set:
        // the two are complementary, not alternatives. The state self-clears on the next
        // successful library result, which is exactly what observeSignInForBrowseRefresh's
        // notifyChildrenChanged provokes.
        builder.setLibraryErrorReplicationMode(
            MediaLibrarySession.LIBRARY_ERROR_REPLICATION_MODE_FATAL,
        )
        if (sessionIntent != null) {
            builder.setSessionActivity(sessionIntent)
        }

        mediaLibrarySession = builder.build()
        serviceScope.launch { skipIntervals.changes.collect { pushMediaButtons() } }

        logger.info { "MediaLibrarySession initialized" }
    }

    /**
     * Re-presents the session once a minute while playing, so the system card's remaining time counts down.
     * No player event fires as a minute passes; a per-tick invalidate would rebuild every controller's state a few
     * times a second for a number that changes once a minute.
     */
    private fun refreshCardTimeLeft() {
        serviceScope.launch {
            combine(playbackManager.playing, playbackManager.currentPositionMs) { playing, positionMs ->
                if (playing) minutesLeftOnCard(positionMs) else null
            }.distinctUntilChanged()
                .collect { minutesLeft -> if (minutesLeft != null) chapterWindowPlayer?.invalidate() }
        }
    }

    /** The whole minutes the card's line shows as left in the chapter at [positionMs]. */
    private fun minutesLeftOnCard(positionMs: Long): Long =
        chapterWindowAt(positionMs, playbackManager.chapters.value, playbackManager.totalDurationMs.value)
            .remainingMs / MS_PER_MINUTE

    private fun initializeNotificationProvider() {
        notificationProvider =
            AudiobookNotificationProvider(
                context = this,
                skipIntervals = skipIntervals,
                scope = serviceScope,
                strings = systemStrings,
            )
        val provider = notificationProvider ?: return
        setMediaNotificationProvider(provider)
        logger.info { "Notification provider initialized" }
    }

    /**
     * Resolve the platform-surface strings from the shared catalog (#1246).
     *
     * Media3 asks for these from synchronous callbacks, so they are resolved once here into the
     * snapshot those callbacks read as a field. Until it lands — a window of milliseconds, but a
     * real one, because a car can connect the instant this service starts — every surface serves
     * [SystemStrings.ENGLISH_FALLBACK] rather than a blank label.
     */
    private fun refreshSystemStrings() {
        serviceScope.launch {
            systemStrings.refresh()
            pushMediaButtons()
        }
    }

    /**
     * Re-sends the custom media buttons to every connected controller that gets them.
     *
     * [ListenUpSessionCallback.onConnect] labels the buttons once, so without this the car, Bluetooth and the system
     * card keep "Back 30" after the listener sets 15 s, and keep the old language after a locale change. Any new
     * setting that changes a system-surface label must call this.
     */
    private fun pushMediaButtons() {
        val session = mediaLibrarySession ?: return
        mediaButtonUpdates(
            controllers = session.connectedControllers.map { it to session.classifyController(it) },
            backwardSec = skipIntervals.backwardSec,
            forwardSec = skipIntervals.forwardSec,
            words = systemStrings.current,
        ).forEach { (controller, buttons) -> session.setMediaButtonPreferences(controller, buttons) }
    }

    /**
     * Start tracking the user's configured skip intervals.
     *
     * Before [initializePlayer], so the first press a car can possibly issue already reads a
     * real value rather than the stock fallback. The read is Room-only, so this stays correct
     * with no server and no session.
     */
    private fun followSkipIntervals() {
        skipIntervals.follow(serviceScope)
    }

    /**
     * A locale change reaches a running service as a configuration change, not a restart, so the
     * snapshot is re-resolved here — otherwise the browse tree and the notification would keep
     * serving the locale that was current when playback started.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshSystemStrings()
    }

    /**
     * Re-query the Auto browse tree when the listener signs back in (#1245).
     *
     * A head unit that was handed the signed-out browse error caches it and has no reason of its
     * own to ask again — so signing in on the phone left the car showing a sign-in wall over a
     * library that was, by then, perfectly readable. `notifyChildrenChanged` is the signal that
     * closes that loop, and every statically-known node gets it: refreshing only the root would
     * leave a unit parked on By Series still staring at the stale error.
     *
     * The edge itself comes from [browseSignInEdges], which derives it from the same predicate the
     * browse callbacks gate on, so the refresh cannot disagree with the wall it is clearing.
     */
    private fun observeSignInForBrowseRefresh() {
        serviceScope.launch {
            authSession.authState.browseSignInEdges().collect {
                val session = mediaLibrarySession ?: return@collect
                logger.info { "Signed back in — refreshing the Auto browse tree" }
                BrowseTree.BROWSABLE_NODES.forEach { node ->
                    // Int.MAX_VALUE is Media3's documented "child count unknown" — the head unit
                    // re-queries onGetChildren for the real list, which is the whole point.
                    session.notifyChildrenChanged(node, Int.MAX_VALUE, null)
                }
            }
        }
    }

    /**
     * Initialize Cast if Google Play Services is present. On a de-Googled device
     * this is a no-op — no cast button, local playback unaffected (never stranded).
     */
    private fun initializeCast() {
        castSessionController =
            CastSessionController.createOrNull(
                context = this,
                onConnected = {
                    serviceScope.launch {
                        val controller = castSessionController ?: return@launch
                        val session = mediaLibrarySession ?: return@launch
                        val local = player ?: return@launch
                        castHandoff.handoffToCast(controller, session, local)
                    }
                },
                onDisconnected = {
                    serviceScope.launch {
                        val controller = castSessionController ?: return@launch
                        val session = mediaLibrarySession ?: return@launch
                        val local = player ?: return@launch
                        val wrapper = chapterWindowPlayer ?: return@launch
                        castHandoff.handoffToLocal(
                            controller = controller,
                            session = session,
                            local = local,
                            wrapper = wrapper,
                        )
                    }
                },
            )
        if (castSessionController != null) logger.info { "Cast initialized" }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaLibrarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Legitimately the session player, not activeTransportPlayer(): this only issues a
        // pause command (ForwardingSimpleBasePlayer forwards setPlayWhenReady verbatim to the
        // wrapped local player) and checks session liveness — it never reads a chapter-relative
        // position or index, so there's nothing here for the presentation wrapper to corrupt.
        val player = mediaLibrarySession?.player

        // Pause playback when user swipes app away — notification remains for resume.
        // Not while casting: the session player is then the *cast* player, so this would stop
        // audio on the receiver because someone tidied their recent-apps list.
        if (!casting) {
            player?.playWhenReady = false
        }

        // Always save position when the user swipes the app away — if the system
        // later kills the process, onDestroy may not get a chance to run.
        // Must be synchronous: saveCurrentPosition() is fire-and-forget and races
        // process death; saveCurrentPositionBlocking() completes before returning.
        bookkeeper.saveCurrentPositionBlocking()

        // Don't stop immediately — the notification stays so the listener can resume.
        // The timer is armed here rather than left to the pause above: Media3 delivers that
        // pause's onIsPlayingChanged asynchronously, so nothing has armed it yet.
        when (taskRemovedActionFor(hasPlayer = player != null, casting = casting)) {
            TaskRemovedAction.STOP_SERVICE -> {
                stopSelf()
            }

            TaskRemovedAction.ARM_IDLE_TIMER -> {
                bookkeeper.startIdleTimer(PlaybackBookkeeper.IDLE_TIMEOUT_SHORT, "task_removed")
            }

            TaskRemovedAction.KEEP_ALIVE -> {
                logger.debug { "Task removed while casting — leaving the session alone" }
            }
        }
    }

    override fun onDestroy() {
        logger.info { "PlaybackService destroying" }

        // Save position before releasing player — player.release() does not fire
        // onIsPlayingChanged, so without this the position would be lost.
        // Must be synchronous: saveCurrentPosition() is fire-and-forget and races
        // serviceScope.cancel() below; saveCurrentPositionBlocking() completes before returning.
        bookkeeper.saveCurrentPositionBlocking()
        bookkeeper.cancelJobs()

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

    /**
     * Apply restored playback speed to ExoPlayer.
     * Extracted to keep resolveMediaItems and onPlaybackResumption within complexity limits.
     */
    override fun applyResumeSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }
}
