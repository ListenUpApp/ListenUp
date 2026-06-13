package com.calypsan.listenup.client.features.admin.import

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.import.AbsItemRef
import com.calypsan.listenup.api.dto.import.AbsUserMatch
import com.calypsan.listenup.api.dto.import.ImportResult
import com.calypsan.listenup.api.dto.import.MatchTier
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.presentation.admin.import.BookSearchState
import com.calypsan.listenup.client.presentation.admin.import.ImportFlowUiState
import com.calypsan.listenup.client.presentation.admin.import.ImportFlowViewModel
import com.calypsan.listenup.client.util.DocumentPickerResult
import com.calypsan.listenup.client.util.rememberABSBackupPicker
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_try_again
import listenup.composeapp.generated.resources.import_apply_import
import listenup.composeapp.generated.resources.import_analyzing_subtitle
import listenup.composeapp.generated.resources.import_applying_subtitle
import listenup.composeapp.generated.resources.import_auto_matched
import listenup.composeapp.generated.resources.import_book_assigned
import listenup.composeapp.generated.resources.import_book_search_cancel
import listenup.composeapp.generated.resources.import_book_search_hint
import listenup.composeapp.generated.resources.import_book_search_no_results
import listenup.composeapp.generated.resources.import_book_skip
import listenup.composeapp.generated.resources.import_books_matched
import listenup.composeapp.generated.resources.import_books_skipped
import listenup.composeapp.generated.resources.import_choose_backup_file
import listenup.composeapp.generated.resources.import_done_subtitle
import listenup.composeapp.generated.resources.import_done_title
import listenup.composeapp.generated.resources.import_error_title
import listenup.composeapp.generated.resources.import_finish
import listenup.composeapp.generated.resources.import_idle_subtitle
import listenup.composeapp.generated.resources.import_idle_title
import listenup.composeapp.generated.resources.import_items_matched
import listenup.composeapp.generated.resources.import_matching_current_item
import listenup.composeapp.generated.resources.import_no_attention_needed
import listenup.composeapp.generated.resources.import_no_books_to_review
import listenup.composeapp.generated.resources.import_records_imported
import listenup.composeapp.generated.resources.import_review_books_section
import listenup.composeapp.generated.resources.import_review_users_section
import listenup.composeapp.generated.resources.import_review_users_unresolved_warning
import listenup.composeapp.generated.resources.import_search_and_assign
import listenup.composeapp.generated.resources.import_sessions_importable
import listenup.composeapp.generated.resources.import_flow_sessions_imported
import listenup.composeapp.generated.resources.import_flow_title
import listenup.composeapp.generated.resources.import_sessions_written
import listenup.composeapp.generated.resources.import_uploading_subtitle
import listenup.composeapp.generated.resources.import_uploading_title
import listenup.composeapp.generated.resources.import_user_accept_suggestion
import listenup.composeapp.generated.resources.import_user_assign
import listenup.composeapp.generated.resources.import_user_assigned_to
import listenup.composeapp.generated.resources.import_user_needs_review
import listenup.composeapp.generated.resources.import_user_pick_user
import listenup.composeapp.generated.resources.import_user_skip
import listenup.composeapp.generated.resources.import_user_skipped
import listenup.composeapp.generated.resources.import_user_suggested
import listenup.composeapp.generated.resources.import_users_matched
import listenup.composeapp.generated.resources.import_writing_current_item
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Single-screen host for the entire ABS import flow. All state transitions are driven by
 * [ImportFlowUiState] from [ImportFlowViewModel]; no navigation occurs between phases.
 *
 * [Idle] hosts the OS file picker: picking a file immediately calls [ImportFlowViewModel.start].
 * Progress phases reuse the visual treatment from LibraryScanScreen (label + current item + counters).
 * [Review] surfaces unmatched / ambiguous items for admin attention with per-item skip toggles.
 * [Done] / [Error] carry action buttons that return to the back stack or reset the flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportFlowScreen(
    onNavigateBack: () -> Unit,
    viewModel: ImportFlowViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The file picker is declared here (at the screen level) so the composable lifecycle
    // that wires the ActivityResult launcher is stable across all phases. It is only
    // *launched* from the Idle content when the admin taps "Choose backup file".
    val filePicker =
        rememberABSBackupPicker { result ->
            if (result is DocumentPickerResult.Success) {
                viewModel.start(result.fileSource)
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.import_flow_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            when (val state = uiState) {
                is ImportFlowUiState.Idle -> {
                    IdleContent(onChooseFile = { filePicker.launch() })
                }

                is ImportFlowUiState.Uploading -> {
                    UploadingContent(state = state)
                }

                is ImportFlowUiState.Analyzing -> {
                    AnalyzingContent(state = state)
                }

                is ImportFlowUiState.Review -> {
                    ReviewContent(
                        state = state,
                        onSetUserMapping = { absUserId, userId ->
                            viewModel.setUserMapping(absUserId, userId)
                        },
                        onSkipUser = { viewModel.skipUser(it) },
                        onOpenBookSearch = { viewModel.openBookSearch(it) },
                        onCloseBookSearch = { viewModel.closeBookSearch() },
                        onBookSearchQueryChange = { viewModel.updateBookSearchQuery(it) },
                        onSelectBook = { absItemId, bookId ->
                            viewModel.selectBook(absItemId, bookId)
                        },
                        onSkipBook = { viewModel.skipBook(it) },
                        onConfirm = { viewModel.confirmAndApply() },
                    )
                }

                is ImportFlowUiState.Applying -> {
                    ApplyingContent(state = state)
                }

                is ImportFlowUiState.Done -> {
                    DoneContent(
                        result = state.result,
                        onFinish = {
                            viewModel.reset()
                            onNavigateBack()
                        },
                    )
                }

                is ImportFlowUiState.Error -> {
                    ErrorContent(
                        message = state.error.localized(),
                        onTryAgain = { viewModel.reset() },
                    )
                }
            }
        }
    }
}

// ─── Idle ─────────────────────────────────────────────────────────────────────

@Composable
private fun IdleContent(onChooseFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.import_idle_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.import_idle_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        ListenUpButton(
            text = stringResource(Res.string.import_choose_backup_file),
            onClick = onChooseFile,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── Uploading ────────────────────────────────────────────────────────────────

@Composable
private fun UploadingContent(state: ImportFlowUiState.Uploading) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            text = stringResource(Res.string.import_uploading_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.import_uploading_subtitle, state.filename),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

// ─── Analyzing ───────────────────────────────────────────────────────────────

@Composable
private fun AnalyzingContent(state: ImportFlowUiState.Analyzing) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            text = stringResource(Res.string.import_flow_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.import_analyzing_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (state.total > 0) {
            LinearProgressIndicator(
                progress = { state.done.toFloat() / state.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.import_items_matched, state.done, state.total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.currentItem?.let { item ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.import_matching_current_item, item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                text = stringResource(Res.string.import_users_matched, state.usersMatched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.import_books_matched, state.booksMatched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Review ───────────────────────────────────────────────────────────────────

@Composable
private fun ReviewContent(
    state: ImportFlowUiState.Review,
    onSetUserMapping: (AbsUserId, UserId) -> Unit,
    onSkipUser: (AbsUserId) -> Unit,
    onOpenBookSearch: (AbsItemId) -> Unit,
    onCloseBookSearch: () -> Unit,
    onBookSearchQueryChange: (String) -> Unit,
    onSelectBook: (AbsItemId, BookId) -> Unit,
    onSkipBook: (AbsItemId) -> Unit,
    onConfirm: () -> Unit,
) {
    val analysis = state.analysis
    val attentionItems = analysis.ambiguous + analysis.unmatched
    val autoMatchedCount =
        analysis.bookMatchCounts
            .filterKeys { it != MatchTier.AMBIGUOUS && it != MatchTier.UNMATCHED }
            .values
            .sum()
    val unresolvedUserCount =
        analysis.userMatches.count { match ->
            !state.userMappings.containsKey(match.absUserId) &&
                !state.skippedUsers.contains(match.absUserId)
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Users section ─────────────────────────────────────────────────────
        item(key = "users_header") {
            SectionLabel(stringResource(Res.string.import_review_users_section))
        }

        if (analysis.userMatches.isEmpty()) {
            item(key = "users_empty") {
                Text(
                    text = stringResource(Res.string.import_no_attention_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(analysis.userMatches, key = { "user_${it.absUserId.value}" }) { match ->
                UserMatchCard(
                    match = match,
                    assignedUserId = state.userMappings[match.absUserId],
                    isSkipped = state.skippedUsers.contains(match.absUserId),
                    listenupUsers = state.listenupUsers,
                    onAcceptSuggestion = { suggestedId ->
                        onSetUserMapping(match.absUserId, suggestedId)
                    },
                    onAssign = { pickedUser ->
                        onSetUserMapping(match.absUserId, UserId(pickedUser.id))
                    },
                    onSkip = { onSkipUser(match.absUserId) },
                )
            }
        }

        // ── Books section ─────────────────────────────────────────────────────
        item(key = "books_header") {
            SectionLabel(stringResource(Res.string.import_review_books_section))
        }

        // Auto-match summary line
        if (autoMatchedCount > 0) {
            item(key = "auto_summary") {
                Text(
                    text =
                        stringResource(Res.string.import_books_matched, autoMatchedCount.toString()) +
                            " — " + stringResource(Res.string.import_auto_matched),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (analysis.importableSessionCount > 0) {
            item(key = "sessions_summary") {
                Text(
                    text = stringResource(Res.string.import_sessions_importable, analysis.importableSessionCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (attentionItems.isEmpty()) {
            item(key = "books_no_review") {
                Text(
                    text = stringResource(Res.string.import_no_books_to_review),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(attentionItems, key = { "book_${it.absItemId.value}" }) { item ->
                val isSearchOpen = state.bookSearch?.absItemId == item.absItemId
                BookReviewCard(
                    item = item,
                    isUnmatched = analysis.unmatched.any { it.absItemId == item.absItemId },
                    bookOverride = state.bookOverrides[item.absItemId],
                    hasOverrideEntry = state.bookOverrides.containsKey(item.absItemId),
                    bookSearch = if (isSearchOpen) state.bookSearch else null,
                    onOpenSearch = { onOpenBookSearch(item.absItemId) },
                    onCloseSearch = onCloseBookSearch,
                    onSearchQueryChange = onBookSearchQueryChange,
                    onSelectBook = { bookId -> onSelectBook(item.absItemId, bookId) },
                    onSkip = { onSkipBook(item.absItemId) },
                )
            }
        }

        // ── Apply ─────────────────────────────────────────────────────────────
        item(key = "apply_warning") {
            if (unresolvedUserCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.import_review_users_unresolved_warning, unresolvedUserCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        item(key = "apply_button") {
            Spacer(Modifier.height(4.dp))
            ListenUpButton(
                text = stringResource(Res.string.import_apply_import),
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─── User match card ──────────────────────────────────────────────────────────

@Composable
private fun UserMatchCard(
    match: AbsUserMatch,
    assignedUserId: UserId?,
    isSkipped: Boolean,
    listenupUsers: List<AdminUserInfo>,
    onAcceptSuggestion: (UserId) -> Unit,
    onAssign: (AdminUserInfo) -> Unit,
    onSkip: () -> Unit,
) {
    val isResolved = assignedUserId != null || isSkipped
    val assignedUser = assignedUserId?.let { id -> listenupUsers.find { it.id == id.value } }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when {
                        isSkipped -> MaterialTheme.colorScheme.surfaceContainerLowest
                        assignedUserId != null -> MaterialTheme.colorScheme.surfaceContainerLow
                        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    },
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            UserIdentityRow(
                match = match,
                isResolved = isResolved,
                isSkipped = isSkipped,
                assignedUser = assignedUser,
                assignedUserId = assignedUserId,
            )
            val suggestedUserId = match.suggestedUserId
            if (suggestedUserId != null && assignedUserId == null && !isSkipped) {
                UserSuggestionRow(
                    suggestedId = suggestedUserId,
                    listenupUsers = listenupUsers,
                    onAccept = onAcceptSuggestion,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UserAssignDropdown(listenupUsers = listenupUsers, onAssign = onAssign)
                OutlinedButton(onClick = onSkip, shape = MaterialTheme.shapes.extraLarge) {
                    Text(stringResource(Res.string.import_user_skip))
                }
            }
        }
    }
}

@Composable
private fun UserIdentityRow(
    match: AbsUserMatch,
    isResolved: Boolean,
    isSkipped: Boolean,
    assignedUser: AdminUserInfo?,
    assignedUserId: UserId?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = match.absUsername, style = MaterialTheme.typography.bodyMedium)
            match.absEmail?.let { email ->
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val badgeText =
            when {
                isSkipped -> stringResource(Res.string.import_user_skipped)
                assignedUser != null -> stringResource(Res.string.import_user_assigned_to, assignedUser.displayableName)
                assignedUserId != null -> stringResource(Res.string.import_user_assigned_to, assignedUserId.value)
                else -> stringResource(Res.string.import_user_needs_review)
            }
        Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (!isResolved) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun UserSuggestionRow(
    suggestedId: UserId,
    listenupUsers: List<AdminUserInfo>,
    onAccept: (UserId) -> Unit,
) {
    val suggestedUser = listenupUsers.find { it.id == suggestedId.value }
    val suggestionLabel = suggestedUser?.displayableName ?: suggestedId.value
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.import_user_suggested, suggestionLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onAccept(suggestedId) }) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(Res.string.import_user_accept_suggestion))
        }
    }
}

@Composable
private fun UserAssignDropdown(
    listenupUsers: List<AdminUserInfo>,
    onAssign: (AdminUserInfo) -> Unit,
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { dropdownExpanded = true },
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Text(stringResource(Res.string.import_user_assign))
        }
        DropdownMenu(
            expanded = dropdownExpanded,
            onDismissRequest = { dropdownExpanded = false },
        ) {
            if (listenupUsers.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(Res.string.import_user_pick_user),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { dropdownExpanded = false },
                )
            } else {
                listenupUsers.forEach { user ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(text = user.displayableName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = user.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            dropdownExpanded = false
                            onAssign(user)
                        },
                    )
                }
            }
        }
    }
}

// ─── Book review card ─────────────────────────────────────────────────────────

@Composable
private fun BookReviewCard(
    item: AbsItemRef,
    isUnmatched: Boolean,
    bookOverride: BookId?,
    hasOverrideEntry: Boolean,
    bookSearch: BookSearchState?,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectBook: (BookId) -> Unit,
    onSkip: () -> Unit,
) {
    val isAssigned = hasOverrideEntry && bookOverride != null
    val isSkipped = hasOverrideEntry && bookOverride == null
    val isResolved = isAssigned || isSkipped
    val tierLabel =
        if (isUnmatched) {
            MatchTier.UNMATCHED.name
                .lowercase()
                .replaceFirstChar { it.uppercase() }
        } else {
            MatchTier.AMBIGUOUS.name
                .lowercase()
                .replaceFirstChar { it.uppercase() }
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when {
                        isSkipped -> MaterialTheme.colorScheme.surfaceContainerLowest
                        isAssigned -> MaterialTheme.colorScheme.surfaceContainerLow
                        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    },
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BookTitleRow(
                item = item,
                isAssigned = isAssigned,
                isSkipped = isSkipped,
                isResolved = isResolved,
                tierLabel = tierLabel,
            )
            if (bookSearch != null) {
                BookSearchPanel(
                    bookSearch = bookSearch,
                    onSearchQueryChange = onSearchQueryChange,
                    onCloseSearch = onCloseSearch,
                    onSelectBook = onSelectBook,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenSearch, shape = MaterialTheme.shapes.extraLarge) {
                        Text(stringResource(Res.string.import_search_and_assign))
                    }
                    OutlinedButton(onClick = onSkip, shape = MaterialTheme.shapes.extraLarge) {
                        Text(stringResource(Res.string.import_book_skip))
                    }
                }
            }
        }
    }
}

@Composable
private fun BookTitleRow(
    item: AbsItemRef,
    isAssigned: Boolean,
    isSkipped: Boolean,
    isResolved: Boolean,
    tierLabel: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.title, style = MaterialTheme.typography.bodyMedium)
            val identifiers =
                listOfNotNull(
                    item.asin?.let { "ASIN: $it" },
                    item.isbn?.let { "ISBN: $it" },
                ).joinToString(" · ")
            if (identifiers.isNotEmpty()) {
                Text(
                    text = identifiers,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text =
                when {
                    isAssigned -> stringResource(Res.string.import_book_assigned)
                    isSkipped -> stringResource(Res.string.import_user_skipped)
                    else -> tierLabel
                },
            style = MaterialTheme.typography.labelSmall,
            color =
                if (!isResolved) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun BookSearchPanel(
    bookSearch: BookSearchState,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onSelectBook: (BookId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = bookSearch.query,
                onValueChange = onSearchQueryChange,
                placeholder = {
                    Text(
                        text = stringResource(Res.string.import_book_search_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                trailingIcon = {
                    if (bookSearch.isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                },
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onCloseSearch) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.import_book_search_cancel),
                )
            }
        }
        if (bookSearch.query.isNotBlank() && !bookSearch.isSearching && bookSearch.results.isEmpty()) {
            Text(
                text = stringResource(Res.string.import_book_search_no_results),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        bookSearch.results.forEach { hit ->
            TextButton(onClick = { onSelectBook(hit.bookId) }, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = hit.title, style = MaterialTheme.typography.bodyMedium)
                    if (hit.author.isNotBlank()) {
                        Text(
                            text = hit.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

// ─── Applying ────────────────────────────────────────────────────────────────

@Composable
private fun ApplyingContent(state: ImportFlowUiState.Applying) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            text = stringResource(Res.string.import_apply_import),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.import_applying_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (state.total > 0) {
            LinearProgressIndicator(
                progress = { state.done.toFloat() / state.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.import_items_matched, state.done, state.total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.currentItem?.let { item ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.import_writing_current_item, item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.import_sessions_written, state.sessionsWritten),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Done ────────────────────────────────────────────────────────────────────

@Composable
private fun DoneContent(
    result: ImportResult,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.import_done_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.import_done_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.import_records_imported, result.importedCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.import_flow_sessions_imported, result.sessionsImported),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.import_books_skipped, result.skippedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        ListenUpButton(
            text = stringResource(Res.string.import_finish),
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── Error ───────────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(
    message: String,
    onTryAgain: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.import_error_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        ListenUpButton(
            text = stringResource(Res.string.common_try_again),
            onClick = onTryAgain,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
