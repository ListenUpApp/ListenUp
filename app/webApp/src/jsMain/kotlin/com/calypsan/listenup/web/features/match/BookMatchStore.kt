package com.calypsan.listenup.web.features.match

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
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
    val close: () -> Unit,
)

/** How the page gets its session for a book. */
typealias OpenBookMatch = (bookId: String) -> BookMatchSession

/**
 * Both halves of Match details on web: the page's session, and the receipt Book Detail shows after Apply. One
 * value so the route chain carries one parameter for the feature rather than two.
 */
class MatchDetailsGraph(
    val openBookMatch: OpenBookMatch,
    val openMatchReceipt: OpenMatchReceipt,
)

/** The production graph: the shared ViewModels, each parametrized on the book. */
fun graphMatchDetails(koin: Koin): MatchDetailsGraph =
    MatchDetailsGraph(openBookMatch = graphBookMatch(koin), openMatchReceipt = graphMatchReceipt(koin))

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

/**
 * A session over flows a spec owns, recording what it is asked to do. The gestures do not move the state on
 * their own — a spec that wants Review after a pick sets [reviewState] itself, as the server would.
 */
@Suppress("LongParameterList")
fun fixedBookMatch(
    findState: StateFlow<FindUiState>,
    reviewState: StateFlow<ReviewUiState> = MutableStateFlow(ReviewUiState.NoneChosen),
    events: Flow<BookMatchEvent> = emptyFlow(),
    search: (String) -> Unit = {},
    searchByTitle: () -> Unit = {},
    chooseStoreForThisSearch: (MetadataLocale) -> Unit = {},
    retry: () -> Unit = {},
    pick: (BookCandidateKey) -> Unit = {},
    backToResults: () -> Unit = {},
    useTwoPane: (Boolean) -> Unit = {},
    setFieldTicked: (BookField, Boolean) -> Unit = { _, _ -> },
    chooseSource: (BookField, FieldChoice) -> Unit = { _, _ -> },
    chooseCover: (ImageChoice) -> Unit = {},
    removeYourLabel: (LabelKind, String) -> Unit = { _, _ -> },
    restoreYourLabel: (LabelKind, String) -> Unit = { _, _ -> },
    toggleSuggestion: (LabelKind, String) -> Unit = { _, _ -> },
    setChapterNamesIncluded: (Boolean) -> Unit = {},
    toggleChapter: (Int) -> Unit = {},
    apply: () -> Unit = {},
): BookMatchSession =
    BookMatchSession(
        findState = findState,
        reviewState = reviewState,
        events = events,
        search = search,
        searchByTitle = searchByTitle,
        chooseStoreForThisSearch = chooseStoreForThisSearch,
        retry = retry,
        pick = pick,
        backToResults = backToResults,
        useTwoPane = useTwoPane,
        setFieldTicked = setFieldTicked,
        chooseSource = chooseSource,
        chooseCover = chooseCover,
        removeYourLabel = removeYourLabel,
        restoreYourLabel = restoreYourLabel,
        toggleSuggestion = toggleSuggestion,
        setChapterNamesIncluded = setChapterNamesIncluded,
        toggleChapter = toggleChapter,
        apply = apply,
        close = {},
    )
