package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsUiState
import kotlinx.coroutines.flow.MutableStateFlow

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
    onClearSaveSuccess: () -> Unit = {},
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
            onClearSaveSuccess = onClearSaveSuccess,
            close = {},
        )
    }
