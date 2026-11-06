package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.presentation.admin.ABSImportListUiState
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.ImportId
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedImports(
    state: ABSImportListUiState = ABSImportListUiState.Loading,
    onDelete: (ImportId) -> Unit = {},
    onClearError: () -> Unit = {},
    onRetry: () -> Unit = {},
): OpenImports =
    {
        ImportsSession(
            state = MutableStateFlow(state),
            onDelete = onDelete,
            onClearError = onClearError,
            onRetry = onRetry,
            close = {},
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
@Suppress("LongParameterList")
fun fixedImportFlow(
    state: ImportFlowUiState = ImportFlowUiState.Idle,
    onStart: (FileSource) -> Unit = {},
    onMapUser: (AbsUserId, UserId) -> Unit = { _, _ -> },
    onSkipUser: (AbsUserId) -> Unit = {},
    onOpenBookSearch: (AbsItemId) -> Unit = {},
    onCloseBookSearch: () -> Unit = {},
    onBookSearchQuery: (String) -> Unit = {},
    onSelectBook: (AbsItemId, BookId) -> Unit = { _, _ -> },
    onSkipBook: (AbsItemId) -> Unit = {},
    onApply: () -> Unit = {},
    onReset: () -> Unit = {},
): OpenImportFlow =
    {
        ImportFlowSession(
            state = MutableStateFlow(state),
            onStart = onStart,
            onMapUser = onMapUser,
            onSkipUser = onSkipUser,
            onOpenBookSearch = onOpenBookSearch,
            onCloseBookSearch = onCloseBookSearch,
            onBookSearchQuery = onBookSearchQuery,
            onSelectBook = onSelectBook,
            onSkipBook = onSkipBook,
            onApply = onApply,
            onReset = onReset,
            close = {},
        )
    }
