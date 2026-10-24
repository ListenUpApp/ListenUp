package com.calypsan.listenup.client.presentation.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.formatSeriesSequence
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.signedInUserId
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.series.ObserveNextInSeriesUseCase
import com.calypsan.listenup.client.playback.PlayerEngine
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.PlayerSession
import com.calypsan.listenup.client.playback.PlayerSessionState
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The player's one view model on every platform (spec §4): Android and desktop through `koinViewModel`, iOS
 * through `PlayerObserver`, web through the shell's `PlaybackSession`. A projection of [PlayerSession] plus
 * Room metadata; actions forward to the session. A Koin `factory` with a public constructor, so
 * `KoinModuleVerifyTest` covers it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions", "LongParameterList") // one function per user intent (spec §4.3); one input per source
class PlayerViewModel(
    private val session: PlayerSession,
    engine: PlayerEngine,
    private val bookRepository: BookRepository,
    private val contributorRepository: ContributorRepository,
    private val documentRepository: DocumentRepository,
    private val bookRatingRepository: BookRatingRepository,
    private val observeNextInSeries: ObserveNextInSeriesUseCase,
    userRepository: UserRepository,
    private val authSession: AuthSession,
    availability: BookAvailability,
    networkMonitor: NetworkMonitor,
    serverReachability: ServerReachability,
    playbackPreferences: PlaybackPreferences,
    private val sheetState: PlayerSheetState,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val eventChannel = Channel<PlayerEvent>(Channel.BUFFERED)

    /** One-shot navigation for the platform. */
    val events: Flow<PlayerEvent> = eventChannel.receiveAsFlow()

    /** Which picker or sheet is open on this screen. Per instance, as before. */
    val overlay: StateFlow<PlayerOverlay>
        field = MutableStateFlow<PlayerOverlay>(PlayerOverlay.None)

    /** Now Playing expanded, shared by every instance. */
    val expanded: StateFlow<Boolean> get() = sheetState.expanded

    /** The book a play request is in flight for, debounced (Book Detail's busy button, Decision 11). */
    val preparingBookId: StateFlow<String?> =
        session.preparingBookIdUi.map { it?.value }.stateIn(viewModelScope, WhileSubscribedForUi, null)

    private val loadedSession: Flow<PlayerSessionState.Loaded?> =
        session.state.map { it as? PlayerSessionState.Loaded }

    private val loadedBookId: Flow<BookId?> = loadedSession.map { it?.bookId }.distinctUntilChanged()

    private val book: Flow<PlayerBook?> =
        loadedBookId.flatMapLatest { id -> id?.let(::observePlayerBook) ?: flowOf(null) }

    // currentChapter ticks (its remainingMs moves), but playerChapterOf ignores remainingMs, so the pair is equal
    // across ticks and distinctUntilChanged drops them: uiState never re-emits on a position tick.
    private val chapterSlice: Flow<Pair<PlayerChapter, List<PlayerChapterRow>>> =
        combine(session.chapters, session.currentChapter, session.durationMs) { marks, info, duration ->
            playerChapterOf(info, duration) to chapterRowsOf(marks, info?.index)
        }.distinctUntilChanged()

    private val settings: Flow<PlayerSettings> =
        combine(
            flow = combine(session.speed, playbackPreferences.observeDefaultPlaybackSpeed()) { a, b -> a to b },
            flow2 = combine(session.boostDb, playbackPreferences.observeDefaultVolumeBoostDb()) { a, b -> a to b },
            flow3 = session.skipBackwardSec,
            flow4 = session.skipForwardSec,
        ) { (speed, defaultSpeed), (boost, defaultBoost), back, forward ->
            PlayerSettings(
                speed = speed,
                defaultSpeed = defaultSpeed,
                boostDb = boost,
                defaultBoostDb = defaultBoost,
                skipBackSec = back,
                skipForwardSec = forward,
            )
        }.distinctUntilChanged()

    private val bookDownloaded: Flow<Boolean> =
        loadedBookId.flatMapLatest { id ->
            id?.let { availability.observe(it).map { a -> a.downloadStatus is BookDownloadStatus.Completed } }
                ?: flowOf(false)
        }

    private val connectivity: Flow<Pair<Boolean, Boolean>> =
        combine(networkMonitor.onlineFlow, serverReachability.state) { online, reach ->
            online to (reach != Reachability.Unreachable)
        }

    private val source: Flow<AudioSource> =
        combine(
            flow = loadedSession,
            flow2 = engine.outputDeviceName,
            flow3 = engine.timeline,
            flow4 = bookDownloaded,
            flow5 = connectivity,
        ) { loaded, device, timeline, downloaded, (online, reachable) ->
            val bound = loaded?.isEngineBound == true
            audioSourceOf(
                phase = loaded?.phase ?: PlayerPhase.Restored,
                outputDeviceName = device,
                timelineFullyDownloaded = if (bound) timeline?.isFullyDownloaded else null,
                anyFileTranscoded =
                    bound &&
                        timeline?.files.orEmpty().any {
                            it.localPath == null && it.hlsUrl != null
                        },
                bookDownloaded = downloaded,
                isDeviceOnline = online,
                isServerReachable = reachable,
                offlineCopiesSupported = session.offlineCopiesSupported,
            )
        }.distinctUntilChanged()

    private val finished: Flow<FinishedInfo?> =
        loadedSession
            .map { it?.takeIf { s -> s.phase == PlayerPhase.Finished }?.bookId }
            .distinctUntilChanged()
            .flatMapLatest { id -> id?.let(::observeFinishedInfo) ?: flowOf(null) }

    private val surroundings: Flow<Surroundings> =
        combine(
            flow = settings,
            flow2 = source,
            flow3 = session.sleepTimerState,
            flow4 = userRepository.observeIsAdmin(),
        ) { settings, source, sleep, isAdmin ->
            Surroundings(settings = settings, source = source, sleepTimer = sleep, isAdmin = isAdmin)
        }

    /** What every player surface renders. Never re-emits on a position tick, and never blinks out on a book switch. */
    val uiState: StateFlow<PlayerUiState> =
        combine(
            flow = loadedSession,
            flow2 = book,
            flow3 = chapterSlice,
            flow4 = surroundings,
            flow5 = finished,
        ) { loaded, book, (chapter, rows), around, finishedInfo ->
            when {
                loaded == null || book == null -> {
                    PlayerUiState.NothingLoaded
                }

                // The session moved to a new book before Room answered for it. Hold the last frame (null is dropped
                // below) rather than emit NothingLoaded, which unmounts the bar for a frame on every switch (§3.6).
                book.id != loaded.bookId.value -> {
                    null
                }

                else -> {
                    PlayerUiState.Loaded(
                        book = book,
                        phase = loaded.phase,
                        chapter = chapter,
                        chapters = rows,
                        source = around.source,
                        settings = around.settings,
                        sleepTimer = around.sleepTimer,
                        primary = primaryControlFor(loaded.phase, around.isAdmin),
                        showsSkipBack = showsSkipBackFor(loaded.phase),
                        canSkipToNextChapter = !chapter.isWholeBook && chapter.number < chapter.total,
                        finished = finishedInfo.takeIf { loaded.phase == PlayerPhase.Finished },
                    )
                }
            }
        }.filterNotNull()
            .distinctUntilChanged()
            .stateIn(viewModelScope, WhileSubscribedForUi, PlayerUiState.NothingLoaded)

    /** The fast-changing, chapter-scoped position (~4 Hz). */
    val progress: StateFlow<PlayerProgress> =
        combine(session.positionMs, session.currentChapter, session.durationMs) { position, chapter, duration ->
            val start = chapter?.startMs ?: 0L
            val end = chapter?.endMs ?: duration
            val chapterDuration = (end - start).coerceAtLeast(0L)
            PlayerProgress(
                chapterPositionMs = (position - start).coerceIn(0L, chapterDuration),
                chapterDurationMs = chapterDuration,
                bookPositionMs = position,
                bookDurationMs = duration,
            )
        }.stateIn(viewModelScope, WhileSubscribedForUi, PlayerProgress.Zero)

    private fun observePlayerBook(id: BookId): Flow<PlayerBook?> =
        bookRepository.observeBookDetail(id.value).flatMapLatest { detail ->
            if (detail == null) {
                flowOf(null)
            } else {
                combine(
                    contributorsWithCounts(detail.authors, ContributorRole.AUTHOR.apiValue),
                    contributorsWithCounts(detail.narrators, ContributorRole.NARRATOR.apiValue),
                    documentRepository.observeDocuments(id).map { docs -> docs.firstOrNull { it.format == "pdf" }?.id },
                ) { authors, narrators, pdf ->
                    playerBookOf(detail = detail, authors = authors, narrators = narrators, firstPdfDocId = pdf)
                }
            }
        }

    private fun contributorsWithCounts(
        people: List<BookContributor>,
        role: String,
    ): Flow<List<PlayerContributor>> =
        if (people.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                people.map { person ->
                    contributorRepository.observeRolesWithCountForContributor(person.id).map { roles ->
                        PlayerContributor(
                            id = person.id,
                            name = person.name,
                            bookCount = roles.firstOrNull { it.role == role }?.bookCount ?: 0,
                        )
                    }
                },
            ) { it.toList() }
        }

    private fun observeFinishedInfo(id: BookId): Flow<FinishedInfo> =
        combine(
            flow = session.durationMs,
            flow2 = bookRatingRepository.observeForBook(id.value),
            flow3 = authSession.authState.signedInUserId(),
            flow4 = observeNextInSeries(id.value),
            // The finish dates live on the position row.
            flow5 = session.finishDates(id),
        ) { duration, ratings, me, next, (finishedAt, startedAt) ->
            FinishedInfo(
                finishedAtMs = finishedAt,
                startedAtMs = startedAt,
                durationMs = duration,
                yourHalfStars = ratings.firstOrNull { it.userId == me }?.halfStars,
                nextBook = next,
            )
        }

    // ── Actions (spec §4.3): each forwards to the session; ephemera stay here ──

    /** Load [bookId] and play it (Home's and Book Detail's play entry, P-R5), at [startAtMs] or the saved position. */
    fun playBook(
        bookId: BookId,
        startAtMs: Long? = null,
    ) {
        session.playBook(bookId, startAtMs)
    }

    /** Toggle play and pause. */
    fun playPause() {
        session.playPause()
    }

    /** The one main button's action, decided by [PlayerUiState.Loaded.primary]. */
    fun onPrimary() {
        val loaded = uiState.value as? PlayerUiState.Loaded ?: return
        when (loaded.primary) {
            PrimaryControl.Pause, PrimaryControl.Play, PrimaryControl.TapToPlay, PrimaryControl.Busy -> {
                session.playPause()
            }

            PrimaryControl.Retry -> {
                session.retry()
            }

            PrimaryControl.ListenAgain -> {
                listenAgain()
            }

            PrimaryControl.SignIn -> {
                eventChannel.trySend(PlayerEvent.OpenSignIn)
            }

            PrimaryControl.OpenInbox -> {
                eventChannel.trySend(PlayerEvent.OpenInbox)
            }

            PrimaryControl.Close -> {
                closeBook()
            }
        }
    }

    /** A state card's button ([PlayerCards]): each forwards to the action it names. */
    fun onCardAction(action: PlayerCardAction) {
        when (action) {
            PlayerCardAction.Retry -> retry()
            PlayerCardAction.Dismiss -> dismissError()
            PlayerCardAction.CloseBook -> closeBook()
            // The primary already is Sign in, Open Inbox, Play (Resume) or Tap to play in these states.
            PlayerCardAction.SignIn, PlayerCardAction.OpenInbox, PlayerCardAction.Resume, PlayerCardAction.TapToPlay -> onPrimary()
            PlayerCardAction.ListenAgain -> listenAgain()
            PlayerCardAction.PlayNextBook -> playNextBook()
            PlayerCardAction.KeepListening -> cancelSleepTimer()
        }
    }

    /** Try the failed load or play again. */
    fun retry() {
        session.retry()
    }

    /** Clear the error and keep the bar (§11 row 1). */
    fun dismissError() {
        session.dismissError()
    }

    /** Back by the user's skip interval. */
    fun skipBack() {
        session.skipBack()
    }

    /** Forward by the user's skip interval. */
    fun skipForward() {
        session.skipForward()
    }

    /** The start of this chapter, or the previous one near its start (§3.3). */
    fun previousChapter() {
        session.previousChapter()
    }

    /** The start of the next chapter. */
    fun nextChapter() {
        session.nextChapter()
    }

    /** Seek to a chapter's start and close the chapter sheet (PC4). */
    fun seekToChapter(index: Int) {
        session.seekToChapter(index)
        overlay.value = PlayerOverlay.None
    }

    /** Seek within the current chapter, [fraction] 0..1. */
    fun seekWithinChapter(fraction: Float) {
        session.seekWithinChapter(fraction)
    }

    /** A book-relative seek (today's web scrubber and Media Session `seekto`; PR 3/6 move them to the chapter). */
    fun seekTo(positionMs: Long) {
        session.seekTo(positionMs)
    }

    /** Set this book's speed. */
    fun setSpeed(speed: Float) {
        session.setSpeed(speed)
    }

    /** One step up or down the speed ladder. */
    fun stepSpeed(up: Boolean) {
        session.stepSpeed(up)
    }

    /** Back to the user's default speed. */
    fun resetSpeed() {
        session.resetSpeed()
    }

    /** Set this book's volume boost, in dB. */
    fun setBoost(boostDb: Float) {
        session.setBoost(boostDb)
    }

    /** Back to the user's default boost. */
    fun resetBoost() {
        session.resetBoost()
    }

    /** Start a timer and close the sleep sheet (a choice that moves you, §3.8). */
    fun setSleepTimer(mode: SleepTimerMode) {
        session.setSleepTimer(mode)
        overlay.value = PlayerOverlay.None
    }

    /** Add [minutes] to the running timer. */
    fun extendSleepTimer(minutes: Int) {
        session.extendSleepTimer(minutes)
    }

    /** Cancel keeps the sleep sheet open (S13). */
    fun cancelSleepTimer() {
        session.cancelSleepTimer()
    }

    /** Close book: stop, unload, collapse (§4.3). */
    fun closeBook() {
        session.closeBook()
        sheetState.collapse()
    }

    /** Play this book again (F3: never a different one). */
    fun listenAgain() {
        val loaded = uiState.value as? PlayerUiState.Loaded ?: return
        session.playBook(BookId(loaded.book.id), null)
    }

    /** Play the next book in the series (§3.7). */
    fun playNextBook() {
        val next = (uiState.value as? PlayerUiState.Loaded)?.finished?.nextBook ?: return
        session.playBook(BookId(next.bookId), null)
    }

    /** Rate the finished book in half stars (2..10). A failure goes to the error bus (§4.3). */
    fun rate(halfStars: Int) {
        val loaded = uiState.value as? PlayerUiState.Loaded ?: return
        viewModelScope.launch {
            when (val result = bookRatingRepository.rate(loaded.book.id, halfStars, note = null)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    /** Download the first PDF if needed and ask the platform to open it. */
    fun openCurrentPdf() {
        val loaded = uiState.value as? PlayerUiState.Loaded ?: return
        val docId = loaded.book.firstPdfDocId ?: return
        viewModelScope.launch {
            when (val result = documentRepository.ensureLocal(BookId(loaded.book.id), docId)) {
                is AppResult.Success -> eventChannel.trySend(PlayerEvent.OpenDocumentViewer(result.data))
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    /** Ask the platform to show the loaded book's detail. */
    fun goToBook() {
        val loaded = uiState.value as? PlayerUiState.Loaded ?: return
        eventChannel.trySend(PlayerEvent.NavigateToBook(loaded.book.id))
    }

    /** Open Now Playing (shared by every instance). */
    fun expand() {
        sheetState.expand()
    }

    /** Close Now Playing back to the mini player. */
    fun collapse() {
        sheetState.collapse()
    }

    /** Open the chapter list. */
    fun showChapterPicker() {
        overlay.value = PlayerOverlay.ChapterPicker
    }

    /** Open the speed picker. */
    fun showSpeedPicker() {
        overlay.value = PlayerOverlay.SpeedPicker
    }

    /** Open the boost picker. */
    fun showBoostPicker() {
        overlay.value = PlayerOverlay.BoostPicker
    }

    /** Open the sleep timer sheet. */
    fun showSleepTimer() {
        overlay.value = PlayerOverlay.SleepTimer
    }

    /** Open the contributor picker for [type]. */
    fun showContributorPicker(type: ContributorPickerType) {
        overlay.value = PlayerOverlay.ContributorPicker(type)
    }

    /** Close whichever picker is open. */
    fun hideOverlay() {
        overlay.value = PlayerOverlay.None
    }
}

/** [PlayerBook] from the Room detail; every series membership, in the book's order. */
private fun playerBookOf(
    detail: BookDetail,
    authors: List<PlayerContributor>,
    narrators: List<PlayerContributor>,
    firstPdfDocId: String?,
): PlayerBook =
    PlayerBook(
        id = detail.id.value,
        title = detail.title,
        authors = authors,
        narrators = narrators,
        coverPath = detail.coverPath,
        coverHash = detail.coverHash,
        series =
            detail.series.map { membership ->
                PlayerSeriesRef(
                    id = membership.seriesId,
                    name = membership.seriesName,
                    sequenceLabel = membership.sequence?.let(::formatSeriesSequence),
                )
            },
        durationMs = detail.duration,
        firstPdfDocId = firstPdfDocId,
    )

/** The slow-moving inputs beside the book and chapter, grouped so [PlayerViewModel.uiState] stays a five-way combine. */
private data class Surroundings(
    val settings: PlayerSettings,
    val source: AudioSource,
    val sleepTimer: SleepTimerState,
    val isAdmin: Boolean,
)
