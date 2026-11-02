package com.calypsan.listenup.web.features.shelf

import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfNavAction
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfUiState
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

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

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedShelfEdit(
    state: CreateEditShelfUiState = CreateEditShelfUiState.Idle,
    navActions: Flow<CreateEditShelfNavAction> = emptyFlow(),
    onSave: (name: String, description: String, isPrivate: Boolean) -> Unit = { _, _, _ -> },
    onDelete: () -> Unit = {},
    onDismissError: () -> Unit = {},
    hasChanges: (name: String, description: String, isPrivate: Boolean) -> Boolean = { _, _, _ -> false },
): OpenShelfEdit =
    {
        ShelfEditSession(
            state = MutableStateFlow(state),
            navActions = navActions,
            onSave = onSave,
            onDelete = onDelete,
            onDismissError = onDismissError,
            hasChanges = hasChanges,
            close = {},
        )
    }
