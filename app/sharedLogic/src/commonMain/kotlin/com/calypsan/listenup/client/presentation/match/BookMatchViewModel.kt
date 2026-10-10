package com.calypsan.listenup.client.presentation.match

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.BookMatchReview
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FindStrategy
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.RegionContext
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.MatchingRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val COUNTDOWN_TICK_MS = 1_000L

/**
 * One Match details session for one book: Find, Review and Apply share this ViewModel, so going back to the
 * results never re-searches and a candidate you return to keeps what you set on it.
 *
 * Find starts as soon as the ViewModel exists, from what the server already knows about the book; the Your copy
 * strip comes from Room before the search returns. Apply sends every decision in one request; on success the
 * receipt goes to [MatchReceiptStore] for Book Detail and [BookMatchEvent.Applied] tells the screen to return
 * there.
 */
class BookMatchViewModel internal constructor(
    private val bookId: String,
    private val matchingRepository: MatchingRepository,
    bookRepository: BookRepository,
    private val receiptStore: MatchReceiptStore,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val session = MutableStateFlow(Session())
    private var findJob: Job? = null
    private var countdownJob: Job? = null
    private var reviewJob: Job? = null

    private val yourCopy: Flow<YourCopyUi?> =
        combine(bookRepository.observeBookDetail(bookId), bookRepository.observeChapters(bookId)) { book, chapters ->
            book?.toYourCopy(chapters.size)
        }

    private val eventChannel = Channel<BookMatchEvent>(Channel.BUFFERED)

    /** One-shot outcomes: Applied (return to Book Detail) and ReviewReloaded. */
    val events: Flow<BookMatchEvent> = eventChannel.receiveAsFlow()

    /** The Find step. */
    val findState: StateFlow<FindUiState> =
        combine(session, yourCopy) { s, copy -> s.toFindState(copy) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FindUiState.Searching(null, "", null))

    /** The Review step. */
    val reviewState: StateFlow<ReviewUiState> =
        combine(session, yourCopy) { s, copy -> s.toReviewState(copy) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState.NoneChosen)

    init {
        runFind()
    }

    /** Searches for [query]; a blank query goes back to the automatic search. */
    fun search(query: String) {
        session.update {
            it.copy(query = query.trim().ifBlank { null }, strategy = FindStrategy.AUTOMATIC)
        }
        runFind()
    }

    /** "Search by title": skips existing links and identifiers, searching title, author and length only. */
    fun searchByTitle() {
        session.update { it.copy(query = null, strategy = FindStrategy.TITLE_AUTHOR) }
        runFind()
    }

    /** Searches another store for this search only; the library's store is untouched. Choices are kept. */
    fun chooseStoreForThisSearch(region: MetadataLocale) {
        session.update { it.copy(regionOverride = region) }
        runFind()
    }

    /** Re-runs the whole Find; sources that already answered come back from the server's cache. */
    fun retry() {
        val failed = session.value.find as? FindPhase.Failed
        if ((failed?.failure as? FindFailure.RateLimited)?.run { secondsRemaining > 0 } == true) return
        runFind()
    }

    /** Opens [key]'s Review. */
    fun pick(key: BookCandidateKey) {
        val loaded = session.value.find as? FindPhase.Loaded ?: return
        val candidate = loaded.candidates.firstOrNull { it.key == key } ?: return
        session.update { it.copy(pickedKey = key, review = ReviewPhase.Loading(candidate)) }
        loadReview(candidate)
    }

    /** Back to the intact results; the picked row stays marked. */
    fun backToResults() {
        reviewJob?.cancel()
        session.update { it.copy(review = ReviewPhase.None) }
    }

    /**
     * Two-pane layouts open the best Strong match straight away; phones wait for a tap. Layouts report their mode
     * whenever it changes.
     */
    fun useTwoPane(enabled: Boolean) {
        session.update { it.copy(twoPane = enabled) }
        if (enabled) autoPickBest()
    }

    /** Ticks or unticks a field. Unticking keeps yours; re-ticking restores the last chosen source. */
    fun setFieldTicked(
        field: BookField,
        ticked: Boolean,
    ) {
        updateChoices { choices, review ->
            review.fields.firstOrNull { it.field == field }?.let { choices.setTicked(it, ticked) } ?: choices
        }
    }

    /** Picks a field's source, or Keep yours. */
    fun chooseSource(
        field: BookField,
        choice: FieldChoice,
    ) {
        updateChoices { choices, _ -> choices.choose(field, choice) }
    }

    /** Picks the cover Apply writes: Keep current, or one candidate. */
    fun chooseCover(choice: ImageChoice) {
        updateChoices { choices, _ -> choices.copy(cover = choice) }
    }

    /** Removes one of your genres or moods (an explicit ×). */
    fun removeYourLabel(
        kind: LabelKind,
        label: String,
    ) {
        updateChoices { c, _ ->
            c.copy(removedYours = c.toggleLabel(bucket = c.removedYours, kind = kind, label = label, on = true))
        }
    }

    /** Keeps a label you had removed. */
    fun restoreYourLabel(
        kind: LabelKind,
        label: String,
    ) {
        updateChoices { c, _ ->
            c.copy(removedYours = c.toggleLabel(bucket = c.removedYours, kind = kind, label = label, on = false))
        }
    }

    /** Selects or deselects a suggested label. */
    fun toggleSuggestion(
        kind: LabelKind,
        label: String,
    ) {
        updateChoices { c, _ ->
            val off = label in c.deselectedSuggestions[kind].orEmpty()
            c.copy(
                deselectedSuggestions =
                    c.toggleLabel(
                        bucket = c.deselectedSuggestions,
                        kind = kind,
                        label = label,
                        on = !off,
                    ),
            )
        }
    }

    /** Includes or leaves out every chapter name. */
    fun setChapterNamesIncluded(included: Boolean) {
        updateChoices { choices, _ -> choices.copy(chapterNamesIncluded = included) }
    }

    /** Includes or leaves out one chapter's name. */
    fun toggleChapter(ordinal: Int) {
        updateChoices { choices, _ ->
            val excluded = choices.deselectedChapters
            choices.copy(deselectedChapters = if (ordinal in excluded) excluded - ordinal else excluded + ordinal)
        }
    }

    /**
     * Applies every decision in one request. On `ReviewOutdated` the Review reloads with the choices whose
     * options survived and [BookMatchEvent.ReviewReloaded] fires; any other failure stays on Review, inline.
     */
    fun apply() {
        val ready = session.value.review as? ReviewPhase.Ready ?: return
        if (ready.applying) return
        val request = session.value.choicesFor(ready.candidate.key).toApply(ready.review)
        updateReady { it.copy(applying = true, applyError = null) }
        viewModelScope.launch {
            when (val result = matchingRepository.applyBookMatch(BookId(bookId), request)) {
                is AppResult.Success -> {
                    receiptStore.put(bookId, result.data)
                    updateReady { it.copy(applying = false) }
                    eventChannel.send(BookMatchEvent.Applied(result.data))
                }

                is AppResult.Failure -> {
                    if (result.error is MetadataError.ReviewOutdated) {
                        reloadAfterOutdated(ready.candidate)
                    } else {
                        errorBus.emit(result.error)
                        updateReady { it.copy(applying = false, applyError = result.error) }
                    }
                }
            }
        }
    }

    private suspend fun reloadAfterOutdated(candidate: CandidateUi) {
        when (val reloaded = review(candidate)) {
            is AppResult.Success -> {
                session.update { s ->
                    s.copy(
                        review =
                            ReviewPhase.Ready(
                                candidate = candidate,
                                review = reloaded.data,
                                applying = false,
                                applyError = null,
                            ),
                    )
                }
                eventChannel.send(BookMatchEvent.ReviewReloaded)
            }

            is AppResult.Failure -> {
                errorBus.emit(reloaded.error)
                session.update { it.copy(review = ReviewPhase.Failed(candidate, reloaded.error)) }
            }
        }
    }

    private fun runFind() {
        findJob?.cancel()
        countdownJob?.cancel()
        session.update { it.copy(find = FindPhase.Searching(previous = it.find as? FindPhase.Loaded ?: it.lastLoaded)) }
        val s = session.value
        val request = BookFindRequest(query = s.query, strategy = s.strategy, regionOverride = s.regionOverride)
        findJob =
            viewModelScope.launch {
                when (val result = matchingRepository.findBookMatches(BookId(bookId), request)) {
                    is AppResult.Success -> {
                        onFound(result.data)
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        session.update { it.copy(find = FindPhase.Failed(findFailureFor(result.error), null)) }
                    }
                }
            }
    }

    private fun onFound(result: BookFindResult) {
        when (val outcome = chooseFindOutcome(result)) {
            is FindOutcome.Candidates -> {
                val loaded = FindPhase.Loaded(result, outcome)
                session.update { it.copy(find = loaded, lastLoaded = loaded) }
                autoPickBest()
            }

            is FindOutcome.Failure -> {
                session.update { it.copy(find = FindPhase.Failed(outcome.failure, result.region)) }
                (outcome.failure as? FindFailure.RateLimited)?.let(::countDown)
            }
        }
    }

    private fun countDown(start: FindFailure.RateLimited) {
        countdownJob =
            viewModelScope.launch {
                var remaining = start.secondsRemaining
                while (remaining > 0) {
                    delay(COUNTDOWN_TICK_MS)
                    remaining--
                    val left = remaining
                    session.update { s ->
                        val failed = s.find as? FindPhase.Failed ?: return@update s
                        val limited = failed.failure as? FindFailure.RateLimited ?: return@update s
                        s.copy(find = failed.copy(failure = limited.copy(secondsRemaining = left)))
                    }
                }
            }
    }

    private fun autoPickBest() {
        val s = session.value
        if (!s.twoPane || s.review != ReviewPhase.None || s.pickedKey != null) return
        val loaded = s.find as? FindPhase.Loaded ?: return
        val best = loaded.outcome.strong.firstOrNull { it.isBest } ?: loaded.outcome.strong.firstOrNull() ?: return
        pick(best.key)
    }

    private fun loadReview(candidate: CandidateUi) {
        reviewJob?.cancel()
        reviewJob =
            viewModelScope.launch {
                when (val result = review(candidate)) {
                    is AppResult.Success -> {
                        session.update { s ->
                            s.copy(
                                review =
                                    ReviewPhase.Ready(
                                        candidate = candidate,
                                        review = result.data,
                                        applying = false,
                                        applyError = null,
                                    ),
                            )
                        }
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        session.update { it.copy(review = ReviewPhase.Failed(candidate, result.error)) }
                    }
                }
            }
    }

    private suspend fun review(candidate: CandidateUi): AppResult<BookMatchReview> =
        matchingRepository.reviewBookMatch(BookId(bookId), candidate.key, session.value.regionOverride)

    private fun updateChoices(transform: (ReviewChoices, BookMatchReview) -> ReviewChoices) {
        session.update { s ->
            val ready = s.review as? ReviewPhase.Ready ?: return@update s
            if (ready.applying) return@update s
            val key = ready.candidate.key
            s.copy(choices = s.choices + (key to transform(s.choicesFor(key), ready.review)))
        }
    }

    private fun updateReady(transform: (ReviewPhase.Ready) -> ReviewPhase.Ready) {
        session.update { s ->
            val ready = s.review as? ReviewPhase.Ready ?: return@update s
            s.copy(review = transform(ready))
        }
    }

    private sealed interface FindPhase {
        /** A Find is running; the last results stay on screen. */
        data class Searching(
            val previous: Loaded?,
        ) : FindPhase

        /** Find returned candidates. */
        data class Loaded(
            val result: BookFindResult,
            val outcome: FindOutcome.Candidates,
        ) : FindPhase {
            val candidates: List<CandidateUi> get() = outcome.strong + outcome.maybe
        }

        /** Find returned no candidates, for this reason. */
        data class Failed(
            val failure: FindFailure,
            val region: RegionContext?,
        ) : FindPhase
    }

    private sealed interface ReviewPhase {
        data object None : ReviewPhase

        /** The Review for this candidate is loading. */
        data class Loading(
            val candidate: CandidateUi,
        ) : ReviewPhase

        /** The Review is loaded; Apply may be running. */
        data class Ready(
            val candidate: CandidateUi,
            val review: BookMatchReview,
            val applying: Boolean,
            val applyError: AppError?,
        ) : ReviewPhase

        /** The Review couldn't load. */
        data class Failed(
            val candidate: CandidateUi,
            val error: AppError,
        ) : ReviewPhase
    }

    private data class Session(
        /** What the person typed; null means the automatic search from what the server knows. */
        val query: String? = null,
        val strategy: FindStrategy = FindStrategy.AUTOMATIC,
        val regionOverride: MetadataLocale? = null,
        val find: FindPhase = FindPhase.Searching(previous = null),
        val lastLoaded: FindPhase.Loaded? = null,
        val pickedKey: BookCandidateKey? = null,
        val review: ReviewPhase = ReviewPhase.None,
        val choices: Map<BookCandidateKey, ReviewChoices> = emptyMap(),
        val twoPane: Boolean = false,
    ) {
        fun choicesFor(key: BookCandidateKey): ReviewChoices = choices[key] ?: ReviewChoices()

        fun displayedQuery(copy: YourCopyUi?): String = query ?: copy?.title.orEmpty()

        fun toFindState(copy: YourCopyUi?): FindUiState =
            when (val find = find) {
                is FindPhase.Searching -> {
                    FindUiState.Searching(copy, displayedQuery(copy), find.previous?.let { resultsOf(it, copy) })
                }

                is FindPhase.Loaded -> {
                    resultsOf(find, copy)
                }

                is FindPhase.Failed -> {
                    FindUiState.Failed(
                        yourCopy = copy,
                        query = displayedQuery(copy),
                        failure = find.failure,
                        region = find.region?.toUi(),
                    )
                }
            }

        private fun resultsOf(
            loaded: FindPhase.Loaded,
            copy: YourCopyUi?,
        ): FindUiState.Results =
            FindUiState.Results(
                yourCopy = copy,
                steps = loaded.result.steps,
                query = displayedQuery(copy),
                strong = loaded.outcome.strong,
                maybe = loaded.outcome.maybe,
                partialFailure = loaded.outcome.partialFailure,
                region = loaded.result.region?.toUi(),
                pickedKey = pickedKey,
            )

        fun toReviewState(copy: YourCopyUi?): ReviewUiState =
            when (val review = review) {
                ReviewPhase.None -> {
                    ReviewUiState.NoneChosen
                }

                is ReviewPhase.Loading -> {
                    ReviewUiState.Loading(review.candidate)
                }

                is ReviewPhase.Failed -> {
                    ReviewUiState.Failed(review.candidate, review.error)
                }

                is ReviewPhase.Ready -> {
                    choicesFor(review.candidate.key).project(
                        candidate = review.candidate,
                        review = review.review,
                        currentCoverPath = copy?.coverPath,
                        applying = review.applying,
                        applyError = review.applyError,
                    )
                }
            }
    }
}

private fun RegionContext.toUi(): RegionUi =
    RegionUi(source = source, region = region, origin = origin, choices = choices)

private fun BookDetail.toYourCopy(chapterCount: Int): YourCopyUi =
    YourCopyUi(
        title = title,
        authors = authors.map { it.name },
        coverPath = coverPath,
        coverHash = coverHash,
        durationMs = duration.takeIf { it > 0 },
        narrators = narrators.map { it.name },
        chapterCount = chapterCount.takeIf { it > 0 },
        year = publishYear,
        isAbridged = abridged,
    )
