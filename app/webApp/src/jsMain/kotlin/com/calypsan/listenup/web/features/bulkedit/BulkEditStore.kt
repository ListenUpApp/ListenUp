package com.calypsan.listenup.web.features.bulkedit

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditEvent
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/**
 * An open Bulk Edit session over exactly the books that were selected.
 *
 * ⛔ Parametrized on the ids, so the ViewModel is a fresh one per selection. A bulk editor that
 * could switch the books it edits is one keystroke away from writing a publisher onto the wrong
 * forty.
 */
@Suppress("LongParameterList")
class BulkEditSession(
    val state: StateFlow<BulkEditUiState>,
    val events: Flow<BulkEditEvent>,
    val genres: StateFlow<List<Genre>>,
    val tags: StateFlow<List<Tag>>,
    val moods: StateFlow<List<Mood>>,
    val seriesMatches: StateFlow<List<SeriesSearchResult>>,
    val contributorMatches: StateFlow<List<ContributorSearchResult>>,
    val onSeriesQuery: (String) -> Unit,
    val onContributorQuery: (String) -> Unit,
    val onPublisher: (String) -> Unit,
    val onYear: (Int?) -> Unit,
    val onLanguage: (String) -> Unit,
    val onSeries: (BookSeriesInput?) -> Unit,
    val onContributors: (List<BookContributorInput>) -> Unit,
    val onGenres: (List<BookGenreInput>) -> Unit,
    val onTags: (List<String>) -> Unit,
    val onMoods: (List<String>) -> Unit,
    val onApply: () -> Unit,
    val close: () -> Unit,
)

/** How the page gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenBulkEdit = (bookIds: List<String>) -> BulkEditSession

/** The production source: the shared [BulkEditViewModel], scoped to these books. */
fun graphBulkEdit(koin: Koin): OpenBulkEdit =
    { bookIds ->
        val viewModel = koin.get<BulkEditViewModel> { parametersOf(bookIds) }
        val store = ViewModelStore().apply { put(bookIds.joinToString(","), viewModel) }
        BulkEditSession(
            state = viewModel.state,
            events = viewModel.events,
            genres = viewModel.genres,
            tags = viewModel.tags,
            moods = viewModel.moods,
            seriesMatches = viewModel.seriesMatches,
            contributorMatches = viewModel.contributorMatches,
            onSeriesQuery = viewModel::setSeriesQuery,
            onContributorQuery = viewModel::setContributorQuery,
            onPublisher = viewModel::setPublisher,
            onYear = viewModel::setYear,
            onLanguage = viewModel::setLanguage,
            onSeries = viewModel::setSeries,
            onContributors = viewModel::setContributors,
            onGenres = viewModel::setGenres,
            onTags = viewModel::setTags,
            onMoods = viewModel::setMoods,
            onApply = viewModel::apply,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
@Suppress("LongParameterList")
fun fixedBulkEdit(
    state: BulkEditUiState,
    events: Flow<BulkEditEvent> = emptyFlow(),
    genres: List<Genre> = emptyList(),
    tags: List<Tag> = emptyList(),
    moods: List<Mood> = emptyList(),
    seriesMatches: List<SeriesSearchResult> = emptyList(),
    contributorMatches: List<ContributorSearchResult> = emptyList(),
    onSeriesQuery: (String) -> Unit = {},
    onContributorQuery: (String) -> Unit = {},
    onPublisher: (String) -> Unit = {},
    onYear: (Int?) -> Unit = {},
    onLanguage: (String) -> Unit = {},
    onSeries: (BookSeriesInput?) -> Unit = {},
    onContributors: (List<BookContributorInput>) -> Unit = {},
    onGenres: (List<BookGenreInput>) -> Unit = {},
    onTags: (List<String>) -> Unit = {},
    onMoods: (List<String>) -> Unit = {},
    onApply: () -> Unit = {},
): OpenBulkEdit =
    {
        BulkEditSession(
            state = MutableStateFlow(state),
            events = events,
            genres = MutableStateFlow(genres),
            tags = MutableStateFlow(tags),
            moods = MutableStateFlow(moods),
            seriesMatches = MutableStateFlow(seriesMatches),
            contributorMatches = MutableStateFlow(contributorMatches),
            onSeriesQuery = onSeriesQuery,
            onContributorQuery = onContributorQuery,
            onPublisher = onPublisher,
            onYear = onYear,
            onLanguage = onLanguage,
            onSeries = onSeries,
            onContributors = onContributors,
            onGenres = onGenres,
            onTags = onTags,
            onMoods = onMoods,
            onApply = onApply,
            close = {},
        )
    }
