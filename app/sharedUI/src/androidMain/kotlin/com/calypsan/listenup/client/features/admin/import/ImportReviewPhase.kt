package com.calypsan.listenup.client.features.admin.import

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.MatchTier
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.import_apply_import
import listenup.composeapp.generated.resources.import_auto_matched
import listenup.composeapp.generated.resources.import_books_matched
import listenup.composeapp.generated.resources.import_no_attention_needed
import listenup.composeapp.generated.resources.import_no_books_to_review
import listenup.composeapp.generated.resources.import_review_books_section
import listenup.composeapp.generated.resources.import_review_users_unresolved_warning
import listenup.composeapp.generated.resources.import_sessions_importable
import listenup.composeapp.generated.resources.import_to_review_count
import listenup.composeapp.generated.resources.import_users_in_backup
import org.jetbrains.compose.resources.stringResource

private const val CONTENT_TYPE_SECTION_HEADER = "section-header"
private const val CONTENT_TYPE_MUTED_LINE = "muted-line"

@Composable
internal fun ReviewContent(
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
        item(key = "users_header", contentType = CONTENT_TYPE_SECTION_HEADER) {
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
            item(key = "users_empty", contentType = CONTENT_TYPE_MUTED_LINE) {
                MutedLine(stringResource(Res.string.import_no_attention_needed))
            }
        } else {
            items(
                items = analysis.userMatches,
                key = { "user_${it.absUserId.value}" },
                contentType = { "user-match" },
            ) { match ->
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

        item(key = "books_header", contentType = CONTENT_TYPE_SECTION_HEADER) {
            ReviewSectionHeader(label = stringResource(Res.string.import_review_books_section), count = null)
        }

        if (autoMatchedCount > 0) {
            item(key = "auto_summary", contentType = CONTENT_TYPE_MUTED_LINE) {
                MutedLine(
                    stringResource(Res.string.import_books_matched, autoMatchedCount.toString()) +
                        " — " + stringResource(Res.string.import_auto_matched),
                )
            }
        }

        if (analysis.importableSessionCount > 0) {
            item(key = "sessions_summary", contentType = CONTENT_TYPE_MUTED_LINE) {
                MutedLine(stringResource(Res.string.import_sessions_importable, analysis.importableSessionCount))
            }
        }

        if (attentionItems.isEmpty()) {
            item(key = "books_no_review", contentType = CONTENT_TYPE_MUTED_LINE) {
                MutedLine(stringResource(Res.string.import_no_books_to_review))
            }
        } else {
            items(
                items = attentionItems,
                key = { "book_${it.absItemId.value}" },
                contentType = { "book-review" },
            ) { item ->
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

        item(key = "apply_warning", contentType = "apply-warning") {
            if (unresolvedUserCount > 0) {
                Text(
                    text = stringResource(Res.string.import_review_users_unresolved_warning, unresolvedUserCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        item(key = "apply_button", contentType = "apply-button") {
            ListenUpButton(
                text = stringResource(Res.string.import_apply_import),
                onClick = onConfirm,
                trailingIcon = ListenUpIcons.AutoAwesome,
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
            modifier = Modifier.weight(1f).semantics { heading() },
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
