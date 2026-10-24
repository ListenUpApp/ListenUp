package com.calypsan.listenup.web.features.nowplaying

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerEvent
import com.calypsan.listenup.client.presentation.player.PlayerOverlay
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PlayerViewModel
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.appCoroutineExceptionHandler
import com.calypsan.listenup.web.nav.RouteSession
import com.calypsan.listenup.web.playback.HtmlAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.core.Koin

/**
 * The web shell's player: [PlayerViewModel]'s state plus the gestures it accepts, and the teardown for it.
 *
 * A browser has no `ViewModelStore` to hand a lifetime to, so the shell opens this session and closes it when
 * it unmounts (the [com.calypsan.listenup.web.features.library.LibrarySession] arrangement). Everything the
 * native clients share (what is loaded, its phase, the one main button, supersede, sleep, typed errors) lives
 * in the view model and the shared `PlayerSession`; what stays here is the browser's own: the gain stage and
 * its refusal.
 */
class PlaybackSession(
    /** What the bar, the expanded player and the OS media controls render. Never re-emitted on a tick. */
    val state: StateFlow<PlayerUiState>,
    /** The playhead (~4 Hz), split from [state] so a tick re-renders only what shows the time. */
    val progress: StateFlow<PlayerProgress>,
    /** Whether a book is loaded, `Restoring` until the local read is done: what `/playing` decides its redirect on. */
    val loadedBook: Flow<LoadedBook>,
    /** Which picker the view model would have open. Web keeps its pickers' open flags in composition. */
    val overlay: StateFlow<PlayerOverlay>,
    /** The book a play request is in flight for, debounced (Book Detail's busy button). */
    val preparingBookId: StateFlow<String?>,
    /** Whether a boost was asked for and this browser would not give it. */
    val boostUnavailable: StateFlow<Boolean>,
    /** One-shot navigation the main button asks for: sign in, the Inbox, a book. */
    val events: Flow<PlayerEvent>,
    /** The one main button's action, whatever [PlayerUiState.Loaded.primary] says it is. */
    val onPrimary: () -> Unit,
    /** Play or pause what is loaded: the OS media keys, which have no notion of a Retry. */
    val onPlayPause: () -> Unit,
    /** Plays a book: [startAtMs] starts there (a chapter's start, in book ms); null resumes. */
    val onPlayBook: (bookId: BookId, startAtMs: Long?) -> Unit,
    val onSeek: (Long) -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onSeekToChapter: (Int) -> Unit,
    val onSetSpeed: (Float) -> Unit,
    val onResetSpeed: () -> Unit,
    /** One 0.05 step of the speed, down (false) or up (true): the panel's − and +. */
    val onStepSpeed: (Boolean) -> Unit,
    val onSetBoost: (Float) -> Unit,
    val onResetBoost: () -> Unit,
    val onSetSleepTimer: (SleepTimerMode) -> Unit,
    val onCancelSleepTimer: () -> Unit,
    val onExtendSleepTimer: (Int) -> Unit,
    val onRetry: () -> Unit,
    val onDismissError: () -> Unit,
    /** Finish with the loaded book: stop it and put the bar away. The session stays open. */
    val onCloseBook: () -> Unit,
    /** A state card's button (shared `PlayerCards`): the view model carries out the action it names. */
    val onCardAction: (PlayerCardAction) -> Unit,
    /** Rate the finished book, in half stars (2..10). */
    val onRate: (Int) -> Unit,
    override val close: () -> Unit,
) : RouteSession

/**
 * How the shell gets its player. Production drives the real one out of the client graph ([graphPlayback]);
 * specs hand over a fixed state instead ([fixedPlayback]), so the bar's layout can be driven without a
 * decoder behind it.
 */
typealias OpenPlayback = () -> PlaybackSession

/** The production source: the shared [PlayerViewModel] over the browser's player. */
fun graphPlayback(koin: Koin): OpenPlayback =
    {
        LivePlayback(
            viewModel = koin.get(),
            playbackManager = koin.get(),
            audioPlayer = koin.get(),
            loadedBooks = koin.get(),
        ).asSession()
    }

/**
 * A session over a state that never changes: the shape specs pass in place of the graph, so a spec about
 * navigation or layout is not forced to wire up a decoder. [PlayerUiState.NothingLoaded] draws no bar.
 *
 * Deliberately NOT a default value on [com.calypsan.listenup.web.WebAppRoot]'s parameter. Its `onPlayBook`
 * is inert, and Book Detail's Play button is gated on the book's own `canPlay` rather than on whether
 * playback is wired, so a production call site that forgot the argument would render a real, dead Play
 * button and compile clean. Making every caller name its source turns that into a compile error.
 */
fun fixedPlayback(
    state: PlayerUiState = PlayerUiState.NothingLoaded,
    progress: PlayerProgress = PlayerProgress.Zero,
    preparingBookId: String? = null,
    boostUnavailable: Boolean = false,
    loadedBook: Flow<LoadedBook> = flowOf(state.asLoadedBook()),
): OpenPlayback =
    {
        PlaybackSession(
            state = MutableStateFlow(state),
            progress = MutableStateFlow(progress),
            loadedBook = loadedBook,
            overlay = MutableStateFlow(PlayerOverlay.None),
            preparingBookId = MutableStateFlow(preparingBookId),
            boostUnavailable = MutableStateFlow(boostUnavailable),
            events = emptyFlow(),
            onPrimary = {},
            onPlayPause = {},
            onPlayBook = { _, _ -> },
            onSeek = {},
            onSkipBack = {},
            onSkipForward = {},
            onPreviousChapter = {},
            onNextChapter = {},
            onSeekToChapter = {},
            onSetSpeed = {},
            onResetSpeed = {},
            onStepSpeed = {},
            onSetBoost = {},
            onResetBoost = {},
            onSetSleepTimer = {},
            onCancelSleepTimer = {},
            onExtendSleepTimer = {},
            onRetry = {},
            onDismissError = {},
            onCloseBook = {},
            onCardAction = {},
            onRate = {},
            close = {},
        )
    }

/** What a fixed [state] says about the loaded book, for [fixedPlayback]: a fixed state has nothing left to restore. */
private fun PlayerUiState.asLoadedBook(): LoadedBook =
    when (this) {
        PlayerUiState.NothingLoaded -> {
            LoadedBook.NothingLoaded
        }

        is PlayerUiState.Loaded -> {
            LoadedBook.Loaded(
                bookId = book.id,
                isPlaying = phase == PlayerPhase.Playing,
                isFinished = phase == PlayerPhase.Finished,
            )
        }
    }

/**
 * The shell's [PlayerViewModel], held in a [ViewModelStore] this session owns, plus the browser's gain stage.
 *
 * The tap rule (Safari's activation is transient and the prepare is an RPC away) is the shared
 * `PlayerSession`'s: its load primes the element through `HtmlAudioStartGate` inside the call, before the
 * first suspension, and withdraws the prime when a load fails or is superseded.
 */
internal class LivePlayback(
    private val viewModel: PlayerViewModel,
    playbackManager: PlaybackManager,
    private val audioPlayer: HtmlAudioPlayer,
    private val loadedBooks: LoadedBookRepository,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main + appCoroutineExceptionHandler),
) {
    private val store = ViewModelStore().apply { put("player", viewModel) }

    init {
        // Loudness gain: the book's normalization plus the listener's boost, already combined by
        // `VolumeGain.effectiveGainDb`, the same number Android's gain processor applies.
        //
        // Combined with the player's state, and NOT because the state is wanted: the two arrive out of order.
        // The manager publishes `effectiveGainDb` inside the prepare, *before* the element has a source, and
        // `WebGainStage` cannot route an element with nothing loaded. The state change that follows the load
        // re-applies the gain to a book that now has audio. Re-applying is idempotent.
        scope.launch {
            combine(playbackManager.effectiveGainDb, playbackManager.playbackState) { db, _ -> db }
                .collect { db -> audioPlayer.setGainDb(db) }
        }
    }

    /**
     * End the listening session (the shell unmounting: a sign-out, or any flip off `Authenticated`).
     *
     * [HtmlAudioPlayer.releasePlayer], not a pause: a pause would leave a signed-out tab holding a live hls.js
     * instance still fetching segments with the previous user's signed URLs, and a prime from a tap whose
     * prepare is still in flight would stand. Release is not terminal (`load()` revives the player), so the
     * next sign-in gets a clean one. Stopping and forgetting the book itself is `LogoutUseCase`'s, through
     * `PlayerSession.closeBook()`, on every platform.
     */
    fun close() {
        audioPlayer.releasePlayer()
        store.clear()
        scope.cancel()
    }

    fun asSession(): PlaybackSession =
        PlaybackSession(
            state = viewModel.uiState,
            progress = viewModel.progress,
            loadedBook = loadedBooks.observe(),
            overlay = viewModel.overlay,
            preparingBookId = viewModel.preparingBookId,
            boostUnavailable = audioPlayer.boostUnavailable,
            events = viewModel.events,
            onPrimary = viewModel::onPrimary,
            onPlayPause = viewModel::playPause,
            onPlayBook = viewModel::playBook,
            onSeek = viewModel::seekTo,
            onSkipBack = viewModel::skipBack,
            onSkipForward = viewModel::skipForward,
            onPreviousChapter = viewModel::previousChapter,
            onNextChapter = viewModel::nextChapter,
            onSeekToChapter = viewModel::seekToChapter,
            onSetSpeed = viewModel::setSpeed,
            onResetSpeed = viewModel::resetSpeed,
            onStepSpeed = viewModel::stepSpeed,
            onSetBoost = viewModel::setBoost,
            onResetBoost = viewModel::resetBoost,
            onSetSleepTimer = viewModel::setSleepTimer,
            onCancelSleepTimer = viewModel::cancelSleepTimer,
            onExtendSleepTimer = viewModel::extendSleepTimer,
            onRetry = viewModel::retry,
            onDismissError = viewModel::dismissError,
            onCloseBook = viewModel::closeBook,
            onCardAction = viewModel::onCardAction,
            onRate = viewModel::rate,
            close = ::close,
        )
}
