package com.calypsan.listenup.client.presentation.bulkedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.domain.bulkedit.BulkEditApplier
import com.calypsan.listenup.client.domain.bulkedit.actionsFor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** How long the typing has to stop before a relation search costs a query. */
private const val SEARCH_DEBOUNCE_MS = 300L

/** Below this, a query matches most of the library and the results are noise rather than a shortlist. */
private const val MIN_SEARCH_QUERY_LENGTH = 2

/** How many matches a picker offers. Past this the list stops being a shortlist. */
private const val SEARCH_LIMIT = 10

/**
 * How many of the selection's covers the header gets to show.
 *
 * Five is as many as a cluster reads as before it becomes a smear; past that the header states the
 * remainder as a number instead. The cap lives here rather than in the screen so the state a client
 * renders is already the size it will draw — no client carries forty books' worth of projection
 * through a flow that recomputes on every keystroke.
 */
private const val SELECTION_SAMPLE_SIZE = 5

/**
 * Bulk metadata editing across a selection, shared by Android, iOS and web.
 *
 * State is a **projection** of the loaded books and the instructions built so far. The preview and
 * the work Apply does both come from `actionsFor`, so the count on screen cannot disagree with what
 * happens — there is no second code path to fall out of step.
 *
 * Every client drives it the same way: read [state], call the setters, collect [events], and call
 * [close] when the screen goes.
 *
 * The constructor is `internal` because [BulkEditApplier] is — the planner and the applier are one
 * mechanism inside `:app:sharedLogic` and neither belongs on the export surface. The class itself
 * stays public so `:app:sharedUI` can resolve it through `koinViewModel<BulkEditViewModel>()`.
 */
class BulkEditViewModel internal constructor(
    private val bookIds: List<String>,
    private val bookRepository: BookRepository,
    private val applier: BulkEditApplier,
    private val errorBus: ErrorBus,
    private val seriesRepository: SeriesRepository,
    private val contributorRepository: ContributorRepository,
    genreRepository: GenreRepository,
    tagRepository: TagRepository,
    moodRepository: MoodRepository,
) : ViewModel() {
    private var closed = false

    private val books = MutableStateFlow<List<BookDetail>?>(null)
    private val edits = MutableStateFlow<List<BulkEdit>>(emptyList())
    private val applying = MutableStateFlow(false)

    private val eventChannel = Channel<BulkEditEvent>(Channel.BUFFERED)

    /** Apply outcomes, delivered once. */
    val events: Flow<BulkEditEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<BulkEditUiState> =
        combine(books, edits, applying) { loaded, current, isApplying ->
            if (loaded == null) {
                BulkEditUiState.Loading
            } else {
                BulkEditUiState.Editing(
                    bookCount = loaded.size,
                    requestedCount = bookIds.size,
                    edits = current,
                    preview =
                        current.map { edit ->
                            BulkEditPreviewRow(
                                edit = edit,
                                // Counted through the same function that will do the work.
                                affectedCount = loaded.count { listOf(edit).actionsFor(it).isNotEmpty() },
                            )
                        },
                    // The same planning function the preview and apply() use, so the number on
                    // the button cannot disagree with what Apply then reports.
                    changedBookCount = loaded.count { current.actionsFor(it).isNotEmpty() },
                    isApplying = isApplying,
                    sharedPublisher = loaded.sharedBy { it.publisher },
                    sharedPublishYear = loaded.sharedBy { it.publishYear },
                    sharedLanguage = loaded.sharedBy { it.language },
                    // Covers for the header, capped: enough to make "Edit 37 books" concrete, not
                    // so many that the projection grows with the selection.
                    selectionSample = loaded.take(SELECTION_SAMPLE_SIZE).map { it.toListItem() },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), BulkEditUiState.Loading)

    /**
     * Every genre the library holds, for the genre picker to offer.
     *
     * A bulk edit adds *existing* things. Minting a genre, tag or mood forty books at a time is how a
     * library ends up with `found-family`, `Found Family` and `found family` as three different
     * things — so the pickers offer what is already there and nothing else. The lists come from Room,
     * so they are there offline, like search.
     */
    val genres: StateFlow<List<Genre>> =
        genreRepository
            .observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    /** Every tag the library holds, as [genres]. */
    val tags: StateFlow<List<Tag>> =
        tagRepository
            .observeAllTags()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    /** Every mood the library holds, as [genres]. */
    val moods: StateFlow<List<Mood>> =
        moodRepository
            .observeAllMoods()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    /**
     * Turns a picker's query into its matches: debounced, latest-only, and empty until the query is
     * worth running.
     *
     * `flatMapLatest` rather than `map` so an in-flight search for "tol" is cancelled when "tolk"
     * arrives — otherwise the slower query can land last and put stale names under a newer word.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun <T> StateFlow<String>.searchResults(search: suspend (String) -> List<T>): StateFlow<List<T>> =
        debounce(SEARCH_DEBOUNCE_MS)
            .flatMapLatest { query ->
                flow {
                    emit(if (query.trim().length < MIN_SEARCH_QUERY_LENGTH) emptyList() else search(query.trim()))
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                emptyList(),
            )

    private val seriesQuery = MutableStateFlow("")
    private val contributorQuery = MutableStateFlow("")

    /**
     * Series matching what has been typed into the series picker.
     *
     * Series and contributors are searched rather than listed, because a library holds far more of
     * them than a picker can show — the same reason the single-book editor searches them. Short
     * queries match most of the library, so they return nothing rather than noise.
     */
    val seriesMatches: StateFlow<List<SeriesSearchResult>> =
        seriesQuery.searchResults { seriesRepository.searchSeries(it, limit = SEARCH_LIMIT).series }

    /** Contributors matching what has been typed into the contributor picker, as [seriesMatches]. */
    val contributorMatches: StateFlow<List<ContributorSearchResult>> =
        contributorQuery.searchResults {
            contributorRepository.searchContributors(it, limit = SEARCH_LIMIT).contributors
        }

    init {
        viewModelScope.launch { loadSelection() }
    }

    /** What the series picker is searching for. Blank clears the matches. */
    fun setSeriesQuery(query: String) {
        seriesQuery.value = query
    }

    /** What the contributor picker is searching for, as [setSeriesQuery]. */
    fun setContributorQuery(query: String) {
        contributorQuery.value = query
    }

    /**
     * Sets the publisher, or removes the instruction when [publisher] is blank.
     *
     * A value past [BookUpdate.MAX_PUBLISHER] is **refused rather than recorded**, leaving the last
     * good one in place. These setters are driven by a text field, so every intermediate string a
     * user types arrives here, and [BulkEdit] validates eagerly — which is what keeps `actionsFor`
     * total. Without this guard the 201st character would not be rejected, it would throw.
     */
    fun setPublisher(publisher: String) {
        if (publisher.length > BookUpdate.MAX_PUBLISHER) return
        replace<BulkEdit.SetPublisher>(publisher.takeIf { it.isNotBlank() }?.let { BulkEdit.SetPublisher(it) })
    }

    /** Sets the publication year, or removes the instruction when [year] is null. */
    fun setYear(year: Int?) {
        if (year != null && year !in BookUpdate.MIN_YEAR..BookUpdate.MAX_YEAR) return
        replace<BulkEdit.SetPublishYear>(year?.let { BulkEdit.SetPublishYear(it) })
    }

    /** Sets the language, or removes the instruction when [language] is blank. */
    fun setLanguage(language: String) {
        if (language.length > BookUpdate.MAX_LANGUAGE) return
        replace<BulkEdit.SetLanguage>(language.takeIf { it.isNotBlank() }?.let { BulkEdit.SetLanguage(it) })
    }

    /**
     * Replaces the tag instruction. An empty list removes it.
     *
     * [names] are display names, not slugs: the repository slugifies server-side, so a slug passed
     * here would mint a tag literally called `found-family`.
     */
    fun setTags(names: List<String>) =
        replace<BulkEdit.AddTags>(names.takeIf { it.isNotEmpty() }?.let { BulkEdit.AddTags(it) })

    /** Replaces the mood instruction. An empty list removes it. [names] are display names, as in [setTags]. */
    fun setMoods(names: List<String>) =
        replace<BulkEdit.AddMoods>(names.takeIf { it.isNotEmpty() }?.let { BulkEdit.AddMoods(it) })

    /**
     * Replaces the series instruction. Null removes it.
     *
     * One series, not a list: [BulkEdit.AddToSeries] carries one, and the planner drops its position
     * whatever it holds — a single sequence number across forty books would make every one of them
     * Book 1.
     */
    fun setSeries(series: BookSeriesInput?) = replace<BulkEdit.AddToSeries>(series?.let { BulkEdit.AddToSeries(it) })

    /**
     * Replaces the contributor instruction. An empty list removes it.
     *
     * Each entry carries its own role, because one bulk edit may well be crediting an author and a
     * narrator at once. Positions are ignored — the planner renumbers each book's credits.
     */
    fun setContributors(contributors: List<BookContributorInput>) =
        replace<BulkEdit.AddContributors>(
            contributors.takeIf { it.isNotEmpty() }?.let { BulkEdit.AddContributors(it) },
        )

    /** Replaces the genre instruction. An empty list removes it. */
    fun setGenres(genres: List<BookGenreInput>) =
        replace<BulkEdit.AddGenres>(genres.takeIf { it.isNotEmpty() }?.let { BulkEdit.AddGenres(it) })

    /**
     * Applies every instruction to every selected book.
     *
     * Each book is planned separately, so a book the instructions already satisfy costs nothing —
     * no call, no outbox row, no sync frame. Stops at the first failure and reports how many books
     * were committed before it; those stand, because forty books are forty independent intents.
     *
     * A failure goes to **both** the [ErrorBus] and [events], deliberately. Compose reads the
     * ErrorBus for its global snackbar; iOS consumes nothing from the ErrorBus at all, so the event
     * is its only path to the failure — and the event carries the committed count, which the bus
     * cannot. Neither is redundant: do not "de-duplicate" this.
     */
    fun apply() {
        val loaded = books.value ?: return
        val current = edits.value
        if (applying.value || current.isEmpty()) return

        viewModelScope.launch {
            applying.value = true
            var changed = 0
            for (book in loaded) {
                val actions = current.actionsFor(book)
                if (actions.isEmpty()) continue
                when (val result = applier.apply(book.id, actions)) {
                    is AppResult.Success -> {
                        changed++
                    }

                    is AppResult.Failure -> {
                        applying.value = false
                        errorBus.emit(result.error)
                        eventChannel.trySend(BulkEditEvent.Failed(result.error, appliedCount = changed))
                        return@launch
                    }
                }
            }
            applying.value = false
            eventChannel.trySend(BulkEditEvent.Applied(changedCount = changed))
        }
    }

    /**
     * Cancels this ViewModel's coroutines. Idempotent. Android reaches it via [onCleared]; iOS from
     * the screen wrapper's `isolated deinit`; web when the editor session closes.
     */
    fun close() {
        if (closed) return
        closed = true
        viewModelScope.cancel()
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    /**
     * Loads the selection, in title order.
     *
     * `getBookDetail` and nothing else: it reads contributors, genres, tags and moods, and
     * `SetGenres`/`SetContributors` are replace-sets built from what it returns. A list projection
     * would hand over a [BookDetail] whose collections are empty *because of how it was loaded*,
     * and the resulting "addition" would wipe every genre on every selected book. The N queries are
     * the price of that safety — do not fold them away.
     *
     * Sorted here, once, because [bookIds] originates from an unordered `Set` and the clients do
     * not agree on an order of their own. Sorting makes the selection read the same on phone,
     * desktop, web and iOS, and makes the preview rows stable.
     *
     * A book that fails to load is dropped, so [BulkEditUiState.Editing.bookCount] describes the
     * books Apply will actually touch rather than the number the user selected. The selection can
     * shrink between the grid and this screen — a book deleted from another device is the realistic
     * way — so the count the user chose is kept as
     * [BulkEditUiState.Editing.requestedCount] and the screen owns up to the difference. An
     * operation with no undo must not quietly do less than it was asked to.
     */
    private suspend fun loadSelection() {
        books.value = bookIds.mapNotNull { bookRepository.getBookDetail(it) }.sortedBy { it.title }
    }

    /**
     * Swaps the instruction of type [T] for [next], or drops it when [next] is null.
     *
     * One instruction per field: setting a field twice replaces rather than appends, so the list
     * always describes the form's current state.
     */
    private inline fun <reified T : BulkEdit> replace(next: BulkEdit?) {
        edits.update { current -> current.filterNot { it is T } + listOfNotNull(next) }
    }
}

/** The value every book agrees on, or null when they differ or there are none. */
private fun <T> List<BookDetail>.sharedBy(select: (BookDetail) -> T?): T? = map(select).distinct().singleOrNull()
