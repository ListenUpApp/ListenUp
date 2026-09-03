package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackState
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.playback.fadeOutAndPause
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.appCoroutineExceptionHandler
import com.calypsan.listenup.web.playback.HtmlAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.calypsan.listenup.client.presentation.nowplaying.skipTargetMs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.Koin

/**
 * An open transport-bar state stream, the gestures it accepts, and the teardown for it.
 *
 * The same arrangement [com.calypsan.listenup.web.features.library.LibrarySession] makes, for the
 * same reason: a browser has no `ViewModelStore` to hand a lifetime to, so the composition opens
 * a session and closes it when it goes away.
 */
class PlaybackSession(
    val state: StateFlow<TransportState?>,
    val error: StateFlow<String?>,
    /**
     * The book's chapter marks, or empty when it has none.
     *
     * A flow of its own rather than a field on [TransportState]: chapters change once per book,
     * and [TransportState] is rebuilt on every position tick. Folding a list of several hundred
     * into that combine would rebuild it a few times a second to say the same thing.
     */
    val chapters: StateFlow<List<TransportChapter>>,
    /** Which chapter the playhead is in, or null with no book or no marks. */
    val currentChapterIndex: StateFlow<Int?>,
    /**
     * Who wrote the playing book, what series it is in, and which cover to draw — or null when
     * nothing is playing, or when the book is not in this browser's mirror yet.
     *
     * Separate from [state] for the same reason [chapters] is: this changes once per book, and
     * [state] is rebuilt on every position tick.
     */
    val nowPlaying: StateFlow<NowPlayingBook?>,
    /** Whether a sleep timer is running, and how much of it is left. */
    val sleepTimer: StateFlow<SleepTimerState>,
    /** The listener's own default rate, from Settings — what a reset goes back to. */
    val defaultSpeed: StateFlow<Float>,
    /** This book's volume boost, in dB. 0 is no boost. */
    val volumeBoostDb: StateFlow<Float>,
    /** The listener's own default boost, from Settings — what a boost reset goes back to. */
    val defaultBoostDb: StateFlow<Float>,
    /** Whether a boost was asked for and this browser would not give it. */
    val boostUnavailable: StateFlow<Boolean>,
    val onPlayPause: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onPlayBook: (BookId) -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit,
    val onSetSpeed: (Float) -> Unit,
    val onResetSpeed: () -> Unit,
    val onSetBoost: (Float) -> Unit,
    val onResetBoost: () -> Unit,
    val onSeekToChapter: (Int) -> Unit,
    val onSetSleepTimer: (SleepTimerMode) -> Unit,
    val onCancelSleepTimer: () -> Unit,
    val onExtendSleepTimer: (Int) -> Unit,
    val onDismissError: () -> Unit,
    val close: () -> Unit,
)

/**
 * The playing book's identity, as the expanded player needs it.
 *
 * Read from Room via `BookRepository.observeBookDetail`, not from the prepare that started the
 * book. `PlaybackManager.PrepareResult` carries a title, an author name and a series NAME — but no
 * ids, and no cover hash. Without ids the panel could print "Brandon Sanderson" and not take you
 * to him, and without the hash its cover would be served from a year-long `immutable` cache that
 * no re-cover could bust (see `coverUrl`). Room already holds all of it, is the client's single
 * source of truth, and keeps answering when the network does not.
 */
class NowPlayingBook(
    val bookId: String,
    val coverHash: String?,
    /** Every author, with the id each one links to. Empty when the book names none. */
    val authors: List<PlayerLink>,
    /** Narrator names, joined as the book lists them. Empty when unknown. */
    val narrators: String,
    /** The book's series memberships, each with the position this book holds in it. */
    val series: List<PlayerSeriesLink>,
)

/** A name and the id it navigates to. */
class PlayerLink(
    val id: String,
    val name: String,
)

/** A series membership: where it goes, what it is called, and this book's place in it. */
class PlayerSeriesLink(
    val id: String,
    val name: String,
    /** `"1"`, `"1.5"`, or null when the series does not number this book. */
    val sequenceLabel: String?,
)

/**
 * One chapter mark, as the player needs it.
 *
 * Deliberately not `bookdetail`'s `WebChapter`, which carries a 1-based `number` and a duration
 * because it backs an editing table with multi-select and an inspector. A listener jumping mid-book
 * needs a name and somewhere to land, and nothing else — and borrowing the editor's type would tie
 * the player to a surface it has no other reason to know about.
 */
class TransportChapter(
    val title: String,
    val startMs: Long,
)

/**
 * How the shell gets its transport state. Production drives the real player out of the client
 * graph ([graphPlayback]); specs hand over a fixed state instead, so the bar's layout can be
 * driven without a decoder behind it.
 */
typealias OpenPlayback = () -> PlaybackSession

/** The production source: the browser player, its controller, and the shared [PlaybackManager]. */
fun graphPlayback(koin: Koin): OpenPlayback =
    {
        LivePlayback(
            playbackManager = koin.get(),
            playbackController = koin.get(),
            audioPlayer = koin.get(),
            playbackPreferences = koin.get(),
            bookRepository = koin.get(),
        ).asSession()
    }

/**
 * A session over a state that never changes — the shape specs pass in place of the graph, so a
 * spec about navigation or layout is not forced to wire up a decoder. `null` draws no bar.
 *
 * Deliberately NOT a default value on [com.calypsan.listenup.web.WebAppRoot]'s parameter. Its
 * `onPlayBook` is inert, and Book Detail's Play button is gated on the book's own `canPlay` rather
 * than on whether playback is wired — so a production call site that forgot the argument would
 * render a real, dead Play button and compile clean. Making every caller name its source turns
 * that into a compile error.
 */
fun fixedPlayback(
    state: TransportState? = null,
    error: String? = null,
    chapters: List<TransportChapter> = emptyList(),
    currentChapterIndex: Int? = null,
    nowPlaying: NowPlayingBook? = null,
    sleepTimer: SleepTimerState = SleepTimerState.Inactive,
    defaultSpeed: Float = PlaybackPreferences.DEFAULT_PLAYBACK_SPEED,
    volumeBoostDb: Float = PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB,
    defaultBoostDb: Float = PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB,
    boostUnavailable: Boolean = false,
): OpenPlayback =
    {
        PlaybackSession(
            state = MutableStateFlow(state),
            error = MutableStateFlow(error),
            chapters = MutableStateFlow(chapters),
            currentChapterIndex = MutableStateFlow(currentChapterIndex),
            nowPlaying = MutableStateFlow(nowPlaying),
            sleepTimer = MutableStateFlow(sleepTimer),
            defaultSpeed = MutableStateFlow(defaultSpeed),
            volumeBoostDb = MutableStateFlow(volumeBoostDb),
            defaultBoostDb = MutableStateFlow(defaultBoostDb),
            boostUnavailable = MutableStateFlow(boostUnavailable),
            onPlayPause = {},
            onSeek = {},
            onPlayBook = {},
            onSkipBack = {},
            onSkipForward = {},
            onSetSpeed = {},
            onResetSpeed = {},
            onSetBoost = {},
            onResetBoost = {},
            onSeekToChapter = {},
            onSetSleepTimer = {},
            onCancelSleepTimer = {},
            onExtendSleepTimer = {},
            onDismissError = {},
            close = {},
        )
    }

/**
 * Everything the transport bar needs, over the real player.
 *
 * ## Where `play()` lives, and why it is here
 *
 * Nothing on the built-in-player lane starts audio. [PlaybackManager.startPlayback] loads the
 * segments, sets the speed and seeks — and stops; neither `HtmlAudioPlayer.load` nor Desktop's
 * `FfmpegAudioPlayer.load` auto-plays. Only Android's controller finishes with an explicit
 * `play()`. So the call has to be added, and there are two places to add it: the controller's
 * `startPlayback` (mirroring Android) or the gesture that asked for audio (here).
 *
 * It is here, because *where* is inseparable from *when*. [PlaybackManager.prepareForPlayback] is
 * an RPC round-trip. A `play()` after it runs several suspension points away from the click that
 * caused it: Chrome's *sticky* activation survives that, iOS Safari's *transient* activation does
 * not — so the controller-side version would work in Chromium and be refused on a real iPhone,
 * with [HtmlAudioPlayer.playRefusalMessage] correctly reporting a failure nobody could act on.
 *
 * [playBook] therefore spends the activation inside the click's own task, via
 * [HtmlAudioPlayer.primeForPlayback], and lets the load that arrives later honour the intent it
 * recorded. [playPause] needs no such care — it is synchronous from click to `play()`.
 */
internal class LivePlayback(
    private val playbackManager: PlaybackManager,
    private val playbackController: PlaybackController,
    private val audioPlayer: HtmlAudioPlayer,
    private val playbackPreferences: PlaybackPreferences,
    private val bookRepository: BookRepository,
    // appCoroutineExceptionHandler, not a bare scope: [playBook] deliberately lets a failed
    // prepare propagate rather than swallowing it, so without a handler the only report of "your
    // book did not start" would be an unhandled rejection in the console.
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main + appCoroutineExceptionHandler),
) {
    /**
     * The playing book's title, taken from the prepare that started it.
     *
     * The [PlaybackManager] read surface carries an id and a timeline but no title, and the web
     * client has no second place a title could come from that would not be a second query for
     * something the prepare already returned.
     */
    private val title = MutableStateFlow<String?>(null)

    private var preparingJob: Job? = null

    /**
     * Which [playBook] call currently owns the primed intent.
     *
     * A monotonic counter rather than the book id, because the id is not unique over time: an
     * A → B → A burst of taps can leave job A's deferred `finally` running while the window names
     * `A` again — the third tap's window — and an id comparison would then withdraw the *new*
     * prime. A generation is only ever equal to itself.
     */
    private var playGeneration: Int = 0

    /**
     * What went wrong, in words the listener can read, or null.
     *
     * [PlaybackManager.playbackError] is the one place both kinds of failure land: everything
     * [PlaybackManager.reportError] is told about (a prepare that returned nothing or threw), and
     * the player's own [PlaybackState.Error] — a codec the browser cannot decode, a dropped
     * connection, a fatal hls.js teardown — which `PlaybackManagerImpl` folds into the same flow
     * while it observes the player. Reading anywhere else would catch one half and miss the other.
     */
    val error: StateFlow<String?> =
        playbackManager.playbackError
            .map { it?.message }
            .stateIn(scope, SharingStarted.Eagerly, playbackManager.playbackError.value?.message)

    /**
     * How far each skip control moves, straight from the listener's own settings.
     *
     * Read here rather than baked into the bar so web and the native clients skip by the same
     * amount: these are the very flows `NowPlayingViewModel` reads, and they are synced, so a
     * change made on a phone reaches this tab.
     */
    private val skipBackwardSec: StateFlow<Int> =
        playbackPreferences
            .observeDefaultSkipBackwardSec()
            .stateIn(scope, SharingStarted.Eagerly, PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC)

    /**
     * The listener's default rate, straight from their synced settings — what the picker's reset
     * button *names*, and what decides whether it is offered at all.
     *
     * ⛔ Read for display only. [resetSpeed] deliberately does NOT use this value: `stateIn` seeds
     * `DEFAULT_PLAYBACK_SPEED` and serves it until the first upstream emission arrives, so a reset
     * that read `.value` would quietly send a listener whose default is 1.25 back to 1 — on the
     * one control that promises not to overrule them. A label that is briefly stale corrects
     * itself on the next emission; a reset that fired on a stale value cannot be taken back.
     */
    val defaultSpeed: StateFlow<Float> =
        playbackPreferences
            .observeDefaultPlaybackSpeed()
            .stateIn(scope, SharingStarted.Eagerly, PlaybackPreferences.DEFAULT_PLAYBACK_SPEED)

    private val skipForwardSec: StateFlow<Int> =
        playbackPreferences
            .observeDefaultSkipForwardSec()
            .stateIn(scope, SharingStarted.Eagerly, PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC)

    /** This book's volume boost, straight from the manager that owns it. */
    val volumeBoostDb: StateFlow<Float> get() = playbackManager.volumeBoostDb

    /**
     * The listener's default boost, from their synced settings — what a boost reset *names*.
     *
     * ⛔ Read for display only, exactly like [defaultSpeed]: `stateIn` serves its seed until the
     * first upstream emission, so [resetBoost] takes the suspend read instead.
     */
    val defaultBoostDb: StateFlow<Float> =
        playbackPreferences
            .observeDefaultVolumeBoostDb()
            .stateIn(scope, SharingStarted.Eagerly, PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB)

    /**
     * Whether a boost was asked for and this browser would not give it.
     *
     * Surfaced rather than swallowed because the failure is invisible otherwise — the book plays
     * on, at the volume the listener just tried to change. See
     * [com.calypsan.listenup.web.playback.WebGainStage].
     */
    val boostUnavailable: StateFlow<Boolean> get() = audioPlayer.boostUnavailable

    /**
     * Position and duration as one value, because [combine] is only typed to five flows and the
     * bar needs seven. Paired rather than any other two: they change on the same tick.
     */
    private val timeline: Flow<Pair<Long, Long>> =
        combine(playbackManager.currentPositionMs, playbackManager.totalDurationMs) { position, duration ->
            position to duration
        }

    /** The three values the transport controls display, folded for the same [combine] arity reason. */
    private val controls: Flow<Triple<Float, Int, Int>> =
        combine(playbackManager.playbackSpeed, skipBackwardSec, skipForwardSec) { speed, back, forward ->
            Triple(speed, back, forward)
        }

    /**
     * The book's marks, mapped once per book rather than per tick.
     *
     * `PlaybackManager.chapters` already emits only when the book changes, so this rides that
     * cadence instead of being folded into [state]'s combine.
     */
    val chapters: StateFlow<List<TransportChapter>> =
        playbackManager.chapters
            .map { marks -> marks.map { TransportChapter(title = it.title, startMs = it.startTime) } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Which chapter the playhead is in.
     *
     * Read from `PlaybackManager.currentChapter` rather than derived here by scanning the marks
     * against the position: that flow is what the native clients highlight from, and computing it
     * a second way is how the browser and the phone come to disagree about which chapter is
     * playing at a boundary.
     */
    val currentChapterIndex: StateFlow<Int?> =
        playbackManager.currentChapter
            .map { it?.index }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The playing book, as Room holds it.
     *
     * `flatMapLatest` rather than a `combine`: this must follow whichever book is playing, and the
     * upstream is a different query per book. Emits null the moment nothing is playing, so the
     * panel cannot go on showing the last book's cover under the next book's title.
     *
     * A book the mirror has not seen yet resolves to null rather than to a half-filled shell — the
     * panel then shows what [state] knows (title, position) and simply offers no links, which is
     * true, instead of offering ones that go nowhere.
     */
    val nowPlaying: StateFlow<NowPlayingBook?> =
        playbackManager.currentBookId
            .flatMapLatest { bookId ->
                if (bookId == null) flowOf(null) else bookRepository.observeBookDetail(bookId.value)
            }.map { detail -> detail?.toNowPlaying() }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The listener's sleep timer — the shared one, not a browser copy of it.
     *
     * [SleepTimerManager] is plain commonMain with no player, no platform and its own test suite:
     * it counts down, it decides when a chapter boundary should end the session, and it announces
     * that on [SleepTimerManager.sleepEvent]. None of that is anything a browser needs to do
     * differently, so web uses the same object the phone does rather than writing a second timer
     * that would have to be kept honest against it.
     *
     * Constructed here on [scope] rather than bound in the Koin graph, because a sleep timer's
     * natural lifetime is the listening session. A graph-scoped singleton would outlive [close] —
     * so a sign-out mid-countdown would leave a timer running that eventually fired a fade at a
     * player that had already been released.
     */
    private val sleepTimerManager = SleepTimerManager(scope)

    /** Whether a timer is running, and how much of it is left. */
    val sleepTimer: StateFlow<SleepTimerState> get() = sleepTimerManager.state

    init {
        // Loudness gain — the book's normalization plus the listener's boost, already combined by
        // `VolumeGain.effectiveGainDb`. The same flow Android's `PlaybackService` collects into its
        // `GainAudioProcessor`, so a browser and a phone amplify a book by the same number rather
        // than by two independently-derived ones.
        //
        // Combined with the player's own state, and NOT because the state is wanted: the two
        // arrive out of order. `PlaybackManagerImpl` publishes `effectiveGainDb` inside
        // `prepareForPlayback`, which is *before* the `startPlayback` that gives the element a
        // source — and `WebGainStage` cannot route an element with nothing loaded. The state
        // change that follows the load is what re-applies the gain to a book that now has audio.
        // Re-applying is idempotent: an attached stage just rewrites its node.
        scope.launch {
            combine(playbackManager.effectiveGainDb, playbackManager.playbackState) { db, _ -> db }
                .collect { db -> audioPlayer.setGainDb(db) }
        }

        // End-of-chapter mode has no clock to watch; it waits to be told a chapter turned over.
        // The same feed `NowPlayingViewModel` gives it, deduped in the flow rather than against a
        // private var, so a position tick inside one chapter says nothing.
        scope.launch {
            currentChapterIndex
                .filterNotNull()
                .distinctUntilChanged()
                .collect { index -> sleepTimerManager.onChapterChanged(index) }
        }

        // The timer only announces that time is up; performing it belongs to whoever holds the
        // player. `finally` rather than a call per branch: leaving the state stuck in FadingOut
        // would show a countdown that never resolves and refuse every later timer, so the reset
        // has to survive a throwing fade and a cancelled scope alike.
        scope.launch {
            sleepTimerManager.sleepEvent.collect {
                try {
                    fadeOutAndPause(playbackController)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    console.warn("Sleep fade failed; resetting the timer to Inactive: ${e.message}")
                } finally {
                    sleepTimerManager.onFadeCompleted()
                }
            }
        }
    }

    val state: StateFlow<TransportState?> =
        combine(
            playbackManager.currentBookId,
            title,
            playbackManager.isPlaying,
            timeline,
            controls,
        ) { bookId, bookTitle, isPlaying, (positionMs, durationMs), (speed, backSec, forwardSec) ->
            if (bookId == null || bookTitle == null) {
                null
            } else {
                TransportState(
                    title = bookTitle,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    speed = speed,
                    skipBackSec = backSec,
                    skipForwardSec = forwardSec,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Start [bookId] from cold.
     *
     * Mirrors `NowPlayingViewModel.playBook` — a repeat tap for the same book is swallowed, a tap
     * for a different one supersedes, and the pending-play window always closes in a `finally`.
     * That VM is not reachable from a browser: `playbackPresentationModule`, which binds it, is
     * offered only through `Koin.android.kt` and `Koin.jvm.kt` — `Koin.js.kt` has no equivalent,
     * and giving it one would mean binding a download repository and a bottom-sheet expansion
     * state to a client that has neither. So its shape is followed here rather than borrowed.
     * (Its collaborators are another matter: [sleepTimerManager] is the very same class.)
     *
     * Everything before the `launch` runs in the click's own task, which is the whole point: the
     * [HtmlAudioPlayer.primeForPlayback] on the fifth line is the `play()` this lane was missing,
     * and it happens while the browser still considers the user to have asked for audio.
     *
     * ## Withdrawing a prime that never lands
     *
     * A prime is a standing instruction to the player: *the next thing you load, play*. It is not
     * scoped to this call, and [HtmlAudioPlayer] is a Koin singleton that outlives every session —
     * so a prepare that fails, throws, or is cancelled must take the instruction back, or the next
     * book to reach `load()` for any reason at all starts playing on the strength of a tap that
     * went nowhere. The `finally` does that for every exit that did not *complete*
     * [PlaybackController.startPlayback] — `reachedPlayback` is set only after it returns — with
     * cancellation and a throwing prepare both unwinding through it.
     *
     * Both halves of the `finally` are guarded by the same [playGeneration] check for the same
     * reason `NowPlayingViewModel.playBook` guards its own `finally`: when a tap for a *different*
     * book supersedes this one, the cancelled job's `finally` runs *after* the new job has primed.
     * An unguarded withdrawal there would cancel the new book's intent and leave it silent —
     * trading this bug for a subtler copy of it.
     *
     * [CoroutineStart.UNDISPATCHED] is what makes that `finally` reachable at all. A coroutine
     * cancelled before its first dispatch never enters its `try`, so a plain `launch` here loses
     * the withdrawal for the narrowest and most likely cancellation of all — [close] called in the
     * same tick as the tap, which is what a sign-out mid-prepare looks like. Starting undispatched
     * runs the body in the click's own task up to the prepare's first suspension, so the `try` is
     * always entered and cancellation always unwinds through it. (It also puts the RPC's first
     * leg inside the gesture, which is in the spirit of the rest of this method.)
     */
    fun playBook(bookId: BookId) {
        if (playbackManager.preparingBookId.value == bookId) return
        // Asking to play the book that is already loaded is a resume, never a restart. Falling
        // through would prime — which unloads the audio mid-sentence, tears down hls.js and
        // re-runs the whole prepare — only to resume from the *persisted* position, written every
        // ten seconds. A tap on Resume would cost a drop-out and up to ten seconds of rewind.
        if (playbackManager.currentBookId.value == bookId &&
            playbackManager.playbackState.value != PlaybackState.Idle
        ) {
            playbackManager.clearError()
            // Unconditional, deliberately not gated on `isPlaying`: that flow is mirrored from the
            // player one dispatch behind, so a gate would sometimes read stale and drop the resume
            // entirely. `play()` on an element that is already playing is a no-op, which is the
            // right answer for a Play button pressed on the book it names.
            playbackController.play()
            return
        }
        preparingJob?.cancel()
        val generation = ++playGeneration
        playbackManager.markPreparing(bookId)
        playbackManager.clearError()
        audioPlayer.primeForPlayback()
        // The bar must not keep naming the book the prime just unloaded: its position and duration
        // are already zeroed, so leaving the old title standing would report a real book parked at
        // 0:00. Null hides the bar until the new title arrives, which is the truth of the moment —
        // nothing is loaded.
        title.value = null
        preparingJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var reachedPlayback = false
                try {
                    val result = playbackManager.prepareForPlayback(bookId)
                    if (result == null) {
                        playbackManager.reportError(PREPARE_FAILED, isRecoverable = true)
                        return@launch
                    }
                    title.value = result.bookTitle
                    playbackManager.activateBook(bookId)
                    playbackController.startPlayback(result)
                    reachedPlayback = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Without this the listener gets nothing at all: the handler on [scope] turns
                    // an escaping failure into a console line, which is not a place anyone looks.
                    playbackManager.reportError(PREPARE_FAILED, isRecoverable = true)
                    throw e
                } finally {
                    if (generation == playGeneration) {
                        if (!reachedPlayback) audioPlayer.pause()
                        playbackManager.clearPreparing()
                    }
                }
            }
    }

    /** Clear a reported failure once the listener has seen it. */
    fun dismissError() {
        playbackManager.clearError()
    }

    /** Resume or pause what is loaded — synchronous, so the `play()` lands inside the click. */
    fun playPause() {
        if (playbackManager.isPlaying.value) {
            playbackController.pause()
        } else {
            playbackManager.clearError()
            playbackController.play()
        }
    }

    /**
     * Move to a book-relative position.
     *
     * The manager is told as well as the player because a seek while paused produces no position
     * tick — without this the scrubber would spring back under the listener's finger.
     */
    fun seek(positionMs: Long) {
        playbackController.seekTo(positionMs)
        playbackManager.updatePosition(positionMs)
    }

    /**
     * Move back by the listener's configured interval.
     *
     * The arithmetic is [skipTargetMs] — shared with `NowPlayingViewModel` rather than restated —
     * so a browser skip and a phone skip land in the same place, including the speed scaling that
     * makes the gesture worth the same amount of *listening* at any rate.
     *
     * Guarded on a loaded timeline for the reason that function documents: with no book there is
     * no duration to clamp against.
     */
    fun skipBack() = skipBy(seconds = skipBackwardSec.value, forward = false)

    /** Move forward by the listener's configured interval. See [skipBack]. */
    fun skipForward() = skipBy(seconds = skipForwardSec.value, forward = true)

    private fun skipBy(
        seconds: Int,
        forward: Boolean,
    ) {
        if (playbackManager.currentTimeline.value == null) return
        val target =
            skipTargetMs(
                currentPositionMs = playbackManager.currentPositionMs.value,
                seconds = seconds,
                speed = playbackManager.playbackSpeed.value,
                totalDurationMs = playbackManager.totalDurationMs.value,
                forward = forward,
            )
        playbackController.seekTo(target)
        // Same follow-up NowPlayingViewModel makes: the element reports its new position on its own
        // schedule, and a paused listener pressing skip would otherwise watch the label sit still.
        playbackManager.updatePosition(target)
    }

    /**
     * Play at [speed] from now on, and remember that this book is read at that rate.
     *
     * Both halves matter: the controller is what the `<audio>` element actually obeys, and
     * [PlaybackManager.onSpeedChanged] is what records the choice against this book, so it survives
     * the next time it is opened. `NowPlayingViewModel.setSpeed` makes the same pair of calls.
     */
    fun setSpeed(speed: Float) {
        playbackController.setPlaybackSpeed(speed)
        playbackManager.onSpeedChanged(speed)
    }

    /**
     * Go back to the listener's own default rate, and stop treating this book as an exception.
     *
     * [PlaybackManager.onSpeedReset] rather than `onSpeedChanged` with the same number: the two
     * differ in what they record. Setting 1× explicitly says "this book is read at 1×" and pins it
     * there; resetting says "this book has no opinion", so a later change to the default in
     * Settings reaches it. Mirrors `NowPlayingViewModel.resetSpeedToDefault`.
     */
    fun resetSpeed() {
        scope.launch {
            // The suspend read, not [defaultSpeed]`.value` — see the ⛔ on that flow.
            // `NowPlayingViewModel.resetSpeedToDefault` reads it the same way, for the same reason.
            val target = playbackPreferences.getDefaultPlaybackSpeed()
            playbackController.setPlaybackSpeed(target)
            playbackManager.onSpeedReset(target)
        }
    }

    /**
     * Play this book [boostDb] louder from now on, and remember that it is read that way.
     *
     * One call, unlike [setSpeed]'s pair: [PlaybackManager.onVolumeBoostChanged] both records the
     * per-book choice and recomputes `effectiveGainDb`, which the collector in `init` turns into
     * a real gain. `NowPlayingViewModel.setBoost` is the same single call for the same reason.
     */
    fun setBoost(boostDb: Float) {
        playbackManager.onVolumeBoostChanged(boostDb)
    }

    /**
     * Go back to the listener's own default boost, and stop treating this book as an exception.
     *
     * [PlaybackManager.onBoostReset] rather than `onVolumeBoostChanged` with the same number, for
     * the reason [resetSpeed] documents: one pins the book, the other says it has no opinion.
     * Mirrors `NowPlayingViewModel.resetBoostToDefault`, including the suspend read.
     */
    fun resetBoost() {
        scope.launch {
            // The suspend read, not [defaultBoostDb]`.value` — see the ⛔ on that flow.
            playbackManager.onBoostReset(playbackPreferences.getDefaultVolumeBoostDb())
        }
    }

    /**
     * Jumps the playhead to the start of chapter [index].
     *
     * Reuses [seek] rather than restating it: the pair of calls it makes — seek the controller,
     * then tell [PlaybackManager] the new position — is what keeps the bar honest while paused,
     * where no time update is coming to correct it. `NowPlayingViewModel.seekToChapter` makes the
     * same two calls for the same reason.
     *
     * An index the list does not have is ignored rather than clamped. It can only arrive from a
     * caller reading a stale list, and landing the listener at some *other* chapter would be a
     * confident wrong answer where doing nothing is a visible one.
     */
    fun seekToChapter(index: Int) {
        val start = chapterStartMs(chapters.value, index) ?: return
        seek(start)
    }

    /**
     * Start a sleep timer, replacing any timer already running.
     *
     * Both modes are offered from the same call because [SleepTimerManager] already distinguishes
     * them: a duration counts down, and end-of-chapter waits for the feed set up in `init`.
     */
    fun setSleepTimer(mode: SleepTimerMode) = sleepTimerManager.setTimer(mode)

    /** Stop the running timer. Playback is untouched — this cancels the *ending*, not the book. */
    fun cancelSleepTimer() = sleepTimerManager.cancelTimer()

    /**
     * Add [minutes] to a running duration timer.
     *
     * Ignored when nothing is running or when the timer is end-of-chapter, which has no clock to
     * add to — [SleepTimerManager.extendTimer] makes that decision, so the browser cannot come to
     * a different one than the phone.
     */
    fun extendSleepTimer(minutes: Int) = sleepTimerManager.extendTimer(minutes)

    /**
     * End the listening session: stop the audio, then stop observing it.
     *
     * [HtmlAudioPlayer.releasePlayer] rather than [HtmlAudioPlayer.pause], because the only thing
     * that closes a session is the shell unmounting — a sign-out, or any other flip off
     * `AuthState.Authenticated`. Pausing would leave a signed-out browser holding a live hls.js
     * instance, still fetching segments with the previous user's signed URLs, for the life of the
     * tab. Release is not terminal (`load()` revives the player), so the next sign-in gets a clean
     * one rather than a torn-down one.
     *
     * Nothing else covers this. `LogoutUseCase` reaches `PlaybackManagerImpl.clearPlayback()`,
     * which only zeroes the manager's own flows; [PlaybackController.releasePlayer] is a
     * documented no-op on web; and the Koin `onClose` hook fires at graph teardown, which a
     * sign-out is not. Without this the book keeps narrating over the login screen, with no
     * transport bar left to stop it.
     */
    fun close() {
        audioPlayer.releasePlayer()
        playbackManager.clearPlayback()
        scope.cancel()
    }

    fun asSession(): PlaybackSession =
        PlaybackSession(
            state = state,
            error = error,
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
            nowPlaying = nowPlaying,
            sleepTimer = sleepTimer,
            defaultSpeed = defaultSpeed,
            volumeBoostDb = volumeBoostDb,
            defaultBoostDb = defaultBoostDb,
            boostUnavailable = boostUnavailable,
            onPlayPause = ::playPause,
            onSeek = ::seek,
            onPlayBook = ::playBook,
            onSkipBack = ::skipBack,
            onSkipForward = ::skipForward,
            onSetSpeed = ::setSpeed,
            onResetSpeed = ::resetSpeed,
            onSetBoost = ::setBoost,
            onResetBoost = ::resetBoost,
            onSeekToChapter = ::seekToChapter,
            onSetSleepTimer = ::setSleepTimer,
            onCancelSleepTimer = ::cancelSleepTimer,
            onExtendSleepTimer = ::extendSleepTimer,
            onDismissError = ::dismissError,
            close = ::close,
        )
}

/**
 * Where chapter [index] starts, or `null` when [chapters] has no such chapter.
 *
 * ⛔ Out of range resolves to **nothing**, never to the nearest chapter. A bad index can only reach
 * here from a caller reading a list that has since changed — a book swapped underneath an open
 * picker — and landing the listener at some *other* chapter would be a confident wrong answer
 * where doing nothing is a visible one. They would have no way to tell they had been moved
 * somewhere they did not choose.
 *
 * Pure, and separate from the seek it feeds, so that rule is provable without a player.
 */
internal fun chapterStartMs(
    chapters: List<TransportChapter>,
    index: Int,
): Long? = chapters.getOrNull(index)?.startMs

/**
 * What the listener is told when a book will not start.
 *
 * One string for both the "prepare returned nothing" and "prepare threw" paths: from where the
 * listener sits they are the same event, and `PlaybackPreparer` already folds every underlying
 * cause to `null` before either reaches here, so a more specific message would be invented rather
 * than reported.
 */
private const val PREPARE_FAILED = "Couldn't start this book. Check your connection and try again."

/**
 * Room's book, reduced to what the player panel draws.
 *
 * Narrators join into one string because the panel prints them as a sentence and web has no
 * narrator destination to link each one to — Contributor Detail is reached by id, and a narrator
 * credit carries one, but a list of separately-clickable narrators is a control this panel has no
 * room for. Authors DO link, because that is the single most likely thing to want from a player.
 */
private fun BookDetail.toNowPlaying(): NowPlayingBook =
    NowPlayingBook(
        bookId = id.value,
        coverHash = coverHash,
        authors = authors.map { PlayerLink(id = it.id, name = it.name) },
        narrators = narrators.joinToString(", ") { it.name },
        series =
            series.map {
                PlayerSeriesLink(id = it.seriesId, name = it.seriesName, sequenceLabel = it.sequenceLabel)
            },
    )
