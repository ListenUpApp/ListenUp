package com.calypsan.listenup.web.features.metadata

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.metadata.MetadataEvent
import com.calypsan.listenup.client.presentation.metadata.MetadataField
import com.calypsan.listenup.client.presentation.metadata.MetadataUiState
import com.calypsan.listenup.client.presentation.metadata.MetadataViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin

/**
 * An open Metadata Match session — the wizard that finds a book on Audible and applies what it
 * finds, one field at a time.
 *
 * Wide, like the Chapter Editor's session, because this ViewModel also takes methods rather than
 * one sealed event. The names are the ViewModel's, unchanged: a web-only event type over the same
 * operations would be a second vocabulary for the same thing.
 */
@Suppress("LongParameterList")
class MetadataSession(
    val state: StateFlow<MetadataUiState>,
    val events: Flow<MetadataEvent>,
    val onQuery: (String) -> Unit,
    val onRegion: (MetadataLocale) -> Unit,
    val onSearch: () -> Unit,
    val onSelectMatch: (MetadataBook) -> Unit,
    val onClearSelection: () -> Unit,
    val onToggleField: (MetadataField) -> Unit,
    val onToggleAuthor: (String) -> Unit,
    val onToggleNarrator: (String) -> Unit,
    val onToggleSeries: (String) -> Unit,
    val onToggleGenre: (String) -> Unit,
    val onToggleMood: (String) -> Unit,
    val onToggleTag: (String) -> Unit,
    val onSelectCover: (String?) -> Unit,
    val onToggleChapter: (Int) -> Unit,
    val onApplyChapterNames: () -> Unit,
    val onApply: () -> Unit,
    val close: () -> Unit,
)

/** How the page gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenMetadata = (bookId: String, title: String, author: String, asin: String?) -> MetadataSession

/**
 * The production source: the shared [MetadataViewModel], initialised for one book.
 *
 * `initForBook` fires here, as every other session's load does. It seeds the query from the book's
 * own title and author — or, when the book already carries an ASIN, from that — so the reader lands
 * on a search that is usually already right.
 */
fun graphMetadata(koin: Koin): OpenMetadata =
    { bookId, title, author, asin ->
        val viewModel = koin.get<MetadataViewModel>()
        val store = ViewModelStore().apply { put(bookId, viewModel) }
        viewModel.initForBook(bookId = bookId, title = title, author = author, asin = asin)
        MetadataSession(
            state = viewModel.state,
            events = viewModel.events,
            onQuery = viewModel::updateQuery,
            onRegion = viewModel::changeRegion,
            onSearch = viewModel::search,
            onSelectMatch = viewModel::selectMatch,
            onClearSelection = viewModel::clearSelection,
            onToggleField = viewModel::toggleField,
            onToggleAuthor = viewModel::toggleAuthor,
            onToggleNarrator = viewModel::toggleNarrator,
            onToggleSeries = viewModel::toggleSeries,
            onToggleGenre = viewModel::toggleGenre,
            onToggleMood = viewModel::toggleMood,
            onToggleTag = viewModel::toggleTag,
            onSelectCover = viewModel::selectCover,
            onToggleChapter = viewModel::toggleChapter,
            onApplyChapterNames = viewModel::applyChapterNames,
            onApply = viewModel::applyMatch,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
@Suppress("LongParameterList")
fun fixedMetadata(
    state: MetadataUiState,
    events: Flow<MetadataEvent> = emptyFlow(),
    onQuery: (String) -> Unit = {},
    onRegion: (MetadataLocale) -> Unit = {},
    onSearch: () -> Unit = {},
    onSelectMatch: (MetadataBook) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onToggleField: (MetadataField) -> Unit = {},
    onToggleAuthor: (String) -> Unit = {},
    onToggleNarrator: (String) -> Unit = {},
    onToggleSeries: (String) -> Unit = {},
    onToggleGenre: (String) -> Unit = {},
    onToggleMood: (String) -> Unit = {},
    onToggleTag: (String) -> Unit = {},
    onSelectCover: (String?) -> Unit = {},
    onToggleChapter: (Int) -> Unit = {},
    onApplyChapterNames: () -> Unit = {},
    onApply: () -> Unit = {},
): OpenMetadata =
    { _, _, _, _ ->
        MetadataSession(
            state = MutableStateFlow(state),
            events = events,
            onQuery = onQuery,
            onRegion = onRegion,
            onSearch = onSearch,
            onSelectMatch = onSelectMatch,
            onClearSelection = onClearSelection,
            onToggleField = onToggleField,
            onToggleAuthor = onToggleAuthor,
            onToggleNarrator = onToggleNarrator,
            onToggleSeries = onToggleSeries,
            onToggleGenre = onToggleGenre,
            onToggleMood = onToggleMood,
            onToggleTag = onToggleTag,
            onSelectCover = onSelectCover,
            onToggleChapter = onToggleChapter,
            onApplyChapterNames = onApplyChapterNames,
            onApply = onApply,
            close = {},
        )
    }
