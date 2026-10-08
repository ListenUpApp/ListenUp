package com.calypsan.listenup.client.presentation.match

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.LibraryCredit
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.RoleWithBookCount
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.MatchingRepository
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val COUNTDOWN_TICK_MS = 1_000L

/**
 * One Match details session for one person. Find, Review and Apply share this ViewModel, so going back never
 * re-searches. There is no role to choose: a role on one book doesn't say who someone is, so Find looks for the
 * person in every role any source knows, and the Your-library strip and each row say what they did here — every
 * role, read from Room for the strip so Find is never blank.
 *
 * Apply writes the photo and the biography, chosen separately, in one request; on success the receipt goes to
 * [MatchReceiptStore] for the contributor page and [PersonMatchEvent.Applied] tells the screen to return there.
 */
class PersonMatchViewModel internal constructor(
    private val contributorId: String,
    private val matchingRepository: MatchingRepository,
    contributorRepository: ContributorRepository,
    private val receiptStore: MatchReceiptStore,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val session = MutableStateFlow(Session())
    private var findJob: Job? = null
    private var countdownJob: Job? = null
    private var reviewJob: Job? = null

    private val header: Flow<PersonHeaderUi?> =
        contributorRepository.observeById(contributorId).map { it?.let { c -> PersonHeaderUi(c.name, c.imagePath) } }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val library: Flow<InLibraryUi> =
        contributorRepository.observeRolesWithCountForContributor(contributorId).flatMapLatest { roles ->
            val credits = roles.toCredits()
            if (credits.isEmpty()) {
                flowOf(InLibraryUi(credits = emptyList(), bookCount = 0, titles = emptyList(), covers = emptyList()))
            } else {
                combine(
                    credits.map { credit ->
                        contributorRepository.observeBooksForContributorRole(
                            contributorId,
                            credit.role.apiValue,
                        )
                    },
                ) { byRole ->
                    val books = byRole.flatMap { it.toList() }.map { it.book }.distinctBy { it.id }
                    val shown = books.take(LIBRARY_STRIP_BOOKS)
                    InLibraryUi(
                        credits = credits,
                        bookCount = books.size,
                        titles = shown.map { it.title },
                        covers =
                            shown.map { book ->
                                LibraryCoverUi(
                                    bookId = book.id.value,
                                    title = book.title,
                                    coverPath = book.coverPath,
                                    coverHash = book.coverHash,
                                )
                            },
                    )
                }
            }
        }

    private val eventChannel = Channel<PersonMatchEvent>(Channel.BUFFERED)

    /** One-shot outcomes: Applied (return to the contributor page) and ReviewReloaded. */
    val events: Flow<PersonMatchEvent> = eventChannel.receiveAsFlow()

    /** The Find step. */
    val findState: StateFlow<PersonFindUiState> =
        combine(session, header, library) { s, h, lib -> s.toFindState(h, lib) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                PersonFindUiState.Searching(header = null, inLibrary = null, query = "", previous = null),
            )

    /** The Review step. */
    val reviewState: StateFlow<PersonReviewUiState> =
        session
            .map { it.toReviewState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonReviewUiState.NoneChosen)

    init {
        runFind()
    }

    /** Searches for [query]; a blank query goes back to searching by the person's name. */
    fun search(query: String) {
        session.update { it.copy(query = query.trim().ifBlank { null }) }
        runFind()
    }

    /** Re-runs Find; sources that already answered come back from the server's cache. */
    fun retry() {
        val failed = session.value.find as? FindPhase.Failed
        if ((failed?.failure as? FindFailure.RateLimited)?.run { secondsRemaining > 0 } == true) return
        runFind()
    }

    /** Opens [key]'s Review. */
    fun pick(key: PersonCandidateKey) {
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

    /** Ticks or unticks the photo. Unticking keeps yours; re-ticking restores the last photo chosen. */
    fun setPhotoTicked(ticked: Boolean) {
        updateChoices { choices, review -> choices.setPhotoTicked(review, ticked) }
    }

    /** Picks the photo Apply writes: Keep current, or one source's. */
    fun choosePhoto(choice: ImageChoice) {
        updateChoices { choices, _ -> choices.choosePhoto(choice) }
    }

    /** Ticks or unticks the biography. Unticking keeps yours; re-ticking restores the last source chosen. */
    fun setBiographyTicked(ticked: Boolean) {
        updateChoices { choices, review -> choices.setBiographyTicked(review, ticked) }
    }

    /** Picks the biography's source, or Keep yours. */
    fun chooseBiographySource(choice: FieldChoice) {
        updateChoices { choices, _ -> choices.chooseBiography(choice) }
    }

    /**
     * Applies the photo and the biography in one request. On `ReviewOutdated` the Review reloads with the choices
     * whose options survived and [PersonMatchEvent.ReviewReloaded] fires; any other failure stays on Review, inline.
     */
    fun apply() {
        val s = session.value
        val ready = s.review as? ReviewPhase.Ready ?: return
        if (ready.applying) return
        val request = s.choicesFor(ready.candidate.key).toApply(ready.review)
        updateReady { it.copy(applying = true, applyError = null) }
        viewModelScope.launch {
            when (val result = matchingRepository.applyPersonMatch(ContributorId(contributorId), request)) {
                is AppResult.Success -> {
                    receiptStore.put(contributorId, result.data)
                    updateReady { it.copy(applying = false) }
                    eventChannel.send(PersonMatchEvent.Applied(result.data))
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

    private suspend fun reloadAfterOutdated(candidate: PersonCandidateUi) {
        when (val reloaded = review(candidate)) {
            is AppResult.Success -> {
                session.update { current ->
                    current.copy(
                        review =
                            ReviewPhase.Ready(
                                candidate = candidate,
                                review = reloaded.data,
                                applying = false,
                                applyError = null,
                            ),
                    )
                }
                eventChannel.send(PersonMatchEvent.ReviewReloaded)
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
        session.update { s ->
            s.copy(find = FindPhase.Searching(previous = s.find as? FindPhase.Loaded ?: s.lastLoaded))
        }
        val request = PersonFindRequest(query = session.value.query)
        findJob =
            viewModelScope.launch {
                when (val result = matchingRepository.findPeople(ContributorId(contributorId), request)) {
                    is AppResult.Success -> {
                        onFound(result.data)
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        session.update { it.copy(find = FindPhase.Failed(findFailureFor(result.error))) }
                    }
                }
            }
    }

    private fun onFound(result: PersonFindResult) {
        when (val outcome = choosePersonFindOutcome(result)) {
            is PersonFindOutcome.Candidates -> {
                val loaded = FindPhase.Loaded(result, outcome)
                session.update { it.copy(find = loaded, lastLoaded = loaded) }
                autoPickBest()
            }

            PersonFindOutcome.NoProfiles -> {
                session.update { it.copy(find = FindPhase.NoProfiles) }
            }

            is PersonFindOutcome.Failure -> {
                session.update { it.copy(find = FindPhase.Failed(outcome.failure)) }
                (outcome.failure as? FindFailure.RateLimited)?.let { countDown(it) }
            }
        }
    }

    private fun countDown(start: FindFailure.RateLimited) {
        countdownJob?.cancel()
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

    private fun loadReview(candidate: PersonCandidateUi) {
        reviewJob?.cancel()
        reviewJob =
            viewModelScope.launch {
                when (val result = review(candidate)) {
                    is AppResult.Success -> {
                        session.update { current ->
                            current.copy(
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

    private suspend fun review(candidate: PersonCandidateUi): AppResult<PersonMatchReview> =
        matchingRepository.reviewPersonMatch(ContributorId(contributorId), candidate.key)

    private fun updateChoices(transform: (PersonReviewChoices, PersonMatchReview) -> PersonReviewChoices) {
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

        /** Find returned people. */
        data class Loaded(
            val result: PersonFindResult,
            val outcome: PersonFindOutcome.Candidates,
        ) : FindPhase {
            val candidates: List<PersonCandidateUi> get() = outcome.strong + outcome.maybe
        }

        /** No source has a profile for the person. */
        data object NoProfiles : FindPhase

        /** Find returned nobody because something failed. */
        data class Failed(
            val failure: FindFailure,
        ) : FindPhase
    }

    private sealed interface ReviewPhase {
        data object None : ReviewPhase

        /** The Review for this candidate is loading. */
        data class Loading(
            val candidate: PersonCandidateUi,
        ) : ReviewPhase

        /** The Review is loaded; Apply may be running. */
        data class Ready(
            val candidate: PersonCandidateUi,
            val review: PersonMatchReview,
            val applying: Boolean,
            val applyError: AppError?,
        ) : ReviewPhase

        /** The Review couldn't load. */
        data class Failed(
            val candidate: PersonCandidateUi,
            val error: AppError,
        ) : ReviewPhase
    }

    private data class Session(
        /** What the person typed; null searches by their name. */
        val query: String? = null,
        val find: FindPhase = FindPhase.Searching(previous = null),
        val lastLoaded: FindPhase.Loaded? = null,
        /** The row last opened in Review. */
        val pickedKey: PersonCandidateKey? = null,
        val review: ReviewPhase = ReviewPhase.None,
        val choices: Map<PersonCandidateKey, PersonReviewChoices> = emptyMap(),
        val twoPane: Boolean = false,
    ) {
        fun choicesFor(key: PersonCandidateKey): PersonReviewChoices = choices[key] ?: PersonReviewChoices()

        fun toFindState(
            header: PersonHeaderUi?,
            library: InLibraryUi,
        ): PersonFindUiState {
            val shownQuery = query ?: header?.name.orEmpty()
            return when (val find = find) {
                is FindPhase.Searching -> {
                    PersonFindUiState.Searching(
                        header = header,
                        inLibrary = library,
                        query = shownQuery,
                        previous =
                            find.previous?.let { previousResults ->
                                resultsOf(
                                    loaded = previousResults,
                                    header = header,
                                    library = library,
                                    shownQuery = shownQuery,
                                )
                            },
                    )
                }

                is FindPhase.Loaded -> {
                    resultsOf(loaded = find, header = header, library = library, shownQuery = shownQuery)
                }

                FindPhase.NoProfiles -> {
                    PersonFindUiState.NoProfiles(header, library, shownQuery)
                }

                is FindPhase.Failed -> {
                    PersonFindUiState.Failed(
                        header = header,
                        inLibrary = library,
                        query = shownQuery,
                        failure = find.failure,
                    )
                }
            }
        }

        private fun resultsOf(
            loaded: FindPhase.Loaded,
            header: PersonHeaderUi?,
            library: InLibraryUi,
            shownQuery: String,
        ): PersonFindUiState.Results =
            PersonFindUiState.Results(
                header = header,
                inLibrary = library,
                query = shownQuery,
                steps = loaded.result.steps,
                strong = loaded.outcome.strong,
                maybe = loaded.outcome.maybe,
                partialFailure = loaded.outcome.partialFailure,
                pickedKey = pickedKey,
            )

        fun toReviewState(): PersonReviewUiState =
            when (val review = review) {
                ReviewPhase.None -> {
                    PersonReviewUiState.NoneChosen
                }

                is ReviewPhase.Loading -> {
                    PersonReviewUiState.Loading(review.candidate)
                }

                is ReviewPhase.Failed -> {
                    PersonReviewUiState.Failed(review.candidate, review.error)
                }

                is ReviewPhase.Ready -> {
                    choicesFor(review.candidate.key).project(
                        candidate = review.candidate,
                        review = review.review,
                        applying = review.applying,
                        applyError = review.applyError,
                    )
                }
            }
    }
}

/** Room's per-role counts as credits, most books first (role order on a tie); roles this build doesn't know drop. */
private fun List<RoleWithBookCount>.toCredits(): List<LibraryCredit> =
    mapNotNull { row -> ContributorRole.fromApiValue(row.role)?.let { LibraryCredit(it, row.bookCount) } }
        .filter { it.bookCount > 0 }
        .sortedWith(compareByDescending<LibraryCredit> { it.bookCount }.thenBy { it.role.ordinal })
