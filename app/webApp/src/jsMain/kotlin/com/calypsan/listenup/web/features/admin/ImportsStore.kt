package com.calypsan.listenup.web.features.admin

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.client.presentation.admin.ABSImportHubViewModel
import com.calypsan.listenup.client.presentation.admin.ABSImportListUiState
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowViewModel
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FileSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/** An open Imports list: what has been imported before, and the way to be rid of one. */
class ImportsSession(
    val state: StateFlow<ABSImportListUiState>,
    val onDelete: (ImportId) -> Unit,
    val onClearError: () -> Unit,
    val onRetry: () -> Unit,
    val close: () -> Unit,
)

/** How the list gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenImports = () -> ImportsSession

/** The production source: the shared [ABSImportHubViewModel], which loads its own list on init. */
fun graphImports(koin: Koin): OpenImports =
    {
        val viewModel = koin.get<ABSImportHubViewModel>()
        val store = ViewModelStore().apply { put(IMPORTS_STORE_KEY, viewModel) }
        ImportsSession(
            state = viewModel.listState,
            onDelete = viewModel::deleteImport,
            onClearError = viewModel::clearError,
            onRetry = viewModel::refresh,
            close = store::clear,
        )
    }

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

/**
 * An open import run: one linear pipeline from a picked file to a written history.
 *
 * Every gesture the flow accepts, in the order the flow accepts them. There is no `onBack` — the
 * ViewModel's own KDoc calls this "a destructive pipeline, not a wizard with a back button", and a
 * session that offered a step back would be describing a flow that does not exist.
 */
@Suppress("LongParameterList")
class ImportFlowSession(
    val state: StateFlow<ImportFlowUiState>,
    val onStart: (FileSource) -> Unit,
    val onMapUser: (AbsUserId, UserId) -> Unit,
    val onSkipUser: (AbsUserId) -> Unit,
    val onOpenBookSearch: (AbsItemId) -> Unit,
    val onCloseBookSearch: () -> Unit,
    val onBookSearchQuery: (String) -> Unit,
    val onSelectBook: (AbsItemId, BookId) -> Unit,
    val onSkipBook: (AbsItemId) -> Unit,
    val onApply: () -> Unit,
    val onReset: () -> Unit,
    val close: () -> Unit,
)

/** How the flow page gets its state. */
typealias OpenImportFlow = () -> ImportFlowSession

/** The production source: the shared [ImportFlowViewModel], starting at Idle. */
fun graphImportFlow(koin: Koin): OpenImportFlow =
    {
        val viewModel = koin.get<ImportFlowViewModel>()
        val store = ViewModelStore().apply { put(IMPORT_FLOW_STORE_KEY, viewModel) }
        ImportFlowSession(
            state = viewModel.uiState,
            onStart = viewModel::start,
            onMapUser = viewModel::setUserMapping,
            onSkipUser = viewModel::skipUser,
            onOpenBookSearch = viewModel::openBookSearch,
            onCloseBookSearch = viewModel::closeBookSearch,
            onBookSearchQuery = viewModel::updateBookSearchQuery,
            onSelectBook = viewModel::selectBook,
            onSkipBook = viewModel::skipBook,
            onApply = viewModel::confirmAndApply,
            onReset = viewModel::reset,
            close = store::clear,
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

private const val IMPORTS_STORE_KEY = "admin-imports"

private const val IMPORT_FLOW_STORE_KEY = "admin-import-flow"
