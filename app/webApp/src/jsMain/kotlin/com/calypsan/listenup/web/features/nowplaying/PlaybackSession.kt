package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.web.playback.HtmlAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val onPlayPause: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onPlayBook: (BookId) -> Unit,
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
 * A session over a state that never changes — the shape specs use in place of the graph, and the
 * default every surface takes so a spec about something else is not forced to wire up a player.
 * `null` is the honest default: nothing is playing, so no bar is drawn.
 */
fun fixedPlayback(state: TransportState? = null): OpenPlayback =
    { PlaybackSession(MutableStateFlow(state), {}, {}, {}, {}) }

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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
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
     * [HtmlAudioPlayer.primeForPlayback] on the fourth line is the `play()` this lane was missing,
     * and it happens while the browser still considers the user to have asked for audio.
     */
    fun playBook(bookId: BookId) {
        if (playbackManager.preparingBookId.value == bookId) return
        preparingJob?.cancel()
        playbackManager.markPreparing(bookId)
        playbackManager.clearError()
        audioPlayer.primeForPlayback()
        preparingJob =
            scope.launch {
                try {
                    val result = playbackManager.prepareForPlayback(bookId)
                    if (result == null) {
                        // Withdraw the primed intent, or the next book to load — for any reason —
                        // would start playing on the strength of a tap that failed.
                        audioPlayer.pause()
                        playbackManager.reportError(
                            "Couldn't start this book. Check your connection and try again.",
                            isRecoverable = true,
                        )
                        return@launch
                    }
                    title.value = result.bookTitle
                    playbackManager.activateBook(bookId)
                    playbackController.startPlayback(result)
                } finally {
                    if (playbackManager.preparingBookId.value == bookId) {
                        playbackManager.clearPreparing()
                    }
                }
            }
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

    fun close() {
        scope.cancel()
    }

    fun asSession(): PlaybackSession =
        PlaybackSession(
            state = state,
            onPlayPause = ::playPause,
            onSeek = ::seek,
            onPlayBook = ::playBook,
            close = ::close,
        )
}
