package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.share.ShareTarget
import com.calypsan.listenup.client.share.ShareLinkCodec
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import com.calypsan.listenup.client.domain.model.BookDocument
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
    /**
     * Shares this book, reporting how it went so the page can say the right thing.
     *
     * Suspends because building the link needs the server's own identity — the instance id and
     * remote URL a recipient's client uses to resolve the link back to THIS server.
     */
    val onShare: suspend (title: String) -> ShareOutcome,
    /**
     * The supplementary documents shipped with this book — a PDF map, a bonus chapter, cover art.
     *
     * ⛔ Another flow the ViewModel has always exposed and web never read. Android renders these in
     * its detail body and iOS has a whole DocumentReader feature; web showed nothing, so a
     * self-hoster who put a PDF beside their audiobook had no way to learn from the browser that
     * the server had even found it.
     *
     * `onOpenDocument` is deliberately NOT wired. Its job is to resolve a *local file path* for a
     * platform PDF viewer, and web has neither: the document route is already cookie-authenticated
     * for exactly this (`BLOB_READ_PROVIDER`, "an `<img src>` or a document link"), so the browser
     * opens the URL and renders it with machinery far better than anything we would port. On web
     * `ensureLocal` would also write the bytes into a set that discards them — see
     * `BrowserDocumentStorage`.
     */
    val documents: StateFlow<List<BookDocument>>,
    /**
     * Ask the server again, now.
     *
     * ⛔ `Ready.showServerWarning` has been in the shared state all along and web rendered it
     * nowhere, so a reader whose server had gone away saw an ordinary book page — the library
     * reads from OPFS, so nothing looks wrong until Play quietly fails. Both natives show an
     * offline banner here and back its Retry with this call, which tears the sync firehose down
     * and re-opens it rather than waiting out the automatic backoff.
     */
    val onRetryConnection: () -> Unit,
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
            onShare = { title -> shareBook(koin, bookId, title) },
            documents = viewModel.documents,
            onRetryConnection = viewModel::retryConnection,
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
    onShare: suspend (String) -> ShareOutcome = { ShareOutcome.SHARED },
    documents: List<BookDocument> = emptyList(),
    onRetryConnection: () -> Unit = {},
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
            onShare = onShare,
            documents = MutableStateFlow(documents),
            onRetryConnection = onRetryConnection,
            close = {},
        )
    }

/**
 * Builds this book's share link from the server's own identity, then hands it to the browser.
 *
 * ⛔ The link carries the instance id and remote URL, not just the book id. A recipient's client
 * resolves a share against the server it names — without them the link only works for someone
 * already pointed at the same server, which is exactly the person who did not need a link.
 *
 * The sentence is the natives' sentence, character for character, and the URL comes from the shared
 * [ShareLinkCodec]: three clients producing three dialects of the same link would be three bugs
 * waiting for someone to paste the wrong one.
 *
 * A server that cannot say who it is yields [ShareOutcome.FAILED] rather than a link missing its
 * identity — a link that silently resolves nowhere is worse than an honest refusal.
 */
private suspend fun shareBook(
    koin: Koin,
    bookId: String,
    title: String,
): ShareOutcome {
    val info = koin.get<InstanceRepository>().getServerInfoOrNull() ?: return ShareOutcome.FAILED
    val url =
        ShareLinkCodec.encode(
            ShareTarget.Book(
                bookId = BookId(bookId),
                serverInstanceId = info.instanceId,
                serverUrl = info.remoteUrl?.trimEnd('/'),
            ),
        )
    return shareBookLink(title = title, text = "Check out $title on ListenUp!\n$url", url = url)
}
