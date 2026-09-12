package com.calypsan.listenup.web.features.bookdetail

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Shelf

/**
 * An open Book Detail state stream, plus the teardown for it.
 *
 * A browser has no `ViewModelStore` to hand a ViewModel's lifetime to, so the page owns it: the
 * composition opens a session when it starts showing a book and closes it when it stops. Without
 * that, every book a reader visited would leave its flows collecting for the life of the tab.
 */
class BookDetailSession(
    val state: StateFlow<BookDetailUiState>,
    /**
     * Mark the book finished.
     *
     * ⛔ These three were the whole of web's Book Detail gap: the page consumed `state` and exposed
     * no action at all, so a reader could look at a book on the web and never change their own
     * relationship to it. `BookDetailViewModel` has had all three since it was written, and both
     * native clients offer them from the book's overflow menu.
     */
    val onMarkComplete: () -> Unit,
    /** Clear progress entirely — the "start over / did not finish" answer. */
    val onDiscardProgress: () -> Unit,
    /** Keep the book started but send the position back to zero. */
    val onRestart: () -> Unit,
    /**
     * The shelves this listener owns, and the collections they may file a book into.
     *
     * ⛔ Both are exposed by the ViewModel as flows separate from `state`, and web read neither — so
     * even once the actions existed the pickers would have had nothing to show.
     */
    val myShelves: StateFlow<List<Shelf>>,
    val collections: StateFlow<List<Collection>>,
    val onShowShelfPicker: () -> Unit,
    val onHideShelfPicker: () -> Unit,
    val onAddToShelf: (String) -> Unit,
    val onCreateShelfAndAdd: (String) -> Unit,
    val onClearShelfError: () -> Unit,
    val onShowCollectionPicker: () -> Unit,
    val onHideCollectionPicker: () -> Unit,
    val onAddToCollection: (String) -> Unit,
    val onCreateCollectionAndAdd: (String) -> Unit,
    val onClearCollectionError: () -> Unit,
    val close: () -> Unit,
)

/**
 * How the page gets its state. Production resolves the real ViewModel out of the client graph
 * ([graphBookDetail]); specs hand over a fixed state instead, so the URL, layout and selection
 * contracts can be driven without a database behind them.
 */
typealias OpenBookDetail = (bookId: String) -> BookDetailSession

/**
 * The production source: the shared [BookDetailViewModel], resolved from the started Koin graph
 * and pointed at [bookId].
 *
 * The ViewModel goes into a [ViewModelStore] of its own, because that is the only thing allowed
 * to end one — `ViewModel.clear()`, which cancels `viewModelScope` and with it every flow the
 * ViewModel exposes, is internal to the lifecycle library. Clearing the store is the same call a
 * native client's screen makes when it goes away; here the store's scope is one visited book.
 */
fun graphBookDetail(koin: Koin): OpenBookDetail =
    { bookId ->
        val viewModel = koin.get<BookDetailViewModel>()
        val store = ViewModelStore().apply { put(bookId, viewModel) }
        viewModel.loadBook(bookId)
        BookDetailSession(
            state = viewModel.state,
            onMarkComplete = { viewModel.markComplete() },
            onDiscardProgress = viewModel::discardProgress,
            onRestart = viewModel::restartBook,
            myShelves = viewModel.myShelves,
            collections = viewModel.collections,
            onShowShelfPicker = viewModel::showShelfPicker,
            onHideShelfPicker = viewModel::hideShelfPicker,
            onAddToShelf = viewModel::addBookToShelf,
            onCreateShelfAndAdd = viewModel::createShelfAndAddBook,
            onClearShelfError = viewModel::clearShelfError,
            onShowCollectionPicker = viewModel::showCollectionPicker,
            onHideCollectionPicker = viewModel::hideCollectionPicker,
            onAddToCollection = viewModel::addBookToCollection,
            onCreateCollectionAndAdd = viewModel::createCollectionAndAddBook,
            onClearCollectionError = viewModel::clearCollectionError,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs use in place of the graph. */
@Suppress("LongParameterList")
fun fixedBookDetail(
    state: BookDetailUiState,
    onMarkComplete: () -> Unit = {},
    onDiscardProgress: () -> Unit = {},
    onRestart: () -> Unit = {},
    myShelves: List<Shelf> = emptyList(),
    collections: List<Collection> = emptyList(),
    onShowShelfPicker: () -> Unit = {},
    onHideShelfPicker: () -> Unit = {},
    onAddToShelf: (String) -> Unit = {},
    onCreateShelfAndAdd: (String) -> Unit = {},
    onClearShelfError: () -> Unit = {},
    onShowCollectionPicker: () -> Unit = {},
    onHideCollectionPicker: () -> Unit = {},
    onAddToCollection: (String) -> Unit = {},
    onCreateCollectionAndAdd: (String) -> Unit = {},
    onClearCollectionError: () -> Unit = {},
): OpenBookDetail =
    {
        BookDetailSession(
            state = MutableStateFlow(state),
            onMarkComplete = onMarkComplete,
            onDiscardProgress = onDiscardProgress,
            onRestart = onRestart,
            myShelves = MutableStateFlow(myShelves),
            collections = MutableStateFlow(collections),
            onShowShelfPicker = onShowShelfPicker,
            onHideShelfPicker = onHideShelfPicker,
            onAddToShelf = onAddToShelf,
            onCreateShelfAndAdd = onCreateShelfAndAdd,
            onClearShelfError = onClearShelfError,
            onShowCollectionPicker = onShowCollectionPicker,
            onHideCollectionPicker = onHideCollectionPicker,
            onAddToCollection = onAddToCollection,
            onCreateCollectionAndAdd = onCreateCollectionAndAdd,
            onClearCollectionError = onClearCollectionError,
            close = {},
        )
    }
