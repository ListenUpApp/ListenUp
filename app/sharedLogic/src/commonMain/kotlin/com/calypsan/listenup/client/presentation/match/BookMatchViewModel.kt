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
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.MatchingRepository
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

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
    errorBus: ErrorBus,
) : ViewModel() {
    private val searchParams = MutableStateFlow(BookSearchParams())

    private val controller =
        MatchSessionController<
            BookCandidateKey,
            CandidateUi,
            BookFindLoaded,
            RegionContext?,
            BookMatchReview,
            ReviewChoices,
        >(
            scope = viewModelScope,
            errorBus = errorBus,
            defaultChoices = ::ReviewChoices,
            keyOf = CandidateUi::key,
            candidatesOf = { loaded ->
                MatchLoadedCandidates(
                    all = loaded.outcome.strong + loaded.outcome.maybe,
                    bestStrong = loaded.outcome.strong.firstOrNull { it.isBest } ?: loaded.outcome.strong.firstOrNull(),
                )
            },
            loadReview = { candidate ->
                matchingRepository.reviewBookMatch(
                    BookId(bookId),
                    candidate.key,
                    searchParams.value.regionOverride,
                )
            },
        )

    private val yourCopy: Flow<YourCopyUi?> =
        combine(bookRepository.observeBookDetail(bookId), bookRepository.observeChapters(bookId)) { book, chapters ->
            book?.toYourCopy(chapters.size)
        }

    private val eventChannel = Channel<BookMatchEvent>(Channel.BUFFERED)

    /** One-shot outcomes: Applied (return to Book Detail) and ReviewReloaded. */
    val events: Flow<BookMatchEvent> = eventChannel.receiveAsFlow()

    /** The Find step. */
    val findState: StateFlow<FindUiState> =
        combine(searchParams, controller.state, yourCopy) { params, core, copy ->
            toFindState(params = params, core = core, copy = copy)
        }.stateIn(viewModelScope, WhileSubscribedForUi, FindUiState.Searching(null, "", null))

    /** The Review step. */
    val reviewState: StateFlow<ReviewUiState> =
        combine(controller.state, yourCopy) { core, copy ->
            toReviewState(core = core, copy = copy)
        }.stateIn(viewModelScope, WhileSubscribedForUi, ReviewUiState.NoneChosen)

    init {
        runFind()
    }

    /** Searches for [query]; a blank query goes back to the automatic search. */
    fun search(query: String) {
        searchParams.update {
            it.copy(query = query.trim().ifBlank { null }, strategy = FindStrategy.AUTOMATIC)
        }
        runFind()
    }

    /** "Search by title": skips existing links and identifiers, searching title, author and length only. */
    fun searchByTitle() {
        searchParams.update { it.copy(query = null, strategy = FindStrategy.TITLE_AUTHOR) }
        runFind()
    }

    /** Searches another store for this search only; the library's store is untouched. Choices are kept. */
    fun chooseStoreForThisSearch(region: MetadataLocale) {
        searchParams.update { it.copy(regionOverride = region) }
        runFind()
    }

    /** Re-runs the whole Find; sources that already answered come back from the server's cache. */
    fun retry() {
        if (controller.isRateLimitCoolingDown()) return
        runFind()
    }

    /** Opens [key]'s Review. */
    fun pick(key: BookCandidateKey) {
        controller.pick(key)
    }

    /** Back to the intact results; the picked row stays marked. */
    fun backToResults() {
        controller.backToResults()
    }

    /**
     * Two-pane layouts open the best Strong match straight away; phones wait for a tap. Layouts report their mode
     * whenever it changes.
     */
    fun useTwoPane(enabled: Boolean) {
        controller.useTwoPane(enabled)
    }

    /** Ticks or unticks a field. Unticking keeps yours; re-ticking restores the last chosen source. */
    fun setFieldTicked(
        field: BookField,
        ticked: Boolean,
    ) {
        controller.updateChoices { choices, review ->
            review.fields.firstOrNull { it.field == field }?.let { choices.setTicked(it, ticked) } ?: choices
        }
    }

    /** Picks a field's source, or Keep yours. */
    fun chooseSource(
        field: BookField,
        choice: FieldChoice,
    ) {
        controller.updateChoices { choices, _ -> choices.choose(field, choice) }
    }

    /** Picks the cover Apply writes: Keep current, or one candidate. */
    fun chooseCover(choice: ImageChoice) {
        controller.updateChoices { choices, _ -> choices.copy(cover = choice) }
    }

    /** Removes one of your genres or moods (an explicit ×). */
    fun removeYourLabel(
        kind: LabelKind,
        label: String,
    ) {
        controller.updateChoices { c, _ ->
            c.copy(removedYours = c.toggleLabel(bucket = c.removedYours, kind = kind, label = label, on = true))
        }
    }

    /** Keeps a label you had removed. */
    fun restoreYourLabel(
        kind: LabelKind,
        label: String,
    ) {
        controller.updateChoices { c, _ ->
            c.copy(removedYours = c.toggleLabel(bucket = c.removedYours, kind = kind, label = label, on = false))
        }
    }

    /** Selects or deselects a suggested label. */
    fun toggleSuggestion(
        kind: LabelKind,
        label: String,
    ) {
        controller.updateChoices { c, _ ->
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
        controller.updateChoices { choices, _ -> choices.copy(chapterNamesIncluded = included) }
    }

    /** Includes or leaves out one chapter's name. */
    fun toggleChapter(ordinal: Int) {
        controller.updateChoices { choices, _ ->
            val excluded = choices.deselectedChapters
            choices.copy(deselectedChapters = if (ordinal in excluded) excluded - ordinal else excluded + ordinal)
        }
    }

    /**
     * Applies every decision in one request. On `ReviewOutdated` the Review reloads with the choices whose
     * options survived and [BookMatchEvent.ReviewReloaded] fires; any other failure stays on Review, inline.
     */
    fun apply() {
        controller.apply(
            submit = { choices, review ->
                matchingRepository.applyBookMatch(BookId(bookId), choices.toApply(review))
            },
            onApplied = { receipt ->
                receiptStore.put(bookId, receipt)
                eventChannel.send(BookMatchEvent.Applied(receipt))
            },
            onReviewReloaded = {
                eventChannel.send(BookMatchEvent.ReviewReloaded)
            },
        )
    }

    private fun runFind() {
        val params = searchParams.value
        val request =
            BookFindRequest(
                query = params.query,
                strategy = params.strategy,
                regionOverride = params.regionOverride,
            )
        controller.startFind(failedMeta = null) {
            matchingRepository.findBookMatches(BookId(bookId), request).map { result ->
                when (val outcome = chooseFindOutcome(result)) {
                    is FindOutcome.Candidates -> MatchFindPhase.LoadedPhase(BookFindLoaded(result, outcome))
                    is FindOutcome.Failure -> MatchFindPhase.Failed(outcome.failure, result.region)
                }
            }
        }
    }

    private data class BookSearchParams(
        /** What the person typed; null means the automatic search from what the server knows. */
        val query: String? = null,
        val strategy: FindStrategy = FindStrategy.AUTOMATIC,
        val regionOverride: MetadataLocale? = null,
    )

    private data class BookFindLoaded(
        val result: BookFindResult,
        val outcome: FindOutcome.Candidates,
    )

    private fun toFindState(
        params: BookSearchParams,
        core: MatchCoreState<BookCandidateKey, CandidateUi, BookFindLoaded, RegionContext?, BookMatchReview, ReviewChoices>,
        copy: YourCopyUi?,
    ): FindUiState {
        val displayedQuery = params.query ?: copy?.title.orEmpty()
        return when (val find = core.find) {
            is MatchFindPhase.Searching -> {
                FindUiState.Searching(
                    yourCopy = copy,
                    query = displayedQuery,
                    previous =
                        find.previous?.let { prev ->
                            resultsOf(
                                loaded = prev,
                                copy = copy,
                                displayedQuery = displayedQuery,
                                pickedKey = core.pickedKey,
                            )
                        },
                )
            }

            is MatchFindPhase.LoadedPhase -> {
                resultsOf(
                    loaded = find.loaded,
                    copy = copy,
                    displayedQuery = displayedQuery,
                    pickedKey = core.pickedKey,
                )
            }

            is MatchFindPhase.Failed -> {
                FindUiState.Failed(
                    yourCopy = copy,
                    query = displayedQuery,
                    failure = find.failure,
                    region = find.meta?.toUi(),
                )
            }

            MatchFindPhase.Empty -> {
                FindUiState.Searching(copy, displayedQuery, null)
            }
        }
    }

    private fun resultsOf(
        loaded: BookFindLoaded,
        copy: YourCopyUi?,
        displayedQuery: String,
        pickedKey: BookCandidateKey?,
    ): FindUiState.Results =
        FindUiState.Results(
            yourCopy = copy,
            steps = loaded.result.steps,
            query = displayedQuery,
            strong = loaded.outcome.strong,
            maybe = loaded.outcome.maybe,
            partialFailure = loaded.outcome.partialFailure,
            region = loaded.result.region?.toUi(),
            pickedKey = pickedKey,
        )

    private fun toReviewState(
        core: MatchCoreState<BookCandidateKey, CandidateUi, BookFindLoaded, RegionContext?, BookMatchReview, ReviewChoices>,
        copy: YourCopyUi?,
    ): ReviewUiState =
        when (val review = core.review) {
            MatchReviewPhase.None -> {
                ReviewUiState.NoneChosen
            }

            is MatchReviewPhase.Loading -> {
                ReviewUiState.Loading(review.candidate)
            }

            is MatchReviewPhase.Failed -> {
                ReviewUiState.Failed(review.candidate, review.error)
            }

            is MatchReviewPhase.Ready -> {
                core.choicesFor(review.candidate.key, ::ReviewChoices).project(
                    candidate = review.candidate,
                    review = review.review,
                    currentCoverPath = copy?.coverPath,
                    applying = review.applying,
                    applyError = review.applyError,
                )
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
