package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState
import com.calypsan.listenup.client.presentation.admin.GenreMergeHistory
import kotlinx.coroutines.flow.MutableStateFlow

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
