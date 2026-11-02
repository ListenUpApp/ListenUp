package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailNavAction
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.LastMatchEvent
import com.calypsan.listenup.client.presentation.bookdetail.LastMatchUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A session over a state that never changes — the shape specs use in place of the graph. */
@Suppress("LongParameterList")
fun fixedBookDetail(
    state: BookDetailUiState,
    onMarkComplete: (Long?, Long) -> Unit = { _, _ -> },
    onDiscardProgress: () -> Unit = {},
    onRestart: () -> Unit = {},
    myShelves: List<Shelf> = emptyList(),
    shelvesContainingBook: List<Shelf> = emptyList(),
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
    onDeleteBook: () -> Unit = {},
    onClearDeleteError: () -> Unit = {},
    onReleaseFromInbox: () -> Unit = {},
    onRestoreToAllBooks: () -> Unit = {},
    navActions: Flow<BookDetailNavAction> = emptyFlow(),
    lastMatch: StateFlow<LastMatchUi?> = MutableStateFlow(null),
    lastMatchEvents: Flow<LastMatchEvent> = emptyFlow(),
    onSeeWhatChanged: () -> Unit = {},
    onCloseWhatChanged: () -> Unit = {},
    onUndoLastMatch: () -> Unit = {},
): OpenBookDetail =
    {
        BookDetailSession(
            state = MutableStateFlow(state),
            onMarkComplete = onMarkComplete,
            onDiscardProgress = onDiscardProgress,
            onRestart = onRestart,
            myShelves = MutableStateFlow(myShelves),
            shelvesContainingBook = MutableStateFlow(shelvesContainingBook),
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
            onDeleteBook = onDeleteBook,
            onClearDeleteError = onClearDeleteError,
            onReleaseFromInbox = onReleaseFromInbox,
            onRestoreToAllBooks = onRestoreToAllBooks,
            navActions = navActions,
            lastMatch = lastMatch,
            lastMatchEvents = lastMatchEvents,
            onSeeWhatChanged = onSeeWhatChanged,
            onCloseWhatChanged = onCloseWhatChanged,
            onUndoLastMatch = onUndoLastMatch,
            close = {},
        )
    }
