package com.calypsan.listenup.client.features.admin.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailViewModel
import com.calypsan.listenup.client.presentation.admin.CollectionBookItem
import com.calypsan.listenup.client.presentation.admin.CollectionShareItem
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_add_member
import listenup.composeapp.generated.resources.admin_add_members_to_share_this
import listenup.composeapp.generated.resources.admin_administrator
import listenup.composeapp.generated.resources.admin_all_users_are_already_members
import listenup.composeapp.generated.resources.admin_books_can_be_added_from
import listenup.composeapp.generated.resources.admin_books_in_collection
import listenup.composeapp.generated.resources.admin_collection_details
import listenup.composeapp.generated.resources.admin_collection_name
import listenup.composeapp.generated.resources.admin_collection_updated
import listenup.composeapp.generated.resources.admin_confirm_remove_member
import listenup.composeapp.generated.resources.admin_in_this_collection
import listenup.composeapp.generated.resources.admin_no_books_in_this_collection
import listenup.composeapp.generated.resources.admin_no_users_available
import listenup.composeapp.generated.resources.admin_remove_book
import listenup.composeapp.generated.resources.admin_remove_member
import listenup.composeapp.generated.resources.admin_the_book_will_not_be
import listenup.composeapp.generated.resources.admin_the_display_name_for_this
import listenup.composeapp.generated.resources.admin_they_will_no_longer_have
import listenup.composeapp.generated.resources.common_add
import listenup.composeapp.generated.resources.common_loading_item
import listenup.composeapp.generated.resources.common_members
import listenup.composeapp.generated.resources.common_no_items
import listenup.composeapp.generated.resources.common_not_found
import listenup.composeapp.generated.resources.common_remove
import listenup.composeapp.generated.resources.common_save_changes
import org.jetbrains.compose.resources.stringResource

private const val HORIZONTAL_PADDING_DP = 16
private const val VERTICAL_PADDING_DP = 12
private const val CARD_PADDING_DP = 16
private const val SECTION_SPACING_DP = 24
private const val ICON_GAP_DP = 12
private const val TOP_GAP_DP = 8
private const val EMPTY_PANEL_PADDING_DP = 32
private const val EMPTY_ICON_SIZE_DP = 48
private const val ADD_ICON_SIZE_DP = 18
private const val FADED_ALPHA = 0.5f
private const val FAINT_ALPHA = 0.7f

/**
 * Admin screen for viewing and editing a single collection.
 *
 * Features:
 * - View and edit collection name
 * - List books in the collection (when available)
 * - Remove books from the collection
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminCollectionDetailScreen(
    viewModel: AdminCollectionDetailViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var bookToRemove by remember { mutableStateOf<CollectionBookItem?>(null) }
    var shareToRemove by remember { mutableStateOf<CollectionShareItem?>(null) }

    val ready = state as? AdminCollectionDetailUiState.Ready

    // Transient mutation-failure error in snackbar (only meaningful in Ready).
    val readyError = ready?.error
    LaunchedEffect(readyError) {
        readyError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val saveSuccessMessage = stringResource(Res.string.admin_collection_updated)
    val saveSuccess = ready?.saveSuccess == true
    LaunchedEffect(saveSuccess) {
        if (saveSuccess) {
            snackbarHostState.showSnackbar(saveSuccessMessage)
            viewModel.clearSaveSuccess()
        }
    }

    val topBarTitle =
        when (val s = state) {
            is AdminCollectionDetailUiState.Ready -> s.collection.name
            is AdminCollectionDetailUiState.Loading, is AdminCollectionDetailUiState.Error -> "Collection"
        }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(topBarTitle) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        AdminCollectionDetailBody(
            state = state,
            innerPadding = innerPadding,
            onNameChange = viewModel::updateName,
            onSaveClick = viewModel::saveName,
            onRemoveBookClick = { bookToRemove = it },
            onAddMemberClick = viewModel::showAddMemberSheet,
            onRemoveMemberClick = { shareToRemove = it },
        )
    }

    // Add member bottom sheet
    if (ready?.showAddMemberSheet == true) {
        AddMemberBottomSheet(
            sheetState = sheetState,
            isLoading = ready.isLoadingUsers,
            isSharing = ready.isSharing,
            users = ready.availableUsers,
            onDismiss = viewModel::hideAddMemberSheet,
            onUserSelected = viewModel::shareWithUser,
        )
    }

    // Remove book confirmation dialog
    bookToRemove?.let { book ->
        ListenUpDestructiveDialog(
            onDismissRequest = { bookToRemove = null },
            title = stringResource(Res.string.admin_remove_book),
            text =
                "Are you sure you want to remove \"${book.title}\" from this collection? " +
                    stringResource(Res.string.admin_the_book_will_not_be),
            confirmText = stringResource(Res.string.common_remove),
            onConfirm = {
                viewModel.removeBook(book.id)
                bookToRemove = null
            },
            onDismiss = { bookToRemove = null },
        )
    }

    // Remove member confirmation dialog
    shareToRemove?.let { share ->
        ListenUpDestructiveDialog(
            onDismissRequest = { shareToRemove = null },
            title = stringResource(Res.string.admin_remove_member),
            text =
                stringResource(Res.string.admin_confirm_remove_member) +
                    stringResource(Res.string.admin_they_will_no_longer_have),
            confirmText = stringResource(Res.string.common_remove),
            onConfirm = {
                viewModel.removeShare(share.id)
                shareToRemove = null
            },
            onDismiss = { shareToRemove = null },
        )
    }
}

@Composable
private fun AdminCollectionDetailBody(
    state: AdminCollectionDetailUiState,
    innerPadding: PaddingValues,
    onNameChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onRemoveBookClick: (CollectionBookItem) -> Unit,
    onAddMemberClick: () -> Unit,
    onRemoveMemberClick: (CollectionShareItem) -> Unit,
) {
    when (state) {
        is AdminCollectionDetailUiState.Loading -> {
            FullScreenLoadingIndicator()
        }

        is AdminCollectionDetailUiState.Error -> {
            ErrorContent(
                message = stringResource(Res.string.common_not_found, "Collection"),
                modifier = Modifier.padding(innerPadding),
            )
        }

        is AdminCollectionDetailUiState.Ready -> {
            AdminCollectionDetailReadyContent(
                state = state,
                onNameChange = onNameChange,
                onSaveClick = onSaveClick,
                onRemoveBookClick = onRemoveBookClick,
                onAddMemberClick = onAddMemberClick,
                onRemoveMemberClick = onRemoveMemberClick,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun AdminCollectionDetailReadyContent(
    state: AdminCollectionDetailUiState.Ready,
    onNameChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onRemoveBookClick: (CollectionBookItem) -> Unit,
    onAddMemberClick: () -> Unit,
    onRemoveMemberClick: (CollectionShareItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = HORIZONTAL_PADDING_DP.dp),
    ) {
        item { DetailsSectionHeader() }
        item {
            CollectionInfoCard(
                state = state,
                onNameChange = onNameChange,
                onSaveClick = onSaveClick,
            )
        }

        item { BooksSectionHeader() }
        if (state.books.isEmpty()) {
            item { EmptyBooksMessage() }
        } else {
            item {
                BooksCard(
                    books = state.books,
                    removingBookId = state.removingBookId,
                    onRemoveBookClick = onRemoveBookClick,
                )
            }
        }

        item { MembersSectionHeader(onAddMemberClick = onAddMemberClick) }
        if (state.shares.isEmpty()) {
            item { EmptyMembersMessage() }
        } else {
            item {
                MembersCard(
                    shares = state.shares,
                    removingShareId = state.removingShareId,
                    onRemoveMemberClick = onRemoveMemberClick,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(HORIZONTAL_PADDING_DP.dp)) }
    }
}

@Composable
private fun DetailsSectionHeader() {
    Text(
        text = stringResource(Res.string.admin_collection_details),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = TOP_GAP_DP.dp, bottom = TOP_GAP_DP.dp),
    )
}

@Composable
private fun CollectionInfoCard(
    state: AdminCollectionDetailUiState.Ready,
    onNameChange: (String) -> Unit,
    onSaveClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(CARD_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(CARD_PADDING_DP.dp),
        ) {
            ListenUpTextField(
                value = state.editedName,
                onValueChange = onNameChange,
                label = stringResource(Res.string.admin_collection_name),
                enabled = !state.isSaving,
                supportingText = stringResource(Res.string.admin_the_display_name_for_this),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onSaveClick,
                    enabled = !state.isSaving && state.isDirty,
                ) {
                    if (state.isSaving) {
                        ListenUpLoadingIndicatorSmall()
                    } else {
                        Text(stringResource(Res.string.common_save_changes))
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = FADED_ALPHA),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ICON_GAP_DP.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    val count = state.collection.bookCount
                    Text(
                        text = "$count book${if (count != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.admin_in_this_collection),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BooksSectionHeader() {
    Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))
    Text(
        text = stringResource(Res.string.admin_books_in_collection),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = TOP_GAP_DP.dp),
    )
}

@Composable
private fun BooksCard(
    books: List<CollectionBookItem>,
    removingBookId: String?,
    onRemoveBookClick: (CollectionBookItem) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column {
            books.forEachIndexed { index, book ->
                BookRow(
                    book = book,
                    isRemoving = removingBookId == book.id,
                    onRemoveClick = { onRemoveBookClick(book) },
                )
                if (index < books.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = FADED_ALPHA),
                    )
                }
            }
        }
    }
}

@Composable
private fun MembersSectionHeader(onAddMemberClick: () -> Unit) {
    Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.common_members),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = onAddMemberClick) {
            Icon(
                imageVector = Icons.Outlined.PersonAdd,
                contentDescription = null,
                modifier = Modifier.size(ADD_ICON_SIZE_DP.dp),
            )
            Text(
                text = stringResource(Res.string.common_add),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun MembersCard(
    shares: List<CollectionShareItem>,
    removingShareId: String?,
    onRemoveMemberClick: (CollectionShareItem) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column {
            shares.forEachIndexed { index, share ->
                MemberRow(
                    share = share,
                    isRemoving = removingShareId == share.id,
                    onRemoveClick = { onRemoveMemberClick(share) },
                )
                if (index < shares.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = FADED_ALPHA),
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberRow(
    share: CollectionShareItem,
    isRemoving: Boolean,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = HORIZONTAL_PADDING_DP.dp, vertical = VERTICAL_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ICON_GAP_DP.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = share.userName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (share.userEmail.isNotBlank()) {
                Text(
                    text = share.userEmail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isRemoving) {
            ListenUpLoadingIndicatorSmall()
        } else {
            IconButton(onClick = onRemoveClick) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(Res.string.common_remove),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EmptyMembersMessage(modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(EMPTY_PANEL_PADDING_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = FADED_ALPHA),
                modifier = Modifier.size(EMPTY_ICON_SIZE_DP.dp),
            )
            Spacer(modifier = Modifier.height(ICON_GAP_DP.dp))
            Text(
                text = stringResource(Res.string.common_no_items, "members"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.admin_add_members_to_share_this),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = FAINT_ALPHA),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemberBottomSheet(
    sheetState: SheetState,
    isLoading: Boolean,
    isSharing: Boolean,
    users: List<AdminUserInfo>,
    onDismiss: () -> Unit,
    onUserSelected: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = HORIZONTAL_PADDING_DP.dp),
        ) {
            Text(
                text = stringResource(Res.string.admin_add_member),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = HORIZONTAL_PADDING_DP.dp),
            )

            when {
                isLoading -> {
                    AddMemberLoadingPanel()
                }

                users.isEmpty() -> {
                    AddMemberEmptyPanel()
                }

                else -> {
                    AddMemberUserList(
                        users = users,
                        isSharing = isSharing,
                        onUserSelected = onUserSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddMemberLoadingPanel() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(EMPTY_PANEL_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ListenUpLoadingIndicator()
        Spacer(modifier = Modifier.height(HORIZONTAL_PADDING_DP.dp))
        Text(
            text = stringResource(Res.string.common_loading_item, "users"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddMemberEmptyPanel() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(EMPTY_PANEL_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = FADED_ALPHA),
            modifier = Modifier.size(EMPTY_ICON_SIZE_DP.dp),
        )
        Spacer(modifier = Modifier.height(ICON_GAP_DP.dp))
        Text(
            text = stringResource(Res.string.admin_no_users_available),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.admin_all_users_are_already_members),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = FAINT_ALPHA),
        )
    }
}

@Composable
private fun AddMemberUserList(
    users: List<AdminUserInfo>,
    isSharing: Boolean,
    onUserSelected: (String) -> Unit,
) {
    users.forEach { user ->
        ListItem(
            headlineContent = { Text(user.displayName ?: user.email) },
            supportingContent = {
                if (user.displayName != null) {
                    Text(user.email)
                } else if (user.isRoot) {
                    Text(stringResource(Res.string.admin_administrator))
                }
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                )
            },
            trailingContent = {
                if (isSharing) {
                    ListenUpLoadingIndicatorSmall()
                }
            },
            colors =
                ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            modifier =
                Modifier.clickable(enabled = !isSharing) {
                    onUserSelected(user.id)
                },
        )
    }
}

@Composable
private fun BookRow(
    book: CollectionBookItem,
    isRemoving: Boolean,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = HORIZONTAL_PADDING_DP.dp, vertical = VERTICAL_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ICON_GAP_DP.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Book,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.authorNames.isNotBlank()) {
                Text(
                    text = book.authorNames,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isRemoving) {
            ListenUpLoadingIndicatorSmall()
        } else {
            IconButton(onClick = onRemoveClick) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(Res.string.common_remove),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EmptyBooksMessage(modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(EMPTY_PANEL_PADDING_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Book,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = FADED_ALPHA),
                modifier = Modifier.size(EMPTY_ICON_SIZE_DP.dp),
            )
            Spacer(modifier = Modifier.height(ICON_GAP_DP.dp))
            Text(
                text = stringResource(Res.string.admin_no_books_in_this_collection),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.admin_books_can_be_added_from),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = FAINT_ALPHA),
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(EMPTY_PANEL_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
