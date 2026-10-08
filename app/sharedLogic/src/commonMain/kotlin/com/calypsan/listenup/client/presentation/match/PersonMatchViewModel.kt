package com.calypsan.listenup.client.presentation.match

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.BookWithContributorRole
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.MatchingRepository
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val COUNTDOWN_TICK_MS = 1_000L

/** The roles a person can be matched in. */
private val MATCHABLE_ROLES = listOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR)

/**
 * One Match details session for one person. Find, Review and Apply share this ViewModel, so going back never
 * re-searches. The person is matched in a role — As author or As narrator — and each role keeps its own query
 * and results: switching back to a role already searched shows its results again without asking the server.
 *
 * The session starts in the role the person is credited with on the most books here (author on a tie), read
 * from Room along with the header and the Your-library strip, so Find is never blank. Apply writes the photo and
 * the biography, chosen separately, in one request; on success the receipt goes to [MatchReceiptStore] for the
 * contributor page and [PersonMatchEvent.Applied] tells the screen to return there.
 */
class PersonMatchViewModel internal constructor(
    private val contributorId: String,
    private val matchingRepository: MatchingRepository,
    contributorRepository: ContributorRepository,
    private val receiptStore: MatchReceiptStore,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val session = MutableStateFlow(Session())
    private val findJobs = mutableMapOf<ContributorRole, Job>()
    private var countdownJob: Job? = null
    private var reviewJob: Job? = null

    private val header: Flow<PersonHeaderUi?> =
        contributorRepository.observeById(contributorId).map { it?.let { c -> PersonHeaderUi(c.name, c.imagePath) } }

    private val library: Flow<Map<ContributorRole, InLibraryUi>> =
        combine(
            MATCHABLE_ROLES.map { role ->
                contributorRepository.observeBooksForContributorRole(contributorId, role.apiValue).map {
                    role to
                        it.toInLibrary(role)
                }
            },
        ) { it.toMap() }

    private val eventChannel = Channel<PersonMatchEvent>(Channel.BUFFERED)

    /** One-shot outcomes: Applied (return to the contributor page) and ReviewReloaded. */
    val events: Flow<PersonMatchEvent> = eventChannel.receiveAsFlow()

    /** The Find step, for the role selected. */
    val findState: StateFlow<PersonFindUiState> =
        combine(session, header, library) { s, h, lib -> s.toFindState(h, lib) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                PersonFindUiState.Searching(ContributorRole.AUTHOR, null, null, "", null),
            )

    /** The Review step. */
    val reviewState: StateFlow<PersonReviewUiState> =
        session
            .map { it.toReviewState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonReviewUiState.NoneChosen)

    init {
        viewModelScope.launch {
            val lib = library.first()
            val narrated = lib[ContributorRole.NARRATOR]?.bookCount ?: 0
            val written = lib[ContributorRole.AUTHOR]?.bookCount ?: 0
            val role = if (narrated > written) ContributorRole.NARRATOR else ContributorRole.AUTHOR
            if (session.value.role == null) {
                session.update { it.copy(role = role) }
                runFind(role)
            }
        }
    }

    /** Searches for [query] in the selected role; a blank query goes back to searching by the person's name. */
    fun search(query: String) {
        val role = session.value.role ?: return
        session.update { it.withSearch(role) { search -> search.copy(query = query.trim().ifBlank { null }) } }
        runFind(role)
    }

    /**
     * Matches the person As author or As narrator. A role not yet searched (or whose search never finished)
     * searches now; one already searched shows its results again. The open Review closes.
     */
    fun switchRole(role: ContributorRole) {
        if (role !in MATCHABLE_ROLES || role == session.value.role) return
        reviewJob?.cancel()
        session.update { it.copy(role = role, review = ReviewPhase.None) }
        val find = session.value.searches[role.apiValue]?.find
        if (find == null || (find is FindPhase.Searching && findJobs[role]?.isActive != true)) {
            runFind(role)
        } else {
            autoPickBest()
        }
    }

    /** Re-runs the selected role's Find; sources that already answered come back from the server's cache. */
    fun retry() {
        val s = session.value
        val role = s.role ?: return
        val failed = s.searches[role.apiValue]?.find as? FindPhase.Failed
        if ((failed?.failure as? FindFailure.RateLimited)?.secondsRemaining?.let { it > 0 } == true) return
        runFind(role)
    }

    /** Opens [key]'s Review in the selected role. */
    fun pick(key: PersonCandidateKey) {
        val s = session.value
        val role = s.role ?: return
        val loaded = s.searches[role.apiValue]?.find as? FindPhase.Loaded ?: return
        val candidate = loaded.candidates.firstOrNull { it.key == key } ?: return
        session.update {
            it
                .withSearch(
                    role,
                ) { search -> search.copy(pickedKey = key) }
                .copy(review = ReviewPhase.Loading(role, candidate))
        }
        loadReview(role, candidate)
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
    fun setPhotoTicked(ticked: Boolean) = updateChoices { choices, review -> choices.setPhotoTicked(review, ticked) }

    /** Picks the photo Apply writes: Keep current, or one source's. */
    fun choosePhoto(choice: ImageChoice) = updateChoices { choices, _ -> choices.choosePhoto(choice) }

    /** Ticks or unticks the biography. Unticking keeps yours; re-ticking restores the last source chosen. */
    fun setBiographyTicked(ticked: Boolean) =
        updateChoices { choices, review -> choices.setBiographyTicked(review, ticked) }

    /** Picks the biography's source, or Keep yours. */
    fun chooseBiographySource(choice: FieldChoice) = updateChoices { choices, _ -> choices.chooseBiography(choice) }

    /**
     * Applies the photo and the biography in one request. On `ReviewOutdated` the Review reloads with the choices
     * whose options survived and [PersonMatchEvent.ReviewReloaded] fires; any other failure stays on Review, inline.
     */
    fun apply() {
        val s = session.value
        val ready = s.review as? ReviewPhase.Ready ?: return
        if (ready.applying) return
        val request = s.choicesFor(ready.role, ready.candidate.key).toApply(ready.review)
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
                        reloadAfterOutdated(ready.role, ready.candidate)
                    } else {
                        errorBus.emit(result.error)
                        updateReady { it.copy(applying = false, applyError = result.error) }
                    }
                }
            }
        }
    }

    private suspend fun reloadAfterOutdated(
        role: ContributorRole,
        candidate: PersonCandidateUi,
    ) {
        when (val reloaded = review(role, candidate)) {
            is AppResult.Success -> {
                session.update { it.copy(review = ReviewPhase.Ready(role, candidate, reloaded.data, false, null)) }
                eventChannel.send(PersonMatchEvent.ReviewReloaded)
            }

            is AppResult.Failure -> {
                errorBus.emit(reloaded.error)
                session.update { it.copy(review = ReviewPhase.Failed(role, candidate, reloaded.error)) }
            }
        }
    }

    private fun runFind(role: ContributorRole) {
        findJobs[role]?.cancel()
        if (role == session.value.role) countdownJob?.cancel()
        session.update { s ->
            s.withSearch(role) { search ->
                search.copy(
                    find = FindPhase.Searching(previous = search.find as? FindPhase.Loaded ?: search.lastLoaded),
                )
            }
        }
        val request = PersonFindRequest(role = role, query = session.value.searches[role.apiValue]?.query)
        findJobs[role] =
            viewModelScope.launch {
                when (val result = matchingRepository.findPeople(ContributorId(contributorId), request)) {
                    is AppResult.Success -> {
                        onFound(role, result.data)
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        session.update { s ->
                            s.withSearch(role) { it.copy(find = FindPhase.Failed(findFailureFor(result.error))) }
                        }
                    }
                }
            }
    }

    private fun onFound(
        role: ContributorRole,
        result: PersonFindResult,
    ) {
        when (val outcome = choosePersonFindOutcome(result)) {
            is PersonFindOutcome.Candidates -> {
                val loaded = FindPhase.Loaded(result, outcome)
                session.update { s -> s.withSearch(role) { it.copy(find = loaded, lastLoaded = loaded) } }
                autoPickBest()
            }

            is PersonFindOutcome.NoProfiles -> {
                session.update { s ->
                    s.withSearch(role) { it.copy(find = FindPhase.NoProfiles(outcome.coverageNote)) }
                }
            }

            is PersonFindOutcome.Failure -> {
                session.update { s -> s.withSearch(role) { it.copy(find = FindPhase.Failed(outcome.failure)) } }
                (outcome.failure as? FindFailure.RateLimited)?.let { countDown(role, it) }
            }
        }
    }

    private fun countDown(
        role: ContributorRole,
        start: FindFailure.RateLimited,
    ) {
        countdownJob?.cancel()
        countdownJob =
            viewModelScope.launch {
                var remaining = start.secondsRemaining
                while (remaining > 0) {
                    delay(COUNTDOWN_TICK_MS)
                    remaining--
                    val left = remaining
                    session.update { s ->
                        s.withSearch(role) { search ->
                            val failed = search.find as? FindPhase.Failed ?: return@withSearch search
                            val limited = failed.failure as? FindFailure.RateLimited ?: return@withSearch search
                            search.copy(find = failed.copy(failure = limited.copy(secondsRemaining = left)))
                        }
                    }
                }
            }
    }

    private fun autoPickBest() {
        val s = session.value
        val role = s.role ?: return
        val search = s.searches[role.apiValue] ?: return
        if (!s.twoPane || s.review != ReviewPhase.None || search.pickedKey != null) return
        val loaded = search.find as? FindPhase.Loaded ?: return
        val best = loaded.outcome.strong.firstOrNull { it.isBest } ?: loaded.outcome.strong.firstOrNull() ?: return
        pick(best.key)
    }

    private fun loadReview(
        role: ContributorRole,
        candidate: PersonCandidateUi,
    ) {
        reviewJob?.cancel()
        reviewJob =
            viewModelScope.launch {
                when (val result = review(role, candidate)) {
                    is AppResult.Success -> {
                        session.update {
                            it.copy(
                                review = ReviewPhase.Ready(role, candidate, result.data, false, null),
                            )
                        }
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        session.update { it.copy(review = ReviewPhase.Failed(role, candidate, result.error)) }
                    }
                }
            }
    }

    private suspend fun review(
        role: ContributorRole,
        candidate: PersonCandidateUi,
    ): AppResult<PersonMatchReview> =
        matchingRepository.reviewPersonMatch(ContributorId(contributorId), candidate.key, role)

    private fun updateChoices(transform: (PersonReviewChoices, PersonMatchReview) -> PersonReviewChoices) {
        session.update { s ->
            val ready = s.review as? ReviewPhase.Ready ?: return@update s
            if (ready.applying) return@update s
            val key = ChoiceKey(ready.role, ready.candidate.key)
            s.copy(
                choices = s.choices + (key to transform(s.choicesFor(ready.role, ready.candidate.key), ready.review)),
            )
        }
    }

    private fun updateReady(transform: (ReviewPhase.Ready) -> ReviewPhase.Ready) {
        session.update { s ->
            val ready = s.review as? ReviewPhase.Ready ?: return@update s
            s.copy(review = transform(ready))
        }
    }

    private sealed interface FindPhase {
        /** A Find is running; the role's last results stay on screen. */
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

        /** No source has a profile for the person in this role. */
        data class NoProfiles(
            val coverageNote: CoverageNote?,
        ) : FindPhase

        /** Find returned nobody because something failed. */
        data class Failed(
            val failure: FindFailure,
        ) : FindPhase
    }

    private sealed interface ReviewPhase {
        data object None : ReviewPhase

        /** The Review for this candidate is loading. */
        data class Loading(
            val role: ContributorRole,
            val candidate: PersonCandidateUi,
        ) : ReviewPhase

        /** The Review is loaded; Apply may be running. */
        data class Ready(
            val role: ContributorRole,
            val candidate: PersonCandidateUi,
            val review: PersonMatchReview,
            val applying: Boolean,
            val applyError: AppError?,
        ) : ReviewPhase

        /** The Review couldn't load. */
        data class Failed(
            val role: ContributorRole,
            val candidate: PersonCandidateUi,
            val error: AppError,
        ) : ReviewPhase
    }

    /** One role's search: what the person typed (null = their name), its Find, and the row last opened. */
    private data class RoleSearch(
        val query: String? = null,
        val find: FindPhase = FindPhase.Searching(previous = null),
        val lastLoaded: FindPhase.Loaded? = null,
        val pickedKey: PersonCandidateKey? = null,
    )

    private data class ChoiceKey(
        val role: ContributorRole,
        val key: PersonCandidateKey,
    )

    private data class Session(
        /** The role selected; null for the instant before Room says which role to start in. */
        val role: ContributorRole? = null,
        /** Each role's search, by [ContributorRole.apiValue] (an enum-keyed map would trap if it ever reached Swift). */
        val searches: Map<String, RoleSearch> = emptyMap(),
        val review: ReviewPhase = ReviewPhase.None,
        val choices: Map<ChoiceKey, PersonReviewChoices> = emptyMap(),
        val twoPane: Boolean = false,
    ) {
        fun choicesFor(
            role: ContributorRole,
            key: PersonCandidateKey,
        ): PersonReviewChoices = choices[ChoiceKey(role, key)] ?: PersonReviewChoices()

        fun withSearch(
            role: ContributorRole,
            transform: (RoleSearch) -> RoleSearch,
        ): Session = copy(searches = searches + (role.apiValue to transform(searchFor(role))))

        fun searchFor(role: ContributorRole): RoleSearch = searches[role.apiValue] ?: RoleSearch()

        fun toFindState(
            header: PersonHeaderUi?,
            library: Map<ContributorRole, InLibraryUi>,
        ): PersonFindUiState {
            val shownRole = role ?: ContributorRole.AUTHOR
            val search = searchFor(shownRole)
            val strip = library[shownRole]
            val query = search.query ?: header?.name.orEmpty()
            return when (val find = search.find) {
                is FindPhase.Searching -> {
                    PersonFindUiState.Searching(
                        shownRole,
                        header,
                        strip,
                        query,
                        find.previous?.let { resultsOf(shownRole, it, search, header, strip, query) },
                    )
                }

                is FindPhase.Loaded -> {
                    resultsOf(shownRole, find, search, header, strip, query)
                }

                is FindPhase.NoProfiles -> {
                    PersonFindUiState.NoProfiles(shownRole, header, strip, query, find.coverageNote)
                }

                is FindPhase.Failed -> {
                    PersonFindUiState.Failed(shownRole, header, strip, query, find.failure)
                }
            }
        }

        private fun resultsOf(
            role: ContributorRole,
            loaded: FindPhase.Loaded,
            search: RoleSearch,
            header: PersonHeaderUi?,
            strip: InLibraryUi?,
            query: String,
        ): PersonFindUiState.Results =
            PersonFindUiState.Results(
                role = role,
                header = header,
                inLibrary = strip,
                query = query,
                steps = loaded.result.steps,
                coverageNote = loaded.outcome.coverageNote,
                strong = loaded.outcome.strong,
                maybe = loaded.outcome.maybe,
                partialFailure = loaded.outcome.partialFailure,
                pickedKey = search.pickedKey,
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
                    choicesFor(review.role, review.candidate.key).project(
                        candidate = review.candidate,
                        review = review.review,
                        applying = review.applying,
                        applyError = review.applyError,
                    )
                }
            }
    }
}

private fun List<BookWithContributorRole>.toInLibrary(role: ContributorRole): InLibraryUi {
    val shown = take(LIBRARY_STRIP_BOOKS).map { it.book }
    return InLibraryUi(
        role = role,
        bookCount = size,
        titles = shown.map { it.title },
        covers = shown.map { LibraryCoverUi(it.id.value, it.title, it.coverPath, it.coverHash) },
    )
}
