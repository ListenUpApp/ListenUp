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
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.domain.model.RoleWithBookCount
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.MatchingRepository
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

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
    errorBus: ErrorBus,
) : ViewModel() {
    /** What the person typed; null searches by their name. */
    private val query = MutableStateFlow<String?>(null)

    private val controller =
        MatchSessionController<
            PersonCandidateKey,
            PersonCandidateUi,
            PersonFindLoaded,
            Unit,
            PersonMatchReview,
            PersonReviewChoices,
        >(
            scope = viewModelScope,
            errorBus = errorBus,
            defaultChoices = ::PersonReviewChoices,
            keyOf = PersonCandidateUi::key,
            candidatesOf = { loaded ->
                MatchLoadedCandidates(
                    all = loaded.outcome.strong + loaded.outcome.maybe,
                    bestStrong = loaded.outcome.strong.firstOrNull { it.isBest } ?: loaded.outcome.strong.firstOrNull(),
                )
            },
            loadReview = { candidate ->
                matchingRepository.reviewPersonMatch(ContributorId(contributorId), candidate.key)
            },
        )

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
        combine(
            combine(query, controller.state, ::Pair),
            header,
            library,
        ) { (q, core), h, lib ->
            toFindState(typedQuery = q, core = core, header = h, library = lib)
        }.stateIn(
            viewModelScope,
            WhileSubscribedForUi,
            PersonFindUiState.Searching(header = null, inLibrary = null, query = "", previous = null),
        )

    /** The Review step. */
    val reviewState: StateFlow<PersonReviewUiState> =
        controller.state
            .map(::toReviewState)
            .stateIn(viewModelScope, WhileSubscribedForUi, PersonReviewUiState.NoneChosen)

    init {
        runFind()
    }

    /** Searches for [query]; a blank query goes back to searching by the person's name. */
    fun search(query: String) {
        this.query.value = query.trim().ifBlank { null }
        runFind()
    }

    /** Re-runs Find; sources that already answered come back from the server's cache. */
    fun retry() {
        if (controller.isRateLimitCoolingDown()) return
        runFind()
    }

    /** Opens [key]'s Review. */
    fun pick(key: PersonCandidateKey) {
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

    /** Ticks or unticks the photo. Unticking keeps yours; re-ticking restores the last photo chosen. */
    fun setPhotoTicked(ticked: Boolean) {
        controller.updateChoices { choices, review -> choices.setPhotoTicked(review, ticked) }
    }

    /** Picks the photo Apply writes: Keep current, or one source's. */
    fun choosePhoto(choice: ImageChoice) {
        controller.updateChoices { choices, _ -> choices.choosePhoto(choice) }
    }

    /** Ticks or unticks the biography. Unticking keeps yours; re-ticking restores the last source chosen. */
    fun setBiographyTicked(ticked: Boolean) {
        controller.updateChoices { choices, review -> choices.setBiographyTicked(review, ticked) }
    }

    /** Picks the biography's source, or Keep yours. */
    fun chooseBiographySource(choice: FieldChoice) {
        controller.updateChoices { choices, _ -> choices.chooseBiography(choice) }
    }

    /**
     * Applies the photo and the biography in one request. On `ReviewOutdated` the Review reloads with the choices
     * whose options survived and [PersonMatchEvent.ReviewReloaded] fires; any other failure stays on Review, inline.
     */
    fun apply() {
        controller.apply(
            submit = { choices, review ->
                matchingRepository.applyPersonMatch(ContributorId(contributorId), choices.toApply(review))
            },
            onApplied = { receipt ->
                receiptStore.put(contributorId, receipt)
                eventChannel.send(PersonMatchEvent.Applied(receipt))
            },
            onReviewReloaded = {
                eventChannel.send(PersonMatchEvent.ReviewReloaded)
            },
        )
    }

    private fun runFind() {
        val request = PersonFindRequest(query = query.value)
        controller.startFind(failedMeta = Unit) {
            matchingRepository.findPeople(ContributorId(contributorId), request).map { result ->
                when (val outcome = choosePersonFindOutcome(result)) {
                    is PersonFindOutcome.Candidates -> MatchFindPhase.LoadedPhase(PersonFindLoaded(result, outcome))
                    PersonFindOutcome.NoProfiles -> MatchFindPhase.Empty
                    is PersonFindOutcome.Failure -> MatchFindPhase.Failed(outcome.failure, Unit)
                }
            }
        }
    }

    private data class PersonFindLoaded(
        val result: PersonFindResult,
        val outcome: PersonFindOutcome.Candidates,
    )

    private fun toFindState(
        typedQuery: String?,
        core: MatchCoreState<
            PersonCandidateKey,
            PersonCandidateUi,
            PersonFindLoaded,
            Unit,
            PersonMatchReview,
            PersonReviewChoices,
        >,
        header: PersonHeaderUi?,
        library: InLibraryUi,
    ): PersonFindUiState {
        val shownQuery = typedQuery ?: header?.name.orEmpty()
        return when (val find = core.find) {
            is MatchFindPhase.Searching -> {
                PersonFindUiState.Searching(
                    header = header,
                    inLibrary = library,
                    query = shownQuery,
                    previous =
                        find.previous?.let { prev ->
                            resultsOf(
                                loaded = prev,
                                header = header,
                                library = library,
                                shownQuery = shownQuery,
                                pickedKey = core.pickedKey,
                            )
                        },
                )
            }

            is MatchFindPhase.LoadedPhase -> {
                resultsOf(
                    loaded = find.loaded,
                    header = header,
                    library = library,
                    shownQuery = shownQuery,
                    pickedKey = core.pickedKey,
                )
            }

            MatchFindPhase.Empty -> {
                PersonFindUiState.NoProfiles(header, library, shownQuery)
            }

            is MatchFindPhase.Failed -> {
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
        loaded: PersonFindLoaded,
        header: PersonHeaderUi?,
        library: InLibraryUi,
        shownQuery: String,
        pickedKey: PersonCandidateKey?,
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

    private fun toReviewState(
        core: MatchCoreState<
            PersonCandidateKey,
            PersonCandidateUi,
            PersonFindLoaded,
            Unit,
            PersonMatchReview,
            PersonReviewChoices,
        >,
    ): PersonReviewUiState =
        when (val review = core.review) {
            MatchReviewPhase.None -> {
                PersonReviewUiState.NoneChosen
            }

            is MatchReviewPhase.Loading -> {
                PersonReviewUiState.Loading(review.candidate)
            }

            is MatchReviewPhase.Failed -> {
                PersonReviewUiState.Failed(review.candidate, review.error)
            }

            is MatchReviewPhase.Ready -> {
                core.choicesFor(review.candidate.key, ::PersonReviewChoices).project(
                    candidate = review.candidate,
                    review = review.review,
                    applying = review.applying,
                    applyError = review.applyError,
                )
            }
        }
}

/** Room's per-role counts as credits, most books first (role order on a tie); roles this build doesn't know drop. */
private fun List<RoleWithBookCount>.toCredits(): List<LibraryCredit> =
    mapNotNull { row -> ContributorRole.fromApiValue(row.role)?.let { LibraryCredit(it, row.bookCount) } }
        .filter { it.bookCount > 0 }
        .sortedWith(compareByDescending<LibraryCredit> { it.bookCount }.thenBy { it.role.ordinal })
