package com.calypsan.listenup.client.presentation.metadata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.core.error.ErrorMapper
import com.calypsan.listenup.client.domain.repository.CoverOption
import com.calypsan.listenup.client.domain.repository.MetadataBook
import com.calypsan.listenup.client.domain.repository.MetadataContributor
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.repository.MetadataSearchResult
import com.calypsan.listenup.client.domain.usecase.metadata.ApplyMetadataMatchUseCase
import com.calypsan.listenup.client.domain.usecase.metadata.MetadataMatchSelections
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** Available Audible regions for metadata lookup. */
enum class AudibleRegion(
    val code: String,
    val displayName: String,
) {
    US("us", "United States"),
    UK("uk", "United Kingdom"),
    DE("de", "Germany"),
    FR("fr", "France"),
    AU("au", "Australia"),
    CA("ca", "Canada"),
    IT("it", "Italy"),
    IN("in", "India"),
    ES("es", "Spain"),
    JP("jp", "Japan"),
}

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
    val region: AudibleRegion

    /** No book loaded yet; pre-[MetadataViewModel.initForBook] placeholder. */
    data class Idle(
        override val region: AudibleRegion = AudibleRegion.US,
    ) : MetadataUiState

    /** Book loaded; user is editing the search query and browsing results. */
    data class Search(
        override val region: AudibleRegion,
        val context: BookContext,
        val query: String,
        val loadState: SearchLoadState,
    ) : MetadataUiState

    /**
     * User picked a [match]; preview is loading or ready. [searchResults] is retained so
     * [MetadataViewModel.clearSelection] can return to [Search] without re-issuing the search.
     */
    data class Preview(
        override val region: AudibleRegion,
        val context: BookContext,
        val query: String,
        val searchResults: List<MetadataSearchResult>,
        val match: MetadataSearchResult,
        val loadState: PreviewLoadState,
    ) : MetadataUiState
}

/** Sub-state of [MetadataUiState.Search]. */
sealed interface SearchLoadState {
    data object Idle : SearchLoadState

    data object InFlight : SearchLoadState

    /** Audible search returned [results]. */
    data class Loaded(
        val results: List<MetadataSearchResult>,
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
        val coverOptions: List<CoverOption>,
        val isLoadingCovers: Boolean,
        val selectedCoverUrl: String?,
        val isApplying: Boolean,
        val applyError: String?,
        val previewNotFound: Boolean,
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
}

/**
 * ViewModel for the metadata search-and-match wizard.
 *
 * Command-driven: all transitions are explicit method calls; there is no
 * reactive upstream. `searchAudible`, `getMetadataPreview` and `searchCovers`
 * are one-shot suspend calls that throw on failure; results land in the
 * appropriate [MetadataUiState] phase.
 */
class MetadataViewModel(
    private val metadataRepository: MetadataRepository,
    private val applyMetadataMatchUseCase: ApplyMetadataMatchUseCase,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val _state = MutableStateFlow<MetadataUiState>(MetadataUiState.Idle())
    val state: StateFlow<MetadataUiState> = _state.asStateFlow()

    private val eventChannel = Channel<MetadataEvent>(Channel.BUFFERED)
    val events: Flow<MetadataEvent> = eventChannel.receiveAsFlow()

    /**
     * Initialize the wizard for a specific book.
     *
     * If the book has an existing ASIN, we seed the search query with it (the
     * route can then auto-search for a direct match). Otherwise the query is
     * `"$title $author"`.
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
        _state.value =
            MetadataUiState.Search(
                region = _state.value.region,
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
        _state.update { current ->
            if (current is MetadataUiState.Search) current.copy(query = query) else current
        }
    }

    /** Change the Audible region. If in preview, re-fetch with the new region. */
    fun changeRegion(region: AudibleRegion) {
        _state.update { current ->
            when (current) {
                is MetadataUiState.Idle -> current.copy(region = region)
                is MetadataUiState.Search -> current.copy(region = region)
                is MetadataUiState.Preview -> current.copy(region = region)
            }
        }
        val current = _state.value
        if (current is MetadataUiState.Preview) {
            selectMatch(current.match)
        }
    }

    /** Execute an Audible search with the current query. */
    fun search() {
        val current = _state.value as? MetadataUiState.Search ?: return
        val query = current.query.trim()
        if (query.isBlank()) return

        _state.value = current.copy(loadState = SearchLoadState.InFlight)

        viewModelScope.launch {
            try {
                val results = metadataRepository.searchAudible(query)
                _state.update { latest ->
                    if (latest is MetadataUiState.Search && latest.query.trim() == query) {
                        latest.copy(loadState = SearchLoadState.Loaded(results))
                    } else {
                        latest
                    }
                }
            } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
                throw cancel
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                @Suppress("DEPRECATION")
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Metadata search failed" }
                _state.update { latest ->
                    if (latest is MetadataUiState.Search && latest.query.trim() == query) {
                        latest.copy(loadState = SearchLoadState.Failed(e.message ?: "Search failed"))
                    } else {
                        latest
                    }
                }
            }
        }
    }

    /** Select a match and transition to the preview phase. */
    fun selectMatch(result: MetadataSearchResult) {
        val current = _state.value
        val baseSearchResults: List<MetadataSearchResult>
        val query: String
        val region: AudibleRegion
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

        _state.value =
            MetadataUiState.Preview(
                region = region,
                context = context,
                query = query,
                searchResults = baseSearchResults,
                match = result,
                loadState = PreviewLoadState.Loading,
            )

        loadCoverOptions(context)
        loadPreview(result, region)
    }

    /** Clear the current match selection and return to the search phase. */
    fun clearSelection() {
        val current = _state.value as? MetadataUiState.Preview ?: return
        _state.value =
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

    /** Pick a cover URL (null = use the Audible default from the preview). */
    fun selectCover(coverUrl: String?) {
        updateReady { it.copy(selectedCoverUrl = coverUrl) }
    }

    /**
     * Apply the selected match. On success emits [MetadataEvent.MatchApplied];
     * on failure sets [PreviewLoadState.Ready.applyError] and stays in Ready.
     */
    fun applyMatch() {
        val preview = _state.value as? MetadataUiState.Preview ?: return
        val ready = preview.loadState as? PreviewLoadState.Ready ?: return

        updateReady { it.copy(isApplying = true, applyError = null) }

        viewModelScope.launch {
            val result =
                applyMetadataMatchUseCase(
                    bookId = preview.context.bookId,
                    asin = preview.match.asin,
                    region = preview.region.code,
                    selections = ready.selections.toMatchSelections(),
                    previewBook = ready.preview,
                    coverUrl = ready.selectedCoverUrl,
                )
            when (result) {
                is Success -> {
                    updateReady { it.copy(isApplying = false, applyError = null) }
                    eventChannel.trySend(MetadataEvent.MatchApplied)
                }

                is Failure -> {
                    logger.error { "Failed to apply metadata match: ${result.message}" }
                    updateReady { it.copy(isApplying = false, applyError = result.message) }
                }
            }
        }
    }

    /** Reset back to [MetadataUiState.Idle]. Call when dismissing the flow. */
    fun reset() {
        _state.value = MetadataUiState.Idle(region = _state.value.region)
    }

    private fun loadPreview(
        match: MetadataSearchResult,
        region: AudibleRegion,
    ) {
        viewModelScope.launch {
            try {
                val preview = metadataRepository.getMetadataPreview(match.asin, region.code)
                val hasNoData =
                    preview.authors.isEmpty() &&
                        preview.narrators.isEmpty() &&
                        preview.series.isEmpty() &&
                        preview.genres.isEmpty() &&
                        preview.coverUrl == null &&
                        preview.description == null
                transitionToReady(match, preview, previewNotFound = hasNoData)
            } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
                throw cancel
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                @Suppress("DEPRECATION")
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to load metadata preview" }
                if (match.title.isNotBlank()) {
                    logger.info { "Using search result data as preview fallback" }
                    transitionToReady(match, match.toFallbackPreview(), previewNotFound = false)
                } else {
                    _state.update { latest ->
                        if (latest is MetadataUiState.Preview && latest.match.asin == match.asin) {
                            latest.copy(loadState = PreviewLoadState.Failed(e.message ?: "Failed to load metadata"))
                        } else {
                            latest
                        }
                    }
                }
            }
        }
    }

    private fun loadCoverOptions(context: BookContext) {
        viewModelScope.launch {
            try {
                val covers = metadataRepository.searchCovers(context.currentTitle, context.currentAuthor)
                updateReady { it.copy(coverOptions = covers, isLoadingCovers = false) }
            } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
                throw cancel
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                @Suppress("DEPRECATION")
                errorBus.emit(ErrorMapper.map(e))
                logger.warn(e) { "Cover search failed, using Audible cover only" }
                updateReady { it.copy(isLoadingCovers = false) }
            }
        }
    }

    private fun transitionToReady(
        match: MetadataSearchResult,
        preview: MetadataBook,
        previewNotFound: Boolean,
    ) {
        _state.update { latest ->
            if (latest !is MetadataUiState.Preview || latest.match.asin != match.asin) return@update latest
            val existingReady = latest.loadState as? PreviewLoadState.Ready
            val ready =
                PreviewLoadState.Ready(
                    preview = preview,
                    selections = initializeSelections(preview),
                    coverOptions = existingReady?.coverOptions.orEmpty(),
                    isLoadingCovers = existingReady?.isLoadingCovers ?: true,
                    selectedCoverUrl = null,
                    isApplying = false,
                    applyError = null,
                    previewNotFound = previewNotFound,
                )
            latest.copy(loadState = ready)
        }
    }

    private fun updateReady(transform: (PreviewLoadState.Ready) -> PreviewLoadState.Ready) {
        _state.update { latest ->
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

    private fun MetadataSearchResult.toFallbackPreview(): MetadataBook =
        MetadataBook(
            asin = asin,
            title = title,
            subtitle = subtitle,
            authors = authors.map { MetadataContributor(name = it) },
            narrators = narrators.map { MetadataContributor(name = it) },
            series = emptyList(),
            coverUrl = coverUrl,
            runtimeMinutes = runtimeMinutes ?: 0,
            releaseDate = releaseDate,
            rating = rating ?: 0.0,
            ratingCount = ratingCount,
            language = language,
        )

    private fun initializeSelections(preview: MetadataBook): MetadataSelections =
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
            selectedGenres = preview.genres.toSet(),
        )

    private fun MetadataSelections.toMatchSelections(): MetadataMatchSelections =
        MetadataMatchSelections(
            cover = cover,
            title = title,
            subtitle = subtitle,
            description = description,
            publisher = publisher,
            releaseDate = releaseDate,
            language = language,
            selectedAuthors = selectedAuthors,
            selectedNarrators = selectedNarrators,
            selectedSeries = selectedSeries,
            selectedGenres = selectedGenres,
        )

    private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
}
