package com.calypsan.listenup.web.features.admin

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailViewModel
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/** An open Collections list, the gestures it accepts, and the teardown for it. */
class CollectionsSession(
    val state: StateFlow<AdminCollectionsUiState>,
    val onCreate: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onClearError: () -> Unit,
    val onClearCreateSuccess: () -> Unit,
    val close: () -> Unit,
)

/** How the list gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenCollections = () -> CollectionsSession

/** The production source: the shared [AdminCollectionsViewModel], which observes its own list. */
fun graphCollections(koin: Koin): OpenCollections =
    {
        val viewModel = koin.get<AdminCollectionsViewModel>()
        val store = ViewModelStore().apply { put(COLLECTIONS_STORE_KEY, viewModel) }
        CollectionsSession(
            state = viewModel.state,
            onCreate = viewModel::createCollection,
            onDelete = viewModel::deleteCollection,
            onClearError = viewModel::clearError,
            onClearCreateSuccess = viewModel::clearCreateSuccess,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedCollections(
    state: AdminCollectionsUiState = AdminCollectionsUiState.Loading,
    onCreate: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onClearError: () -> Unit = {},
    onClearCreateSuccess: () -> Unit = {},
): OpenCollections =
    {
        CollectionsSession(
            state = MutableStateFlow(state),
            onCreate = onCreate,
            onDelete = onDelete,
            onClearError = onClearError,
            onClearCreateSuccess = onClearCreateSuccess,
            close = {},
        )
    }

/** An open collection: its books, who it is shared with, and every change either accepts. */
@Suppress("LongParameterList")
class CollectionDetailSession(
    val state: StateFlow<AdminCollectionDetailUiState>,
    val onNameChange: (String) -> Unit,
    val onSaveName: () -> Unit,
    val onRemoveBook: (String) -> Unit,
    val onOpenAddBooks: () -> Unit,
    val onCloseAddBooks: () -> Unit,
    val onBookQuery: (String) -> Unit,
    val onAddBook: (String) -> Unit,
    val onShowAddMember: () -> Unit,
    val onHideAddMember: () -> Unit,
    val onShare: (String) -> Unit,
    val onRevokeShare: (String) -> Unit,
    val onClearError: () -> Unit,
    val close: () -> Unit,
)

/** How the detail page gets its state, keyed on which collection. */
typealias OpenCollectionDetail = (collectionId: String) -> CollectionDetailSession

/**
 * The production source: the shared [AdminCollectionDetailViewModel], built for [collectionId].
 *
 * The id is a *constructor* parameter, not a load call — the ViewModel builds its observe pipeline
 * around it in `init` — so a session cannot be repointed at a different collection. The route keys
 * on the id for exactly that reason.
 */
fun graphCollectionDetail(koin: Koin): OpenCollectionDetail =
    { collectionId ->
        val viewModel = koin.get<AdminCollectionDetailViewModel> { parametersOf(collectionId) }
        val store = ViewModelStore().apply { put(collectionId, viewModel) }
        CollectionDetailSession(
            state = viewModel.state,
            onNameChange = viewModel::updateName,
            onSaveName = viewModel::saveName,
            onRemoveBook = viewModel::removeBook,
            onOpenAddBooks = viewModel::openAddBooks,
            onCloseAddBooks = viewModel::closeAddBooks,
            onBookQuery = viewModel::onBookQueryChange,
            onAddBook = viewModel::addBookFromSearch,
            onShowAddMember = viewModel::showAddMemberSheet,
            onHideAddMember = viewModel::hideAddMemberSheet,
            onShare = viewModel::shareWithUser,
            onRevokeShare = viewModel::revokeShare,
            onClearError = viewModel::clearError,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
@Suppress("LongParameterList")
fun fixedCollectionDetail(
    state: AdminCollectionDetailUiState = AdminCollectionDetailUiState.Loading,
    onNameChange: (String) -> Unit = {},
    onSaveName: () -> Unit = {},
    onRemoveBook: (String) -> Unit = {},
    onOpenAddBooks: () -> Unit = {},
    onCloseAddBooks: () -> Unit = {},
    onBookQuery: (String) -> Unit = {},
    onAddBook: (String) -> Unit = {},
    onShowAddMember: () -> Unit = {},
    onHideAddMember: () -> Unit = {},
    onShare: (String) -> Unit = {},
    onRevokeShare: (String) -> Unit = {},
    onClearError: () -> Unit = {},
): OpenCollectionDetail =
    {
        CollectionDetailSession(
            state = MutableStateFlow(state),
            onNameChange = onNameChange,
            onSaveName = onSaveName,
            onRemoveBook = onRemoveBook,
            onOpenAddBooks = onOpenAddBooks,
            onCloseAddBooks = onCloseAddBooks,
            onBookQuery = onBookQuery,
            onAddBook = onAddBook,
            onShowAddMember = onShowAddMember,
            onHideAddMember = onHideAddMember,
            onShare = onShare,
            onRevokeShare = onRevokeShare,
            onClearError = onClearError,
            close = {},
        )
    }

private const val COLLECTIONS_STORE_KEY = "admin-collections"
