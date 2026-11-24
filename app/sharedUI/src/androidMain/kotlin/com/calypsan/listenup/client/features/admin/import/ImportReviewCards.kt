package com.calypsan.listenup.client.features.admin.import

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.AbsItemRef
import com.calypsan.listenup.api.dto.imports.AbsUserMatch
import com.calypsan.listenup.api.dto.imports.MatchTier
import com.calypsan.listenup.client.design.components.ForwardEdits
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.presentation.admin.imports.BookSearchState
import com.calypsan.listenup.core.BookId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.import_book_assigned
import listenup.composeapp.generated.resources.import_book_search_cancel
import listenup.composeapp.generated.resources.import_book_search_hint
import listenup.composeapp.generated.resources.import_book_search_no_results
import listenup.composeapp.generated.resources.import_book_skip
import listenup.composeapp.generated.resources.import_search_and_assign
import listenup.composeapp.generated.resources.import_user_assign
import listenup.composeapp.generated.resources.import_user_assigned_to
import listenup.composeapp.generated.resources.import_user_needs_review
import listenup.composeapp.generated.resources.import_user_pick_user
import listenup.composeapp.generated.resources.import_user_skip
import listenup.composeapp.generated.resources.import_user_skipped
import org.jetbrains.compose.resources.stringResource

// ─── User match card ──────────────────────────────────────────────────────────

@Composable
internal fun UserMatchCard(
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
        Column(
            modifier = Modifier.padding(Spacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
            imageVector = ListenUpIcons.Link,
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
            leadingIcon = ListenUpIcons.Block,
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
            leadingIcon = ListenUpIcons.PersonAdd,
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
internal fun BookReviewCard(
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
        Column(
            modifier = Modifier.padding(Spacing.lg).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                        leadingIcon = ListenUpIcons.AutoAwesome,
                        modifier = Modifier.weight(1f),
                    )
                    ListenUpButton(
                        text = stringResource(Res.string.import_book_skip),
                        onClick = onSkip,
                        leadingIcon = ListenUpIcons.Block,
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
    val haptics = LocalHaptics.current
    // openBookSearch starts every item's search blank, so the panel owns the text from there.
    val query = remember(bookSearch.absItemId) { TextFieldState(bookSearch.query) }
    query.ForwardEdits(onSearchQueryChange)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ListenUpTextField(
                state = query,
                placeholder = stringResource(Res.string.import_book_search_hint),
                placeholderStyle = MaterialTheme.typography.bodySmall,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f),
                trailingContent = {
                    if (bookSearch.isSearching) {
                        ListenUpLoadingIndicator(size = 18.dp)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = {
                    haptics.press()
                    onCloseSearch()
                },
            ) {
                Icon(ListenUpIcons.Close, contentDescription = stringResource(Res.string.import_book_search_cancel))
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
