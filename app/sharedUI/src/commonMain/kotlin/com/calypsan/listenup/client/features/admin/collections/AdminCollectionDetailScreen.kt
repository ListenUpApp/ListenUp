package com.calypsan.listenup.client.features.admin.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import com.calypsan.listenup.client.design.TwoPaneMinWidth
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.HeroNavRow
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpSearchField
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.CollectionBookItem
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailViewModel
import com.calypsan.listenup.client.presentation.admin.CollectionShareItem
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_add_books
import listenup.composeapp.generated.resources.admin_add_books_search_placeholder
import listenup.composeapp.generated.resources.admin_add_member
import listenup.composeapp.generated.resources.admin_add_members_to_share_this
import listenup.composeapp.generated.resources.admin_administrator
import listenup.composeapp.generated.resources.admin_all_users_are_already_members
import listenup.composeapp.generated.resources.admin_books_can_be_added_from
import listenup.composeapp.generated.resources.admin_books_in_collection
import listenup.composeapp.generated.resources.admin_collection_members_count
import listenup.composeapp.generated.resources.admin_collection_name
import listenup.composeapp.generated.resources.admin_collection_updated
import listenup.composeapp.generated.resources.admin_confirm_remove_member
import listenup.composeapp.generated.resources.admin_no_books_in_this_collection
import listenup.composeapp.generated.resources.admin_no_users_available
import listenup.composeapp.generated.resources.admin_remove_book
import listenup.composeapp.generated.resources.admin_remove_member
import listenup.composeapp.generated.resources.admin_system_collection_locked
import listenup.composeapp.generated.resources.admin_the_book_will_not_be
import listenup.composeapp.generated.resources.admin_the_display_name_for_this
import listenup.composeapp.generated.resources.admin_they_will_no_longer_have
import listenup.composeapp.generated.resources.common_add
import listenup.composeapp.generated.resources.common_book_count
import listenup.composeapp.generated.resources.common_books_count
import listenup.composeapp.generated.resources.common_loading_item
import listenup.composeapp.generated.resources.common_members
import listenup.composeapp.generated.resources.common_no_items
import listenup.composeapp.generated.resources.common_not_found
import listenup.composeapp.generated.resources.common_remove
import listenup.composeapp.generated.resources.common_save_changes
import listenup.composeapp.generated.resources.import_book_search_no_results
import listenup.composeapp.generated.resources.library_collection
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private const val HERO_BADGE_SIZE_DP = 64
private const val HERO_BADGE_ICON_RATIO = 0.5f
private const val HERO_BOTTOM_CORNER_DP = 40
private const val HERO_SEMI_TRANSPARENT = 0.18f
private const val COVER_GRID_MIN_TILE_DP = 160
private const val COVER_CORNER_DP = 12
private const val SECTION_SPACING_DP = 24
private const val EMPTY_PANEL_PADDING_DP = 32
private const val EMPTY_ICON_SIZE_DP = 48
private const val ICON_GAP_DP = 12
private const val FADED_ALPHA = 0.5f
private const val FAINT_ALPHA = 0.7f
private const val CHIP_PADDING_H_DP = 12
private const val CHIP_PADDING_V_DP = 6
private const val CHIP_SPACING_DP = 8
private const val CHIP_ICON_SIZE_DP = 16
private const val WIDE_SIDEBAR_WIDTH_DP = 360
private const val ADD_ICON_SIZE_DP = 18
private const val DELETE_TILE_SIZE_DP = 40

/**
 * Admin screen for viewing and editing a single collection, redesigned to the M3 Expressive mockup.
 *
 * Features a color-block hero with [HeroNavRow], collection name edit via [SectionGroup],
 * a grid of book covers for books in the collection (removable), and a members section.
 * Note: the hero delete action is intentionally absent — [AdminCollectionDetailViewModel]
 * exposes no deleteCollection action. Deletion is managed from the list screen.
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

    val isWide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(TwoPaneMinWidth.value.toInt())

    ListenUpScaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        DetailBody(
            state = state,
            isWide = isWide,
            innerPadding = innerPadding,
            onBackClick = onBackClick,
            onNameChange = viewModel::updateName,
            onSaveClick = viewModel::saveName,
            onRemoveBookClick = { bookToRemove = it },
            onAddBooksClick = viewModel::openAddBooks,
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

    // Add books search sheet
    (state as? AdminCollectionDetailUiState.Ready)?.takeIf { it.showAddBooks }?.let { ready ->
        AddBooksToCollectionSheet(
            query = ready.bookQuery,
            results = ready.bookResults,
            isSearching = ready.isSearchingBooks,
            onQueryChange = viewModel::onBookQueryChange,
            onBookSelected = viewModel::addBookFromSearch,
            onDismiss = viewModel::closeAddBooks,
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
                viewModel.revokeShare(share.userId)
                shareToRemove = null
            },
            onDismiss = { shareToRemove = null },
        )
    }
}

@Composable
private fun DetailBody(
    state: AdminCollectionDetailUiState,
    isWide: Boolean,
    innerPadding: PaddingValues,
    onBackClick: () -> Unit,
    onNameChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onRemoveBookClick: (CollectionBookItem) -> Unit,
    onAddBooksClick: () -> Unit,
    onAddMemberClick: () -> Unit,
    onRemoveMemberClick: (CollectionShareItem) -> Unit,
) {
    when (state) {
        is AdminCollectionDetailUiState.Loading -> {
            FullScreenLoadingIndicator()
        }

        is AdminCollectionDetailUiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(EMPTY_PANEL_PADDING_DP.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.common_not_found, "Collection"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is AdminCollectionDetailUiState.Ready -> {
            if (isWide) {
                WideDetailContent(
                    state = state,
                    innerPadding = innerPadding,
                    onBackClick = onBackClick,
                    onNameChange = onNameChange,
                    onSaveClick = onSaveClick,
                    onRemoveBookClick = onRemoveBookClick,
                    onAddBooksClick = onAddBooksClick,
                    onAddMemberClick = onAddMemberClick,
                    onRemoveMemberClick = onRemoveMemberClick,
                )
            } else {
                NarrowDetailContent(
                    state = state,
                    innerPadding = innerPadding,
                    onBackClick = onBackClick,
                    onNameChange = onNameChange,
                    onSaveClick = onSaveClick,
                    onRemoveBookClick = onRemoveBookClick,
                    onAddBooksClick = onAddBooksClick,
                    onAddMemberClick = onAddMemberClick,
                    onRemoveMemberClick = onRemoveMemberClick,
                )
            }
        }
    }
}

@Composable
private fun NarrowDetailContent(
    state: AdminCollectionDetailUiState.Ready,
    innerPadding: PaddingValues,
    onBackClick: () -> Unit,
    onNameChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onRemoveBookClick: (CollectionBookItem) -> Unit,
    onAddBooksClick: () -> Unit,
    onAddMemberClick: () -> Unit,
    onRemoveMemberClick: (CollectionShareItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding()),
    ) {
        item {
            DetailHero(state = state, onBackClick = onBackClick)
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = SECTION_SPACING_DP.dp)) {
                NameSection(state = state, onNameChange = onNameChange, onSaveClick = onSaveClick)
                Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))
                BooksSection(
                    state = state,
                    onRemoveBookClick = onRemoveBookClick,
                    onAddBooksClick = onAddBooksClick,
                )
                Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))
                MembersSection(
                    state = state,
                    onAddMemberClick = onAddMemberClick,
                    onRemoveMemberClick = onRemoveMemberClick,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun WideDetailContent(
    state: AdminCollectionDetailUiState.Ready,
    innerPadding: PaddingValues,
    onBackClick: () -> Unit,
    onNameChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onRemoveBookClick: (CollectionBookItem) -> Unit,
    onAddBooksClick: () -> Unit,
    onAddMemberClick: () -> Unit,
    onRemoveMemberClick: (CollectionShareItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        DetailHero(state = state, onBackClick = onBackClick)
        Row(
            modifier = Modifier.fillMaxSize().padding(20.dp).padding(bottom = innerPadding.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                BooksSection(
                    state = state,
                    onRemoveBookClick = onRemoveBookClick,
                    onAddBooksClick = onAddBooksClick,
                )
            }
            Column(
                modifier =
                    Modifier
                        .width(WIDE_SIDEBAR_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SECTION_SPACING_DP.dp),
            ) {
                NameSection(state = state, onNameChange = onNameChange, onSaveClick = onSaveClick)
                MembersSection(
                    state = state,
                    onAddMemberClick = onAddMemberClick,
                    onRemoveMemberClick = onRemoveMemberClick,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DetailHero(
    state: AdminCollectionDetailUiState.Ready,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(bottomStart = HERO_BOTTOM_CORNER_DP.dp, bottomEnd = HERO_BOTTOM_CORNER_DP.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            // Note: delete action is intentionally absent — AdminCollectionDetailViewModel
            // exposes no deleteCollection action. Deletion is managed from the list screen.
            HeroNavRow(
                onBack = onBackClick,
                buttonBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ScallopBadge(
                    size = HERO_BADGE_SIZE_DP.dp,
                    containerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = HERO_SEMI_TRANSPARENT),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderSpecial,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(HERO_BADGE_SIZE_DP.dp * HERO_BADGE_ICON_RATIO),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.library_collection),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        text = state.collection.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(CHIP_SPACING_DP.dp),
            ) {
                CountChip(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    count =
                        if (state.collection.bookCount == 1) {
                            stringResource(Res.string.common_book_count, state.collection.bookCount)
                        } else {
                            stringResource(Res.string.common_books_count, state.collection.bookCount)
                        },
                )
                CountChip(
                    icon = Icons.Outlined.Group,
                    count = stringResource(Res.string.admin_collection_members_count, state.shares.size),
                )
            }
        }
    }
}

@Composable
private fun CountChip(
    icon: ImageVector,
    count: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = CHIP_PADDING_H_DP.dp, vertical = CHIP_PADDING_V_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(CHIP_ICON_SIZE_DP.dp),
            )
            Text(
                text = count,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun NameSection(
    state: AdminCollectionDetailUiState.Ready,
    onNameChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionGroup(
        label = stringResource(Res.string.admin_collection_name),
        icon = Icons.Outlined.Edit,
        accent = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListenUpTextField(
                value = state.editedName,
                onValueChange = onNameChange,
                label = stringResource(Res.string.admin_collection_name),
                enabled = !state.isSaving && !state.collection.isSystem,
                supportingText =
                    if (state.collection.isSystem) {
                        stringResource(Res.string.admin_system_collection_locked)
                    } else {
                        stringResource(Res.string.admin_the_display_name_for_this)
                    },
            )
            if (state.isDirty && !state.collection.isSystem) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onSaveClick, enabled = !state.isSaving) {
                        if (state.isSaving) {
                            ListenUpLoadingIndicatorSmall()
                        } else {
                            Text(stringResource(Res.string.common_save_changes))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BooksSection(
    state: AdminCollectionDetailUiState.Ready,
    onRemoveBookClick: (CollectionBookItem) -> Unit,
    onAddBooksClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionGroup(
        label = stringResource(Res.string.admin_books_in_collection),
        icon = Icons.AutoMirrored.Outlined.MenuBook,
        accent = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
        trailing =
            if (!state.collection.isSystem) {
                {
                    TextButton(onClick = onAddBooksClick) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(ADD_ICON_SIZE_DP.dp),
                        )
                        Text(
                            text = stringResource(Res.string.admin_add_books),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            } else {
                null
            },
    ) {
        if (state.books.isEmpty()) {
            EmptyBooksPanel()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = COVER_GRID_MIN_TILE_DP.dp),
                // Nested in a scrollable parent, so the grid needs a bounded viewport: reserve
                // ~4 rows of adaptive (square) tiles, capped, and let it scroll internally.
                modifier =
                    Modifier
                        .height(((COVER_GRID_MIN_TILE_DP.dp + 8.dp) * 4).coerceAtMost(480.dp))
                        .fillMaxWidth(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = state.books, key = { it.id }) { book ->
                    BookCoverTile(
                        book = book,
                        isRemoving = state.removingBookId == book.id,
                        onRemoveClick = { onRemoveBookClick(book) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyBooksPanel(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(EMPTY_PANEL_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
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

@Composable
private fun BookCoverTile(
    book: CollectionBookItem,
    isRemoving: Boolean,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        // Fill the adaptive grid slot and stay square, matching the canonical BookCard cover.
        // A fixed .size() here fought the slot's width and rendered landscape.
        modifier = modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onRemoveClick),
        contentAlignment = Alignment.Center,
    ) {
        BookCoverImage(
            bookId = book.id,
            coverPath = book.coverPath,
            coverHash = book.coverHash,
            contentDescription = book.title,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(COVER_CORNER_DP.dp)),
        )
        if (isRemoving) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                shape = RoundedCornerShape(COVER_CORNER_DP.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ListenUpLoadingIndicatorSmall()
                }
            }
        }
    }
}

@Composable
private fun MembersSection(
    state: AdminCollectionDetailUiState.Ready,
    onAddMemberClick: () -> Unit,
    onRemoveMemberClick: (CollectionShareItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionGroup(
        label = stringResource(Res.string.common_members),
        icon = Icons.Outlined.Group,
        accent = MaterialTheme.colorScheme.secondary,
        modifier = modifier,
        trailing = {
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
        },
    ) {
        if (state.shares.isEmpty()) {
            EmptyMembersPanel()
        } else {
            state.shares.forEachIndexed { index, share ->
                MemberRow(
                    share = share,
                    isRemoving = state.removingShareUserId == share.userId,
                    showDivider = index > 0,
                    onRemoveClick = { onRemoveMemberClick(share) },
                )
            }
        }
    }
}

@Composable
private fun EmptyMembersPanel(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(EMPTY_PANEL_PADDING_DP.dp),
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

@Composable
private fun MemberRow(
    share: CollectionShareItem,
    isRemoving: Boolean,
    showDivider: Boolean,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolve the member's display name from the public-profile mirror, exactly as the avatar
    // does — the member model carries only the userId. Fall back to the userId so the row is
    // never stranded before the profile has synced.
    val profileRepository: UserProfileRepository = koinInject()
    val profile by profileRepository
        .observeProfile(share.userId)
        .collectAsStateWithLifecycle(initialValue = null)

    SettingRow(
        title = profile?.displayName?.ifBlank { null } ?: share.userId,
        subtitle = share.permission,
        showDivider = showDivider,
        modifier = modifier,
        leading = {
            UserAvatar(userId = share.userId, size = AvatarSize.Medium)
        },
        trailing = {
            if (isRemoving) {
                ListenUpLoadingIndicatorSmall()
            } else {
                TonalIconTile(
                    icon = Icons.Outlined.Delete,
                    size = DELETE_TILE_SIZE_DP.dp,
                    danger = true,
                    modifier = Modifier.clickable(onClick = onRemoveClick),
                )
            }
        },
    )
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
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(Res.string.admin_add_member),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            when {
                isLoading -> AddMemberLoadingPanel()
                users.isEmpty() -> AddMemberEmptyPanel()
                else -> AddMemberUserList(users = users, isSharing = isSharing, onUserSelected = onUserSelected)
            }
        }
    }
}

@Composable
private fun AddMemberLoadingPanel() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(EMPTY_PANEL_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ListenUpLoadingIndicator()
        Spacer(modifier = Modifier.height(16.dp))
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
        modifier = Modifier.fillMaxWidth().padding(EMPTY_PANEL_PADDING_DP.dp),
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
                Icon(imageVector = Icons.Outlined.Person, contentDescription = null)
            },
            trailingContent = {
                if (isSharing) ListenUpLoadingIndicatorSmall()
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickable(enabled = !isSharing) { onUserSelected(user.id) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBooksToCollectionSheet(
    query: String,
    results: List<SearchHit>,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onBookSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(Res.string.admin_add_books),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            ListenUpSearchField(
                value = query,
                onValueChange = onQueryChange,
                onSubmit = {},
                placeholder = stringResource(Res.string.admin_add_books_search_placeholder),
                isLoading = isSearching,
                onClear = { onQueryChange("") },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (query.isNotBlank() && results.isEmpty() && !isSearching) {
                Text(
                    text = stringResource(Res.string.import_book_search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(EMPTY_PANEL_PADDING_DP.dp),
                )
            }
            LazyColumn {
                items(items = results, key = { it.id }) { hit ->
                    ListItem(
                        headlineContent = { Text(hit.name) },
                        supportingContent = hit.author?.let { author -> { Text(author) } },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = stringResource(Res.string.admin_add_books),
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.clickable { onBookSelected(hit.id) },
                    )
                }
            }
        }
    }
}
