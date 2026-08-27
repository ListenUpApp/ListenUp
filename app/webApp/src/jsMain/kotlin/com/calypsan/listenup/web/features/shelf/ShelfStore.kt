package com.calypsan.listenup.web.features.shelf

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfNavAction
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfUiState
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfViewModel
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailUiState
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin

/**
 * An open shelf-detail screen: its state, the three things an owner can do to it, and the teardown.
 *
 * The shelf is loaded by the session rather than by the page, so a spec can drive every state
 * without a database — and so the page never has to remember to ask.
 */
class ShelfDetailSession(
    val state: StateFlow<ShelfDetailUiState>,
    val messages: Flow<String>,
    val onRemoveBook: (String) -> Unit,
    val onReorder: (List<String>) -> Unit,
    val close: () -> Unit,
)

/** How the page gets its session, given the shelf it is showing. */
typealias OpenShelfDetail = (shelfId: String) -> ShelfDetailSession

/**
 * The production source: the shared [ShelfDetailViewModel] over the started graph.
 *
 * `loadShelf` fires here rather than in the composition: the load is what the session IS, and
 * leaving it to the page means every future caller has to remember the same two-step.
 */
fun graphShelfDetail(koin: Koin): OpenShelfDetail =
    { shelfId ->
        val viewModel = koin.get<ShelfDetailViewModel>()
        val store = ViewModelStore().apply { put(SHELF_DETAIL_STORE_KEY, viewModel) }
        viewModel.loadShelf(shelfId)
        ShelfDetailSession(
            state = viewModel.state,
            messages = viewModel.snackbarMessages,
            onRemoveBook = viewModel::removeBook,
            onReorder = viewModel::reorderBooks,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedShelfDetail(
    state: ShelfDetailUiState = ShelfDetailUiState.Loading,
    onRemoveBook: (String) -> Unit = {},
    onReorder: (List<String>) -> Unit = {},
): OpenShelfDetail =
    {
        ShelfDetailSession(
            state = MutableStateFlow(state),
            messages = emptyFlow(),
            onRemoveBook = onRemoveBook,
            onReorder = onReorder,
            close = {},
        )
    }

/**
 * An open create-or-edit screen.
 *
 * One session for both, because the ViewModel is one ViewModel in two modes — which of them it is
 * was decided by the `init` the session already called, so the page never branches on it.
 *
 * [navActions] carries the one event the page cannot derive from state: a save or delete that
 * succeeded, after which the screen must go somewhere. A `Channel`-backed flow rather than a state
 * flag, so a re-render cannot navigate twice.
 */
class ShelfEditSession(
    val state: StateFlow<CreateEditShelfUiState>,
    val navActions: Flow<CreateEditShelfNavAction>,
    val onSave: (name: String, description: String, isPrivate: Boolean) -> Unit,
    val onDelete: () -> Unit,
    val onDismissError: () -> Unit,
    val close: () -> Unit,
)

/**
 * How the page gets its session. A null `shelfId` means create; an id means edit.
 *
 * Nullable rather than two typealiases because the route already distinguishes them and the screen
 * genuinely does not: it renders the same form either way.
 */
typealias OpenShelfEdit = (shelfId: String?) -> ShelfEditSession

/** The production source: the shared [CreateEditShelfViewModel], initialised for its mode. */
fun graphShelfEdit(koin: Koin): OpenShelfEdit =
    { shelfId ->
        val viewModel = koin.get<CreateEditShelfViewModel>()
        val store = ViewModelStore().apply { put(SHELF_EDIT_STORE_KEY, viewModel) }
        if (shelfId == null) viewModel.initCreate() else viewModel.initEdit(shelfId)
        ShelfEditSession(
            state = viewModel.state,
            navActions = viewModel.navActions,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
            onDismissError = viewModel::dismissError,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedShelfEdit(
    state: CreateEditShelfUiState = CreateEditShelfUiState.Idle,
    navActions: Flow<CreateEditShelfNavAction> = emptyFlow(),
    onSave: (name: String, description: String, isPrivate: Boolean) -> Unit = { _, _, _ -> },
    onDelete: () -> Unit = {},
    onDismissError: () -> Unit = {},
): OpenShelfEdit =
    {
        ShelfEditSession(
            state = MutableStateFlow(state),
            navActions = navActions,
            onSave = onSave,
            onDelete = onDelete,
            onDismissError = onDismissError,
            close = {},
        )
    }

private const val SHELF_DETAIL_STORE_KEY = "shelf-detail"

private const val SHELF_EDIT_STORE_KEY = "shelf-edit"
