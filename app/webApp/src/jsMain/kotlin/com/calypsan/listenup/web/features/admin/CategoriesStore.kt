package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.client.presentation.admin.GenreMergeHistory
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/** An open Categories screen, every change it accepts, and the teardown for it. */
class CategoriesSession(
    val state: StateFlow<AdminCategoriesUiState>,
    val onToggleExpanded: (String) -> Unit,
    val onExpandAll: () -> Unit,
    val onCollapseAll: () -> Unit,
    val onCreate: (name: String, parentId: String?) -> Unit,
    val onRename: (id: String, name: String) -> Unit,
    val onDelete: (String) -> Unit,
    val onMove: (id: String, newParentId: String?) -> Unit,
    val onMerge: (source: String, target: String) -> Unit,
    val onClearError: () -> Unit,
    /** The merge history open for one genre (#1061), or null. */
    val mergeHistory: StateFlow<GenreMergeHistory?>,
    val mergeHistoryActions: MergeHistoryActions,
    val close: () -> Unit,
)

/** Everything the genre merge-history dialog asks of the ViewModel, carried as one value. */
class MergeHistoryActions(
    val open: (genreId: String) -> Unit,
    val close: () -> Unit,
    val undo: (MergeReceiptId) -> Unit,
    val retry: () -> Unit,
) {
    /** Factories. */
    companion object {
        /** Actions that do nothing — for pages drawn without a live ViewModel. */
        val None = MergeHistoryActions(open = {}, close = {}, undo = {}, retry = {})
    }
}

/** How the page gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenCategories = () -> CategoriesSession

/**
 * The production source: the shared [AdminCategoriesViewModel] over the started graph.
 *
 * No load call: the ViewModel observes the genre repository from its own `init`, so the tree
 * refreshes itself after every mutation rather than being re-fetched by each caller.
 */
fun graphCategories(koin: Koin): OpenCategories =
    {
        val viewModel = koin.get<AdminCategoriesViewModel>()
        val store = ViewModelStore().apply { put(CATEGORIES_STORE_KEY, viewModel) }
        CategoriesSession(
            state = viewModel.state,
            onToggleExpanded = viewModel::toggleExpanded,
            onExpandAll = viewModel::expandAll,
            onCollapseAll = viewModel::collapseAll,
            onCreate = viewModel::createGenre,
            onRename = viewModel::renameGenre,
            onDelete = viewModel::deleteGenre,
            onMove = viewModel::moveGenre,
            onMerge = viewModel::mergeGenres,
            onClearError = viewModel::clearError,
            mergeHistory = viewModel.mergeHistory,
            mergeHistoryActions =
                MergeHistoryActions(
                    open = viewModel::openMergeHistory,
                    close = viewModel::closeMergeHistory,
                    undo = viewModel::undoGenreMerge,
                    retry = viewModel::retryMergeHistory,
                ),
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
@Suppress("LongParameterList")
fun fixedCategories(
    state: AdminCategoriesUiState = AdminCategoriesUiState.Loading,
    onToggleExpanded: (String) -> Unit = {},
    onExpandAll: () -> Unit = {},
    onCollapseAll: () -> Unit = {},
    onCreate: (String, String?) -> Unit = { _, _ -> },
    onRename: (String, String) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
    onMove: (String, String?) -> Unit = { _, _ -> },
    onMerge: (String, String) -> Unit = { _, _ -> },
    onClearError: () -> Unit = {},
    mergeHistory: GenreMergeHistory? = null,
    mergeHistoryActions: MergeHistoryActions = MergeHistoryActions.None,
): OpenCategories =
    {
        CategoriesSession(
            state = MutableStateFlow(state),
            onToggleExpanded = onToggleExpanded,
            onExpandAll = onExpandAll,
            onCollapseAll = onCollapseAll,
            onCreate = onCreate,
            onRename = onRename,
            onDelete = onDelete,
            onMove = onMove,
            onMerge = onMerge,
            onClearError = onClearError,
            mergeHistory = MutableStateFlow(mergeHistory),
            mergeHistoryActions = mergeHistoryActions,
            close = {},
        )
    }

private const val CATEGORIES_STORE_KEY = "admin-categories"
