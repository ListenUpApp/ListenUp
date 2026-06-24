package com.calypsan.listenup.client.features.admin.import

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.AbsItemRef
import com.calypsan.listenup.api.dto.imports.AbsUserMatch
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.api.dto.imports.MatchTier
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.StatTile
import com.calypsan.listenup.client.design.components.StatTileTone
import com.calypsan.listenup.client.design.components.WizardStepTracker
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.presentation.admin.imports.BookSearchState
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowViewModel
import com.calypsan.listenup.client.util.DocumentPickerResult
import com.calypsan.listenup.client.util.rememberABSBackupPicker
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_try_again
import listenup.composeapp.generated.resources.import_all_done
import listenup.composeapp.generated.resources.import_apply_import
import listenup.composeapp.generated.resources.import_analyzing_subtitle
import listenup.composeapp.generated.resources.import_auto_matched
import listenup.composeapp.generated.resources.import_book_assigned
import listenup.composeapp.generated.resources.import_book_search_cancel
import listenup.composeapp.generated.resources.import_book_search_hint
import listenup.composeapp.generated.resources.import_book_search_no_results
import listenup.composeapp.generated.resources.import_book_skip
import listenup.composeapp.generated.resources.import_books_matched
import listenup.composeapp.generated.resources.import_choose_backup_file
import listenup.composeapp.generated.resources.import_currently_writing_label
import listenup.composeapp.generated.resources.import_data_stays_on_server
import listenup.composeapp.generated.resources.import_done_title
import listenup.composeapp.generated.resources.import_error_title
import listenup.composeapp.generated.resources.import_finish
import listenup.composeapp.generated.resources.import_flow_eyebrow
import listenup.composeapp.generated.resources.import_flow_step_apply
import listenup.composeapp.generated.resources.import_flow_step_done
import listenup.composeapp.generated.resources.import_flow_step_review
import listenup.composeapp.generated.resources.import_flow_step_upload
import listenup.composeapp.generated.resources.import_idle_subtitle
import listenup.composeapp.generated.resources.import_idle_title
import listenup.composeapp.generated.resources.import_items_matched
import listenup.composeapp.generated.resources.import_items_written_label
import listenup.composeapp.generated.resources.import_keep_app_open
import listenup.composeapp.generated.resources.import_matching_current_item
import listenup.composeapp.generated.resources.import_no_attention_needed
import listenup.composeapp.generated.resources.common_percent
import listenup.composeapp.generated.resources.import_no_books_to_review
import listenup.composeapp.generated.resources.import_review_books_section
import listenup.composeapp.generated.resources.import_review_users_section
import listenup.composeapp.generated.resources.import_review_users_unresolved_warning
import listenup.composeapp.generated.resources.import_search_and_assign
import listenup.composeapp.generated.resources.import_sessions_importable
import listenup.composeapp.generated.resources.import_flow_sessions_imported
import listenup.composeapp.generated.resources.import_flow_title
import listenup.composeapp.generated.resources.import_skipped_books_subtitle
import listenup.composeapp.generated.resources.import_skipped_books_title
import listenup.composeapp.generated.resources.import_stat_records_imported
import listenup.composeapp.generated.resources.import_stat_sessions_imported
import listenup.composeapp.generated.resources.import_stat_sessions_written
import listenup.composeapp.generated.resources.import_stat_users_merged
import listenup.composeapp.generated.resources.import_to_review_count
import listenup.composeapp.generated.resources.import_uploading_subtitle
import listenup.composeapp.generated.resources.import_uploading_title
import listenup.composeapp.generated.resources.import_user_assign
import listenup.composeapp.generated.resources.import_user_assigned_to
import listenup.composeapp.generated.resources.import_user_needs_review
import listenup.composeapp.generated.resources.import_user_pick_user
import listenup.composeapp.generated.resources.import_user_skip
import listenup.composeapp.generated.resources.import_user_skipped
import listenup.composeapp.generated.resources.import_users_in_backup
import listenup.composeapp.generated.resources.import_writing_current_item
import listenup.composeapp.generated.resources.import_writing_history_subtitle
import listenup.composeapp.generated.resources.import_writing_history_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val CONTENT_MAX_WIDTH = 560.dp

// Zero-based wizard step index for each progress phase, mapped onto the 4-step tracker.
private const val STEP_UPLOAD = 0
private const val STEP_REVIEW = 1
private const val STEP_APPLY = 2
private const val STEP_DONE = 3

/**
 * Single-screen host for the entire ABS import flow. All state transitions are driven by
 * [ImportFlowUiState] from [ImportFlowViewModel]; no navigation occurs between phases.
 *
 * The flow wears the M3 Expressive wizard chrome — a colour-blocked [ColorBlockHero] hosting a
 * [WizardStepTracker] (Upload → Review → Apply → Done) — over expressive per-phase content:
 * scallop medallions, the wavy progress indicator, tonal stat tiles, and status-toned review cards.
 *
 * [Idle] hosts the OS file picker: picking a file immediately calls [ImportFlowViewModel.start].
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

    ListenUpScaffold { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            FlowHero(state = uiState, onBack = onNavigateBack)
            CenteredColumn {
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
                            onSelectBook = { absItemId, bookId -> viewModel.selectBook(absItemId, bookId) },
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
}

// ─── Wizard chrome ─────────────────────────────────────────────────────────────

@Composable
private fun FlowHero(
    state: ImportFlowUiState,
    onBack: () -> Unit,
) {
    val title =
        when (state) {
            is ImportFlowUiState.Idle -> stringResource(Res.string.import_idle_title)
            is ImportFlowUiState.Uploading -> stringResource(Res.string.import_uploading_title)
            is ImportFlowUiState.Analyzing -> stringResource(Res.string.import_flow_title)
            is ImportFlowUiState.Review -> stringResource(Res.string.import_review_users_section)
            is ImportFlowUiState.Applying -> stringResource(Res.string.import_apply_import)
            is ImportFlowUiState.Done -> stringResource(Res.string.import_done_title)
            is ImportFlowUiState.Error -> stringResource(Res.string.import_error_title)
        }
    val step =
        when (state) {
            is ImportFlowUiState.Uploading, is ImportFlowUiState.Analyzing -> STEP_UPLOAD
            is ImportFlowUiState.Review -> STEP_REVIEW
            is ImportFlowUiState.Applying -> STEP_APPLY
            is ImportFlowUiState.Done -> STEP_DONE
            else -> null
        }
    val steps =
        listOf(
            stringResource(Res.string.import_flow_step_upload),
            stringResource(Res.string.import_flow_step_review),
            stringResource(Res.string.import_flow_step_apply),
            stringResource(Res.string.import_flow_step_done),
        )
    ColorBlockHero(
        title = title,
        badgeIcon = Icons.Outlined.CloudUpload,
        onBack = onBack,
        modifier = Modifier.fillMaxWidth(),
        overline = stringResource(Res.string.import_flow_eyebrow),
        content =
            step?.let {
                {
                    WizardStepTracker(
                        steps = steps,
                        currentStep = it,
                        accent = MaterialTheme.colorScheme.primary,
                        ink = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(end = 20.dp),
                    )
                }
            },
    )
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            content()
        }
    }
}

/** A pulsing scallop medallion holding a glyph — the signature Expressive focal point. */
@Composable
private fun ScallopMedallion(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: Dp,
    iconSize: Dp,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    ScallopBadge(size = size, containerColor = containerColor) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = contentColor,
        )
    }
}

// ─── Idle (intro) ───────────────────────────────────────────────────────────────

@Composable
private fun IdleContent(onChooseFile: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(Res.string.import_idle_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                HowItWorksStep(
                    icon = Icons.Outlined.FolderOpen,
                    text = stringResource(Res.string.import_choose_backup_file),
                )
                Spacer(Modifier.height(16.dp))
                HowItWorksStep(
                    icon = Icons.Outlined.Group,
                    text = stringResource(Res.string.import_review_users_section),
                )
                Spacer(Modifier.height(16.dp))
                HowItWorksStep(icon = Icons.Outlined.AutoAwesome, text = stringResource(Res.string.import_apply_import))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(Res.string.import_data_stays_on_server),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ListenUpButton(
            text = stringResource(Res.string.import_choose_backup_file),
            onClick = onChooseFile,
            leadingIcon = Icons.Outlined.FolderOpen,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HowItWorksStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ─── Uploading ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UploadingContent(state: ImportFlowUiState.Uploading) {
    ProgressMedallionContent(
        icon = Icons.Outlined.CloudUpload,
        subtitle = stringResource(Res.string.import_uploading_subtitle, state.filename),
    ) {
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

// ─── Analyzing ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnalyzingContent(state: ImportFlowUiState.Analyzing) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScallopMedallion(icon = Icons.Outlined.AutoAwesome, size = 64.dp, iconSize = 30.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                text = stringResource(Res.string.import_analyzing_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(34.dp))
        if (state.total > 0) {
            PercentCountRow(done = state.done, total = state.total)
            Spacer(Modifier.height(14.dp))
            LinearWavyProgressIndicator(
                progress = { state.done.toFloat() / state.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.currentItem?.let { item ->
            Spacer(Modifier.height(14.dp))
            DetailChip(label = stringResource(Res.string.import_matching_current_item, item))
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile(
                value = state.usersMatched.toString(),
                label = stringResource(Res.string.import_stat_users_merged),
                icon = Icons.Outlined.Group,
                colors = StatTileTone.primary(),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = state.booksMatched.toString(),
                label = stringResource(Res.string.import_review_books_section),
                icon = Icons.AutoMirrored.Filled.LibraryBooks,
                colors = StatTileTone.tertiary(),
                modifier = Modifier.weight(1f),
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "users_header") {
            val reviewCount =
                if (unresolvedUserCount > 0) {
                    stringResource(Res.string.import_to_review_count, unresolvedUserCount.toString())
                } else {
                    null
                }
            ReviewSectionHeader(
                label = stringResource(Res.string.import_users_in_backup),
                count = reviewCount,
            )
        }

        if (analysis.userMatches.isEmpty()) {
            item(key = "users_empty") { MutedLine(stringResource(Res.string.import_no_attention_needed)) }
        } else {
            items(analysis.userMatches, key = { "user_${it.absUserId.value}" }) { match ->
                UserMatchCard(
                    match = match,
                    assignedUserId = state.userMappings[match.absUserId],
                    isSkipped = state.skippedUsers.contains(match.absUserId),
                    listenupUsers = state.listenupUsers,
                    onAssign = { pickedUser -> onSetUserMapping(match.absUserId, UserId(pickedUser.id)) },
                    onSkip = { onSkipUser(match.absUserId) },
                )
            }
        }

        item(key = "books_header") {
            ReviewSectionHeader(label = stringResource(Res.string.import_review_books_section), count = null)
        }

        if (autoMatchedCount > 0) {
            item(key = "auto_summary") {
                MutedLine(
                    stringResource(Res.string.import_books_matched, autoMatchedCount.toString()) +
                        " — " + stringResource(Res.string.import_auto_matched),
                )
            }
        }

        if (analysis.importableSessionCount > 0) {
            item(key = "sessions_summary") {
                MutedLine(stringResource(Res.string.import_sessions_importable, analysis.importableSessionCount))
            }
        }

        if (attentionItems.isEmpty()) {
            item(key = "books_no_review") { MutedLine(stringResource(Res.string.import_no_books_to_review)) }
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

        item(key = "apply_warning") {
            if (unresolvedUserCount > 0) {
                Text(
                    text = stringResource(Res.string.import_review_users_unresolved_warning, unresolvedUserCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        item(key = "apply_button") {
            ListenUpButton(
                text = stringResource(Res.string.import_apply_import),
                onClick = onConfirm,
                trailingIcon = Icons.Outlined.AutoAwesome,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ReviewSectionHeader(
    label: String,
    count: String?,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    text = count,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
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
    onAssign: (AdminUserInfo) -> Unit,
    onSkip: () -> Unit,
) {
    val isResolved = assignedUserId != null || isSkipped
    val assignedUser = assignedUserId?.let { id -> listenupUsers.find { it.id == id.value } }
    val matched = assignedUserId != null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color =
            when {
                isSkipped -> MaterialTheme.colorScheme.surfaceContainerLowest
                matched -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
            },
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UserIdentityRow(
                match = match,
                matched = matched,
                statusText = userStatusText(matched, isSkipped),
                isResolved = isResolved,
            )
            if (matched) {
                AssignedToRow(label = assignedUser?.displayableName ?: assignedUserId.value)
            } else if (!isSkipped) {
                UnresolvedUserActions(
                    listenupUsers = listenupUsers,
                    onAssign = onAssign,
                    onSkip = onSkip,
                )
            }
        }
    }
}

@Composable
private fun userStatusText(
    matched: Boolean,
    isSkipped: Boolean,
): String =
    when {
        isSkipped -> stringResource(Res.string.import_user_skipped)
        matched -> stringResource(Res.string.import_book_assigned)
        else -> stringResource(Res.string.import_user_needs_review)
    }

@Composable
private fun UserIdentityRow(
    match: AbsUserMatch,
    matched: Boolean,
    statusText: String,
    isResolved: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ScallopBadge(
            size = 46.dp,
            containerColor =
                if (matched) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Text(
                text = match.absUsername.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color =
                    if (matched) {
                        MaterialTheme.colorScheme.onSecondary
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = match.absUsername,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            match.absEmail?.let { email ->
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StatusChip(text = statusText, resolved = isResolved)
    }
}

@Composable
private fun AssignedToRow(label: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Link,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(Res.string.import_user_assigned_to, label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun UnresolvedUserActions(
    listenupUsers: List<AdminUserInfo>,
    onAssign: (AdminUserInfo) -> Unit,
    onSkip: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        UserAssignDropdown(listenupUsers = listenupUsers, onAssign = onAssign, modifier = Modifier.weight(1f))
        ListenUpButton(
            text = stringResource(Res.string.import_user_skip),
            onClick = onSkip,
            leadingIcon = Icons.Outlined.Block,
            filled = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun UserAssignDropdown(
    listenupUsers: List<AdminUserInfo>,
    onAssign: (AdminUserInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ListenUpButton(
            text = stringResource(Res.string.import_user_assign),
            onClick = { dropdownExpanded = true },
            leadingIcon = Icons.Outlined.PersonAdd,
            filled = false,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
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

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color =
            when {
                isSkipped -> MaterialTheme.colorScheme.surfaceContainerLowest
                isAssigned -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
            },
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BookTitleRow(
                item = item,
                statusText =
                    when {
                        isAssigned -> stringResource(Res.string.import_book_assigned)
                        isSkipped -> stringResource(Res.string.import_user_skipped)
                        else -> tierLabel
                    },
                isResolved = isResolved,
            )
            if (bookSearch != null) {
                BookSearchPanel(
                    bookSearch = bookSearch,
                    onSearchQueryChange = onSearchQueryChange,
                    onCloseSearch = onCloseSearch,
                    onSelectBook = onSelectBook,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ListenUpButton(
                        text = stringResource(Res.string.import_search_and_assign),
                        onClick = onOpenSearch,
                        leadingIcon = Icons.Outlined.AutoAwesome,
                        modifier = Modifier.weight(1f),
                    )
                    ListenUpButton(
                        text = stringResource(Res.string.import_book_skip),
                        onClick = onSkip,
                        leadingIcon = Icons.Outlined.Block,
                        filled = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookTitleRow(
    item: AbsItemRef,
    statusText: String,
    isResolved: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
        Spacer(Modifier.width(8.dp))
        StatusChip(text = statusText, resolved = isResolved)
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
                shape = MaterialTheme.shapes.large,
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
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.import_book_search_cancel))
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

// ─── Applying ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ApplyingContent(state: ImportFlowUiState.Applying) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScallopMedallion(icon = Icons.Filled.Storage, size = 64.dp, iconSize = 30.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(Res.string.import_writing_history_title),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(Res.string.import_writing_history_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(34.dp))
        if (state.total > 0) {
            PercentCountRow(done = state.done, total = state.total)
            Spacer(Modifier.height(14.dp))
            LinearWavyProgressIndicator(
                progress = { state.done.toFloat() / state.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        MutedLine(stringResource(Res.string.import_items_written_label))

        state.currentItem?.let { item ->
            Spacer(Modifier.height(20.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(Res.string.import_currently_writing_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    DetailChip(label = stringResource(Res.string.import_writing_current_item, item))
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        StatTile(
            value = state.sessionsWritten.toString(),
            label = stringResource(Res.string.import_stat_sessions_written),
            icon = Icons.Filled.GraphicEq,
            colors = StatTileTone.primary(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))
        FooterNote(stringResource(Res.string.import_keep_app_open))
    }
}

// ─── Done (complete) ───────────────────────────────────────────────────────────

@Composable
private fun DoneContent(
    result: ImportResult,
    onFinish: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        ScallopBadge(size = 104.dp, containerColor = MaterialTheme.colorScheme.primary) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.import_all_done),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.import_flow_sessions_imported, result.sessionsImported.toString()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(26.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                value = result.importedCount.toString(),
                label = stringResource(Res.string.import_stat_records_imported),
                icon = Icons.Outlined.Inventory2,
                colors = StatTileTone.primary(),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = result.sessionsImported.toString(),
                label = stringResource(Res.string.import_stat_sessions_imported),
                icon = Icons.Filled.GraphicEq,
                colors = StatTileTone.tertiary(),
                modifier = Modifier.weight(1f),
            )
        }
        // Only surfaced when a mapped user genuinely has history for books that aren't in this
        // library — the honest "couldn't import" count. A clean import shows no scary skip box.
        if (result.booksNotInLibrary > 0) {
            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.SkipNext,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.import_skipped_books_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(Res.string.import_skipped_books_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = result.booksNotInLibrary.toString(),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        ListenUpButton(
            text = stringResource(Res.string.import_finish),
            onClick = onFinish,
            leadingIcon = Icons.Filled.Check,
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
        ScallopBadge(size = 104.dp, containerColor = MaterialTheme.colorScheme.errorContainer) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(Res.string.import_error_title),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            fontWeight = FontWeight.ExtraBold,
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

// ─── Shared bits ───────────────────────────────────────────────────────────────

/** Centred scallop medallion + subtitle + a wavy progress block; the upload/indeterminate phase. */
@Composable
private fun ProgressMedallionContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    subtitle: String,
    progress: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(10.dp))
        ScallopMedallion(icon = icon, size = 120.dp, iconSize = 54.dp)
        Spacer(Modifier.height(28.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(38.dp))
        progress()
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PercentCountRow(
    done: Int,
    total: Int,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            text = stringResource(Res.string.import_items_matched, done.toString(), total.toString()),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f),
        )
        val pct = if (total > 0) done * 100 / total else 0
        Text(
            text = stringResource(Res.string.common_percent, pct),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun StatusChip(
    text: String,
    resolved: Boolean,
) {
    val container =
        if (resolved) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer
    val content =
        if (resolved) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(shape = MaterialTheme.shapes.large, color = container) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = content,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DetailChip(label: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun MutedLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FooterNote(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
