package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.web.nav.RouteSession
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.match.BookMatchEvent
import com.calypsan.listenup.client.presentation.match.BookMatchViewModel
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.LabelKind
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/**
 * An open Match details session for one book: Find and Review state, the one-shot outcomes, and one callback
 * per [BookMatchViewModel] action, under the ViewModel's own names — a web-only vocabulary over the same
 * operations would be a second name for each.
 *
 * Find and Compare editions share this one session, so going to Compare and back never searches again.
 */
@Suppress("LongParameterList")
class BookMatchSession(
    val findState: StateFlow<FindUiState>,
    val reviewState: StateFlow<ReviewUiState>,
    val events: Flow<BookMatchEvent>,
    val search: (String) -> Unit,
    val searchByTitle: () -> Unit,
    val chooseStoreForThisSearch: (MetadataLocale) -> Unit,
    val retry: () -> Unit,
    val pick: (BookCandidateKey) -> Unit,
    val backToResults: () -> Unit,
    val useTwoPane: (Boolean) -> Unit,
    val setFieldTicked: (BookField, Boolean) -> Unit,
    val chooseSource: (BookField, FieldChoice) -> Unit,
    val chooseCover: (ImageChoice) -> Unit,
    val removeYourLabel: (LabelKind, String) -> Unit,
    val restoreYourLabel: (LabelKind, String) -> Unit,
    val toggleSuggestion: (LabelKind, String) -> Unit,
    val setChapterNamesIncluded: (Boolean) -> Unit,
    val toggleChapter: (Int) -> Unit,
    val apply: () -> Unit,
    override val close: () -> Unit,
) : RouteSession

/** How the page gets its session for a book. */
typealias OpenBookMatch = (bookId: String) -> BookMatchSession

/**
 * Every part of Match details on web: a book's session, a person's session, and the receipt Book Detail or the
 * contributor page shows after Apply. One value so the route chain carries one parameter for the feature.
 */
class MatchDetailsGraph(
    val openBookMatch: OpenBookMatch,
    val openPersonMatch: OpenPersonMatch,
    val openMatchReceipt: OpenMatchReceipt,
)

/** The production graph: the shared ViewModels, each parametrized on its book or contributor. */
fun graphMatchDetails(koin: Koin): MatchDetailsGraph =
    MatchDetailsGraph(
        openBookMatch = graphBookMatch(koin),
        openPersonMatch = graphPersonMatch(koin),
        openMatchReceipt = graphMatchReceipt(koin),
    )

/** The production source: [BookMatchViewModel] for one book. Find starts as the ViewModel is built. */
fun graphBookMatch(koin: Koin): OpenBookMatch =
    { bookId ->
        val viewModel = koin.get<BookMatchViewModel> { parametersOf(bookId) }
        val store = ViewModelStore().apply { put(bookId, viewModel) }
        BookMatchSession(
            findState = viewModel.findState,
            reviewState = viewModel.reviewState,
            events = viewModel.events,
            search = viewModel::search,
            searchByTitle = viewModel::searchByTitle,
            chooseStoreForThisSearch = viewModel::chooseStoreForThisSearch,
            retry = viewModel::retry,
            pick = viewModel::pick,
            backToResults = viewModel::backToResults,
            useTwoPane = viewModel::useTwoPane,
            setFieldTicked = viewModel::setFieldTicked,
            chooseSource = viewModel::chooseSource,
            chooseCover = viewModel::chooseCover,
            removeYourLabel = viewModel::removeYourLabel,
            restoreYourLabel = viewModel::restoreYourLabel,
            toggleSuggestion = viewModel::toggleSuggestion,
            setChapterNamesIncluded = viewModel::setChapterNamesIncluded,
            toggleChapter = viewModel::toggleChapter,
            apply = viewModel::apply,
            close = store::clear,
        )
    }
