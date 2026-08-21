package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackState
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val onPlayPause: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onPlayBook: (BookId) -> Unit,
    val onDismissError: () -> Unit,
    val close: () -> Unit,
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
): OpenPlayback =
    {
        PlaybackSession(
            state = MutableStateFlow(state),
            error = MutableStateFlow(error),
            onPlayPause = {},
            onSeek = {},
            onPlayBook = {},
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

    val state: StateFlow<TransportState?> =
        combine(
            playbackManager.currentBookId,
            title,
            playbackManager.isPlaying,
            playbackManager.currentPositionMs,
            playbackManager.totalDurationMs,
        ) { bookId, bookTitle, isPlaying, positionMs, durationMs ->
            if (bookId == null || bookTitle == null) {
                null
            } else {
                TransportState(
                    title = bookTitle,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Start [bookId] from cold.
     *
     * Mirrors `NowPlayingViewModel.playBook` — a repeat tap for the same book is swallowed, a tap
     * for a different one supersedes, and the pending-play window always closes in a `finally`.
     * That VM is not reachable from a browser (its `SleepTimerManager` has no binding in the web
     * graph), so its shape is followed here rather than borrowed.
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
            onPlayPause = ::playPause,
            onSeek = ::seek,
            onPlayBook = ::playBook,
            onDismissError = ::dismissError,
            close = ::close,
        )
}

/**
 * What the listener is told when a book will not start.
 *
 * One string for both the "prepare returned nothing" and "prepare threw" paths: from where the
 * listener sits they are the same event, and `PlaybackPreparer` already folds every underlying
 * cause to `null` before either reaches here, so a more specific message would be invented rather
 * than reported.
 */
private const val PREPARE_FAILED = "Couldn't start this book. Check your connection and try again."
