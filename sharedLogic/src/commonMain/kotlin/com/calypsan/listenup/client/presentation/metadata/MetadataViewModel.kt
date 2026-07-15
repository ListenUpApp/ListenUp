package com.calypsan.listenup.client.presentation.metadata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapter
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Hard ceiling on a single metadata RPC call. Metadata lookups hit external providers and can be
 * genuinely slow, so this is generous — its only job is to guarantee that a black-hole WebSocket (an
 * RPC that never resolves and never throws) eventually surfaces as a visible error instead of an
 * infinite spinner. Never-Stranded: the user always gets an outcome.
 */
private val METADATA_RPC_TIMEOUT = 30.seconds

private const val SEARCH_TIMEOUT_MESSAGE = "Search timed out. Check your connection and try again."
private const val SEARCH_FAILED_MESSAGE = "Something went wrong while searching. Please try again."
private const val PREVIEW_TIMEOUT_MESSAGE = "Loading the match timed out. Check your connection and try again."
private const val PREVIEW_FAILED_MESSAGE = "Something went wrong while loading the match. Please try again."

/** Tracks which metadata fields the user has selected to apply. */
data class MetadataSelections(
    val cover: Boolean = true,
    val title: Boolean = true,
    val subtitle: Boolean = true,
    val description: Boolean = true,
    val publisher: Boolean = true,
    val releaseDate: Boolean = true,
    val language: Boolean = true,
    val selectedAuthors: Set<String> = emptySet(),
    val selectedNarrators: Set<String> = emptySet(),
    val selectedSeries: Set<String> = emptySet(),
    val selectedGenres: Set<String> = emptySet(),
    val selectedMoods: Set<String> = emptySet(),
    val selectedTags: Set<String> = emptySet(),
)

/** Simple metadata fields that can be toggled as a unit. */
enum class MetadataField {
    COVER,
    TITLE,
    SUBTITLE,
    DESCRIPTION,
    PUBLISHER,
    RELEASE_DATE,
    LANGUAGE,
}

/** Book context carried across wizard phases. */
data class BookContext(
    val bookId: String,
    val currentTitle: String,
    val currentAuthor: String,
    val existingAsin: String?,
)

/**
 * A cover URL entry derived from a [MetadataBook].
 *
 * Replaces the legacy `CoverOption` REST-era type. Covers are now sourced
 * directly from the RPC-returned [MetadataBook] rather than a separate cover-
 * search endpoint.
 *
 * [label] is the display label shown under the cover thumbnail ("Audible",
 * "iTunes HD", etc.); [resolution] is an optional pixel-dimension string for
 * secondary display (e.g. "7000×7000").
 */
data class CoverEntry(
    val url: String,
    val label: String,
    val resolution: String?,
)

/**
 * One chapter's current name and the name suggested by the matched Audible
 * edition. [ordinal] is the chapter's index in start-time order — the key the
 * server uses to apply names. The local timestamp is intentionally absent: only
 * the name is ever changed.
 */
data class ChapterNameRow(
    val ordinal: Int,
    val currentName: String,
    val suggestedName: String,
)

/**
 * Chapter-name suggestion state for a [PreviewLoadState.Ready] preview.
 *
 * Computed once the preview is ready by comparing the book's local chapters to
 * the matched edition's Audible chapters. Names can only be applied when the two
 * counts match — a different count signals a different edition and is surfaced as
 * [CountMismatch], never force-aligned.
 */
sealed interface ChapterSuggestion {
    /** No suggestion offered — the book has no chapters, or Audible returned none. The row is hidden. */
    data object Unavailable : ChapterSuggestion

    /** The Audible chapter count differs from the book's; the row is shown disabled with the reason. */
    data class CountMismatch(
        val localCount: Int,
        val audibleCount: Int,
    ) : ChapterSuggestion

    /** Counts match; [rows] back the review sheet and [selectedOrdinals] are applied on confirm. */
    data class Available(
        val rows: List<ChapterNameRow>,
        val selectedOrdinals: Set<Int>,
        val isApplying: Boolean,
        val applyError: String?,
    ) : ChapterSuggestion
}

/**
 * UI state for the metadata match wizard.
 *
 * The wizard has three top-level phases:
 * - [Idle] — no book loaded yet (pre-[MetadataViewModel.initForBook]).
 * - [Search] — book loaded, user is searching / browsing results.
 * - [Preview] — user picked a match; loading or displaying the preview.
 *
 * Each phase carries a single-axis sub-state sealed hierarchy (never two
 * orthogonal ones), and the current [region] is lifted to the interface because
 * it persists across phase transitions.
 */
sealed interface MetadataUiState {
    val region: MetadataLocale

    /** No book loaded yet; pre-[MetadataViewModel.initForBook] placeholder. */
    data class Idle(
        override val region: MetadataLocale = MetadataLocale.DEFAULT,
    ) : MetadataUiState

    /** Book loaded; user is editing the search query and browsing results. */
    data class Search(
        override val region: MetadataLocale,
        val context: BookContext,
        val query: String,
        val loadState: SearchLoadState,
    ) : MetadataUiState

    /**
     * User picked a [match]; preview is loading or ready. [searchResults] is retained so
     * [MetadataViewModel.clearSelection] can return to [Search] without re-issuing the search.
     */
    data class Preview(
        override val region: MetadataLocale,
        val context: BookContext,
        val query: String,
        val searchResults: List<MetadataBook>,
        val match: MetadataBook,
        val loadState: PreviewLoadState,
    ) : MetadataUiState
}

/** Sub-state of [MetadataUiState.Search]. */
sealed interface SearchLoadState {
    data object Idle : SearchLoadState

    data object InFlight : SearchLoadState

    /** Audible search returned [results]. */
    data class Loaded(
        val results: List<MetadataBook>,
    ) : SearchLoadState

    /** Audible search failed; [message] is shown in-line. */
    data class Failed(
        val message: String,
    ) : SearchLoadState
}

/** Sub-state of [MetadataUiState.Preview]. */
sealed interface PreviewLoadState {
    data object Loading : PreviewLoadState

    /**
     * Preview is ready. [isApplying] and [applyError] overlay the ready data
     * while an apply mutation is in-flight / has just failed — they do not
     * replace the preview (same pattern as ContributorDetail's delete overlay).
     */
    data class Ready(
        val preview: MetadataBook,
        val selections: MetadataSelections,
        val coverEntries: List<CoverEntry>,
        val selectedCoverUrl: String?,
        val isApplying: Boolean,
        val applyError: String?,
        val previewNotFound: Boolean,
        val chapterSuggestion: ChapterSuggestion,
        val genreCandidates: List<String>,
        val moodCandidates: List<String>,
        val tagCandidates: List<String>,
    ) : PreviewLoadState

    /** Preview fetch failed; [message] is shown in-line. */
    data class Failed(
        val message: String,
    ) : PreviewLoadState
}

/** One-shot outcomes the Metadata wizard emits. */
sealed interface MetadataEvent {
    /** Apply succeeded; the route should navigate away. */
    data object MatchApplied : MetadataEvent

    /** Chapter names were applied; the review sheet should close (no navigation). */
    data object ChapterNamesApplied : MetadataEvent
}

/**
 * ViewModel for the metadata search-and-match wizard.
 *
 * Command-driven: all transitions are explicit method calls; there is no
 * reactive upstream. `searchBooks`, `getBookMetadata` are one-shot calls;
 * results land in the appropriate [MetadataUiState] phase.
 *
 * Cover options are derived directly from the preview's [MetadataBook.coverUrl]
 * and [MetadataBook.coverUrlMaxSize] — no separate cover-search call needed.
 *
 * Apply calls [MetadataRepository.applyBookMetadata] directly (the legacy
 * `ApplyMetadataMatchUseCase` has been inlined here; the use case only wrapped
 * the repository call with local image-cache invalidation which is now handled
 * server-side via SSE).
 */
class MetadataViewModel(
    private val metadataRepository: MetadataRepository,
    private val bookRepository: BookRepository,
    private val genreRepository: GenreRepository,
    private val moodRepository: MoodRepository,
    private val tagRepository: TagRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<MetadataUiState>
        field = MutableStateFlow<MetadataUiState>(MetadataUiState.Idle())

    private val eventChannel = Channel<MetadataEvent>(Channel.BUFFERED)
    val events: Flow<MetadataEvent> = eventChannel.receiveAsFlow()

    /**
     * Initialize the wizard for a specific book.
     *
     * If the book has an existing ASIN, we seed the search query with it (the
     * route can then auto-search for a direct match). Otherwise the query is
     * `"$title $author"`.
     *
     * To start in a specific region (e.g. the per-entry preview route carrying the region across
     * navigation), follow this with [changeRegion] before [selectMatch] — kept a separate call so
     * the Swift/iOS bridge for this method stays stable (Kotlin default args don't cross to Swift).
     */
    fun initForBook(
        bookId: String,
        title: String,
        author: String,
        asin: String? = null,
    ) {
        val query =
            buildString {
                append(title)
                if (author.isNotBlank()) {
                    append(' ')
                    append(author)
                }
            }.trim()
        state.value =
            MetadataUiState.Search(
                region = state.value.region,
                context =
                    BookContext(
                        bookId = bookId,
                        currentTitle = title,
                        currentAuthor = author,
                        existingAsin = asin,
                    ),
                query = query,
                loadState = SearchLoadState.Idle,
            )
    }

    /** Update the search query while in the [MetadataUiState.Search] phase. */
    fun updateQuery(query: String) {
        state.update { current ->
            if (current is MetadataUiState.Search) current.copy(query = query) else current
        }
    }

    /**
     * Change the Audible region and immediately reflect it: in preview, re-fetch the open match in
     * the new region; in search, re-run the query so results update without a manual re-submit.
     */
    fun changeRegion(region: MetadataLocale) {
        state.update { current ->
            when (current) {
                is MetadataUiState.Idle -> current.copy(region = region)
                is MetadataUiState.Search -> current.copy(region = region)
                is MetadataUiState.Preview -> current.copy(region = region)
            }
        }
        when (val current = state.value) {
            is MetadataUiState.Preview -> selectMatch(current.match)

            is MetadataUiState.Search -> search()

            // no-op when the query is blank
            is MetadataUiState.Idle -> Unit
        }
    }

    /** Execute an Audible search with the current query. */
    fun search() {
        val current = state.value as? MetadataUiState.Search ?: return
        val query = current.query.trim()
        if (query.isBlank()) return

        state.value = current.copy(loadState = SearchLoadState.InFlight)

        viewModelScope.launch {
            try {
                val result =
                    withTimeout(METADATA_RPC_TIMEOUT) {
                        metadataRepository.searchBooks(query, current.region, BookId(current.context.bookId))
                    }
                when (result) {
                    is AppResult.Success -> {
                        val hits = result.data.hits
                        state.update { latest ->
                            if (latest is MetadataUiState.Search && latest.query.trim() == query) {
                                latest.copy(loadState = SearchLoadState.Loaded(hits))
                            } else {
                                latest
                            }
                        }
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        logger.error { "Metadata search failed: ${result.error.message}" }
                        setSearchFailed(query, result.error.message)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger.error(e) { "Metadata search timed out for query \"$query\"" }
                setSearchFailed(query, SEARCH_TIMEOUT_MESSAGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error(e) { "Metadata search failed unexpectedly for query \"$query\"" }
                setSearchFailed(query, SEARCH_FAILED_MESSAGE)
            }
        }
    }

    /** Project a [SearchLoadState.Failed] onto the still-current search, ignoring a superseded query. */
    private fun setSearchFailed(
        query: String,
        message: String,
    ) {
        state.update { latest ->
            if (latest is MetadataUiState.Search && latest.query.trim() == query) {
                latest.copy(loadState = SearchLoadState.Failed(message))
            } else {
                latest
            }
        }
    }

    /** Select a match and transition to the preview phase. */
    fun selectMatch(result: MetadataBook) {
        val current = state.value
        val baseSearchResults: List<MetadataBook>
        val query: String
        val region: MetadataLocale
        val context: BookContext

        when (current) {
            is MetadataUiState.Search -> {
                region = current.region
                context = current.context
                query = current.query
                baseSearchResults =
                    (current.loadState as? SearchLoadState.Loaded)?.results.orEmpty()
            }

            is MetadataUiState.Preview -> {
                region = current.region
                context = current.context
                query = current.query
                baseSearchResults = current.searchResults
            }

            is MetadataUiState.Idle -> {
                return
            }
        }

        state.value =
            MetadataUiState.Preview(
                region = region,
                context = context,
                query = query,
                searchResults = baseSearchResults,
                match = result,
                loadState = PreviewLoadState.Loading,
            )

        loadPreview(result, region, context.bookId)
    }

    /** Clear the current match selection and return to the search phase. */
    fun clearSelection() {
        val current = state.value as? MetadataUiState.Preview ?: return
        state.value =
            MetadataUiState.Search(
                region = current.region,
                context = current.context,
                query = current.query,
                loadState =
                    if (current.searchResults.isEmpty()) {
                        SearchLoadState.Idle
                    } else {
                        SearchLoadState.Loaded(current.searchResults)
                    },
            )
    }

    /** Toggle a simple metadata field selection (cover, title, etc.). */
    fun toggleField(field: MetadataField) {
        updateReadySelections { selections ->
            when (field) {
                MetadataField.COVER -> selections.copy(cover = !selections.cover)
                MetadataField.TITLE -> selections.copy(title = !selections.title)
                MetadataField.SUBTITLE -> selections.copy(subtitle = !selections.subtitle)
                MetadataField.DESCRIPTION -> selections.copy(description = !selections.description)
                MetadataField.PUBLISHER -> selections.copy(publisher = !selections.publisher)
                MetadataField.RELEASE_DATE -> selections.copy(releaseDate = !selections.releaseDate)
                MetadataField.LANGUAGE -> selections.copy(language = !selections.language)
            }
        }
    }

    fun toggleAuthor(asin: String) =
        updateReadySelections { it.copy(selectedAuthors = it.selectedAuthors.toggle(asin)) }

    fun toggleNarrator(asin: String) =
        updateReadySelections { it.copy(selectedNarrators = it.selectedNarrators.toggle(asin)) }

    fun toggleSeries(asin: String) = updateReadySelections { it.copy(selectedSeries = it.selectedSeries.toggle(asin)) }

    fun toggleGenre(genre: String) = updateReadySelections { it.copy(selectedGenres = it.selectedGenres.toggle(genre)) }

    fun toggleMood(mood: String) = updateReadySelections { it.copy(selectedMoods = it.selectedMoods.toggle(mood)) }

    fun toggleTag(tag: String) = updateReadySelections { it.copy(selectedTags = it.selectedTags.toggle(tag)) }

    /** Pick a cover URL (null = use the Audible default from the preview). */
    fun selectCover(coverUrl: String?) {
        updateReady { it.copy(selectedCoverUrl = coverUrl) }
    }

    /**
     * Apply the selected match. Calls [MetadataRepository.applyBookMetadata]
     * directly — the legacy `ApplyMetadataMatchUseCase` indirection is
     * eliminated since the server handles all enrichment and emits an SSE event
     * for Room to catch. On success emits [MetadataEvent.MatchApplied]; on
     * failure sets [PreviewLoadState.Ready.applyError] and stays in Ready.
     */
    fun applyMatch() {
        val preview = state.value as? MetadataUiState.Preview ?: return
        val ready = preview.loadState as? PreviewLoadState.Ready ?: return

        updateReady { it.copy(isApplying = true, applyError = null) }

        viewModelScope.launch {
            when (
                val result =
                    metadataRepository.applyBookMetadata(
                        bookId = BookId(preview.context.bookId),
                        asin = preview.match.asin,
                        region = preview.region,
                        selection = ready.selections.toApplySelection(coverUrl = ready.selectedCoverUrl),
                    )
            ) {
                is AppResult.Success -> {
                    updateReady { it.copy(isApplying = false, applyError = null) }
                    eventChannel.trySend(MetadataEvent.MatchApplied)
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to apply metadata match: ${result.error.message}" }
                    errorBus.emit(result.error)
                    updateReady { it.copy(isApplying = false, applyError = result.error.message) }
                }
            }
        }
    }

    /** Toggle whether the chapter at [ordinal] receives the suggested name. */
    fun toggleChapter(ordinal: Int) {
        updateAvailable { it.copy(selectedOrdinals = it.selectedOrdinals.toggle(ordinal)) }
    }

    /**
     * Apply the selected chapter names via [MetadataRepository.applyChapterNames].
     * On success emits [MetadataEvent.ChapterNamesApplied]; on failure surfaces the
     * error in the review sheet and via the global error bus. Local timestamps are
     * never sent — only the ordinals to rename.
     */
    fun applyChapterNames() {
        val preview = state.value as? MetadataUiState.Preview ?: return
        val ready = preview.loadState as? PreviewLoadState.Ready ?: return
        val available = ready.chapterSuggestion as? ChapterSuggestion.Available ?: return
        if (available.isApplying) return

        updateAvailable { it.copy(isApplying = true, applyError = null) }

        viewModelScope.launch {
            when (
                val result =
                    metadataRepository.applyChapterNames(
                        bookId = BookId(preview.context.bookId),
                        asin = preview.match.asin,
                        region = preview.region,
                        ordinals = available.selectedOrdinals,
                    )
            ) {
                is AppResult.Success -> {
                    updateAvailable { it.copy(isApplying = false, applyError = null) }
                    eventChannel.trySend(MetadataEvent.ChapterNamesApplied)
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to apply chapter names: ${result.error.message}" }
                    errorBus.emit(result.error)
                    updateAvailable { it.copy(isApplying = false, applyError = result.error.message) }
                }
            }
        }
    }

    /**
     * Reads the book's local chapters and the matched edition's Audible chapters,
     * then derives the [ChapterSuggestion] for the current Ready preview. Both
     * lists are sorted by start time so ordinals align 1:1. Runs after the preview
     * is Ready; guards on the still-current match so a fast match-switch can't
     * apply a stale result.
     */
    private fun loadChapterSuggestion(
        bookId: String,
        asin: String,
        region: MetadataLocale,
    ) {
        viewModelScope.launch {
            val localChapters =
                try {
                    bookRepository.getChapters(bookId).sortedBy { it.startTime }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to read local chapters for book $bookId" }
                    updateChapterSuggestion(asin, region) { ChapterSuggestion.Unavailable }
                    return@launch
                }
            if (localChapters.isEmpty()) {
                updateChapterSuggestion(asin, region) { ChapterSuggestion.Unavailable }
                return@launch
            }
            val audibleChapters =
                when (val result = metadataRepository.getBookChapters(asin, region)) {
                    is AppResult.Success -> {
                        result.data
                            ?.chapters
                            ?.sortedBy { it.startMs }
                            .orEmpty()
                    }

                    is AppResult.Failure -> {
                        emptyList()
                    }
                }
            val suggestion =
                when {
                    audibleChapters.isEmpty() -> {
                        ChapterSuggestion.Unavailable
                    }

                    audibleChapters.size != localChapters.size -> {
                        ChapterSuggestion.CountMismatch(
                            localCount = localChapters.size,
                            audibleCount = audibleChapters.size,
                        )
                    }

                    else -> {
                        buildAvailableSuggestion(localChapters, audibleChapters)
                    }
                }
            updateChapterSuggestion(asin, region) { suggestion }
        }
    }

    private fun updateChapterSuggestion(
        asin: String,
        region: MetadataLocale,
        transform: (ChapterSuggestion) -> ChapterSuggestion,
    ) {
        state.update { latest ->
            val preview = latest.readyPreviewFor(asin, region) ?: return@update latest
            val ready = preview.loadState as PreviewLoadState.Ready
            preview.copy(loadState = ready.copy(chapterSuggestion = transform(ready.chapterSuggestion)))
        }
    }

    /**
     * Returns this state as a [MetadataUiState.Preview] only when it is still the
     * Ready preview for the given [asin] and [region] — the stale-suggestion guard
     * that prevents an in-flight chapter load from landing on a switched match or
     * region. Returns null otherwise.
     */
    private fun MetadataUiState.readyPreviewFor(
        asin: String,
        region: MetadataLocale,
    ): MetadataUiState.Preview? =
        (this as? MetadataUiState.Preview)
            ?.takeIf { it.match.asin == asin && it.region == region && it.loadState is PreviewLoadState.Ready }

    private fun updateAvailable(transform: (ChapterSuggestion.Available) -> ChapterSuggestion.Available) {
        updateReady { ready ->
            val cs = ready.chapterSuggestion
            if (cs is ChapterSuggestion.Available) ready.copy(chapterSuggestion = transform(cs)) else ready
        }
    }

    private fun buildAvailableSuggestion(
        localChapters: List<Chapter>,
        audibleChapters: List<MetadataChapter>,
    ): ChapterSuggestion.Available {
        val rows =
            localChapters.mapIndexed { ordinal, chapter ->
                ChapterNameRow(
                    ordinal = ordinal,
                    currentName = chapter.title,
                    suggestedName = audibleChapters[ordinal].title,
                )
            }
        return ChapterSuggestion.Available(
            rows = rows,
            selectedOrdinals = rows.map { it.ordinal }.toSet(),
            isApplying = false,
            applyError = null,
        )
    }

    /** Reset back to [MetadataUiState.Idle]. Call when dismissing the flow. */
    fun reset() {
        state.value = MetadataUiState.Idle(region = state.value.region)
    }

    private fun loadPreview(
        match: MetadataBook,
        region: MetadataLocale,
        bookId: String,
    ) {
        viewModelScope.launch {
            try {
                val result =
                    withTimeout(METADATA_RPC_TIMEOUT) {
                        metadataRepository.getBookMetadata(match.asin, region)
                    }
                when (result) {
                    is AppResult.Success -> {
                        val preview = result.data
                        if (preview != null) {
                            val hasNoData =
                                preview.authors.isEmpty() &&
                                    preview.narrators.isEmpty() &&
                                    preview.series.isEmpty() &&
                                    preview.coverUrl == null &&
                                    preview.description == null
                            transitionToReady(
                                match,
                                preview,
                                previewNotFound = hasNoData,
                                bookId = bookId,
                                region = region,
                            )
                        } else {
                            // Server returned null — book not found in region
                            transitionToReady(match, match, previewNotFound = true, bookId = bookId, region = region)
                        }
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        logger.error { "Failed to load metadata preview: ${result.error.message}" }
                        if (match.title.isNotBlank()) {
                            logger.info { "Using search result data as preview fallback" }
                            transitionToReady(match, match, previewNotFound = false, bookId = bookId, region = region)
                        } else {
                            setPreviewFailed(match.asin, result.error.message)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger.error(e) { "Metadata preview timed out for ${match.asin}" }
                setPreviewFailed(match.asin, PREVIEW_TIMEOUT_MESSAGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error(e) { "Metadata preview failed unexpectedly for ${match.asin}" }
                setPreviewFailed(match.asin, PREVIEW_FAILED_MESSAGE)
            }
        }
    }

    /** Project a [PreviewLoadState.Failed] onto the still-current preview, ignoring a switched match. */
    private fun setPreviewFailed(
        asin: String,
        message: String,
    ) {
        state.update { latest ->
            if (latest is MetadataUiState.Preview && latest.match.asin == asin) {
                latest.copy(loadState = PreviewLoadState.Failed(message))
            } else {
                latest
            }
        }
    }

    private suspend fun transitionToReady(
        match: MetadataBook,
        preview: MetadataBook,
        previewNotFound: Boolean,
        bookId: String,
        region: MetadataLocale,
    ) {
        // Read the book's current items (local Room, offline-first) and union them
        // with the match's proposed ones. The server reconciles a match-apply by
        // replacing the book's genres/moods/tags with exactly the apply selection,
        // so current items must be in the selection or they'd be silently dropped.
        val currentGenres = genreRepository.getGenresForBook(bookId).map { it.name }
        val currentMoods = moodRepository.observeMoodsForBook(bookId).first().map { it.name }
        val currentTags = tagRepository.observeTagsForBook(bookId).first().map { it.name }
        val genreCandidates = unionCandidates(currentGenres, preview.genres)
        val moodCandidates = unionCandidates(currentMoods, preview.moods)
        val tagCandidates = unionCandidates(currentTags, preview.tags)

        state.update { latest ->
            if (latest !is MetadataUiState.Preview || latest.match.asin != match.asin) return@update latest
            val ready =
                PreviewLoadState.Ready(
                    preview = preview,
                    selections = initializeSelections(preview, genreCandidates, moodCandidates, tagCandidates),
                    coverEntries = buildCoverEntries(preview),
                    selectedCoverUrl = null,
                    isApplying = false,
                    applyError = null,
                    previewNotFound = previewNotFound,
                    chapterSuggestion = ChapterSuggestion.Unavailable,
                    genreCandidates = genreCandidates,
                    moodCandidates = moodCandidates,
                    tagCandidates = tagCandidates,
                )
            latest.copy(loadState = ready)
        }
        loadChapterSuggestion(bookId, match.asin, region)
    }

    private fun updateReady(transform: (PreviewLoadState.Ready) -> PreviewLoadState.Ready) {
        state.update { latest ->
            if (latest is MetadataUiState.Preview && latest.loadState is PreviewLoadState.Ready) {
                latest.copy(loadState = transform(latest.loadState))
            } else {
                latest
            }
        }
    }

    private fun updateReadySelections(transform: (MetadataSelections) -> MetadataSelections) {
        updateReady { it.copy(selections = transform(it.selections)) }
    }

    private fun MetadataSelections.toApplySelection(coverUrl: String?): MetadataApplySelection =
        MetadataApplySelection(
            title = title,
            subtitle = subtitle,
            description = description,
            publisher = publisher,
            releaseDate = releaseDate,
            language = language,
            cover = cover,
            authorAsins = selectedAuthors,
            narratorAsins = selectedNarrators,
            seriesAsins = selectedSeries,
            coverUrl = coverUrl,
            genres = selectedGenres,
            moods = selectedMoods,
            tags = selectedTags,
        )

    private fun initializeSelections(
        preview: MetadataBook,
        genreCandidates: List<String>,
        moodCandidates: List<String>,
        tagCandidates: List<String>,
    ): MetadataSelections =
        MetadataSelections(
            cover = preview.coverUrl != null,
            title = preview.title.isNotBlank(),
            subtitle = !preview.subtitle.isNullOrBlank(),
            description = !preview.description.isNullOrBlank(),
            publisher = !preview.publisher.isNullOrBlank(),
            releaseDate = !preview.releaseDate.isNullOrBlank(),
            language = !preview.language.isNullOrBlank(),
            selectedAuthors = preview.authors.mapNotNull { it.asin }.toSet(),
            selectedNarrators = preview.narrators.mapNotNull { it.asin }.toSet(),
            selectedSeries = preview.series.mapNotNull { it.asin }.toSet(),
            // Seeded all-on from union(current, proposed) so kept current items survive
            // the server's replace-style reconcile on apply.
            selectedGenres = genreCandidates.toSet(),
            selectedMoods = moodCandidates.toSet(),
            selectedTags = tagCandidates.toSet(),
        )

    /**
     * Unions the book's [current] labels with the match's [proposed] ones,
     * current-first and deduped by trimmed, lower-cased label. Empty labels are
     * dropped; the first-seen spelling of each label is preserved.
     */
    private fun unionCandidates(
        current: List<String>,
        proposed: List<String>,
    ): List<String> {
        val seen = LinkedHashMap<String, String>()
        for (label in current + proposed) {
            val key = label.trim().lowercase()
            if (key.isNotEmpty() && key !in seen) seen[key] = label.trim()
        }
        return seen.values.toList()
    }

    /**
     * Derives cover options from the preview book's Audible thumbnail and
     * optional iTunes high-resolution URL. The legacy cover-search endpoint
     * has been removed; covers now come directly from the RPC metadata response.
     */
    private fun buildCoverEntries(preview: MetadataBook): List<CoverEntry> =
        buildList {
            preview.coverUrlMaxSize?.let { add(CoverEntry(url = it, label = "iTunes HD", resolution = null)) }
            preview.coverUrl?.let { add(CoverEntry(url = it, label = "Audible", resolution = null)) }
        }

    private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
}
