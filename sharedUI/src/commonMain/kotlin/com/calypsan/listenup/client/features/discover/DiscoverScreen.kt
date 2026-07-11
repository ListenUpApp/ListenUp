package com.calypsan.listenup.client.features.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.design.util.stableColorForId
import com.calypsan.listenup.client.features.discover.components.ActivityFeedSection
import com.calypsan.listenup.client.features.discover.components.CurrentlyListeningSection
import com.calypsan.listenup.client.features.discover.components.DiscoverBooksSection
import com.calypsan.listenup.client.features.discover.components.LiveCampfiresSection
import com.calypsan.listenup.client.features.discover.components.DiscoverLeaderboardSection
import com.calypsan.listenup.client.features.discover.components.RecentlyAddedSection
import com.calypsan.listenup.client.features.library.components.BookSelectionScaffold
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.books.SelectionMode
import com.calypsan.listenup.client.presentation.discover.DiscoverShelfUi
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUserShelves
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_no_shelves_to_discover_yet
import listenup.composeapp.generated.resources.discover_shelf_count
import listenup.composeapp.generated.resources.discover_shelves_count
import listenup.composeapp.generated.resources.discover_when_other_users_create_shelves
import listenup.composeapp.generated.resources.genre_book_count
import listenup.composeapp.generated.resources.genre_books_count

/**
 * Discover screen - browse shelves from other users and view community leaderboard.
 *
 * Features:
 * - Community leaderboard with gamified rankings
 * - Pull to refresh
 * - Users grouped with their shelves
 * - Click shelf to view details
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    appHeader: AppHeaderSlot,
    onShelfClick: (String) -> Unit,
    onBookClick: (String) -> Unit,
    onUserProfileClick: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = koinViewModel(),
    multiSelect: BookMultiSelectViewModel = koinViewModel(),
) {
    val shelvesState by viewModel.discoverShelvesState.collectAsStateWithLifecycle()
    val haptics = LocalHaptics.current
    val selectionMode by multiSelect.selectionMode.collectAsStateWithLifecycle()
    val isInSelectionMode = selectionMode is SelectionMode.Active
    val selectedBookIds = (selectionMode as? SelectionMode.Active)?.selectedIds.orEmpty()

    // Handle back press to exit selection mode.
    PlatformBackHandler(enabled = isInSelectionMode) {
        multiSelect.exitSelectionMode()
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        PullToRefreshBox(
            isRefreshing = shelvesState is DiscoverShelvesUiState.Loading,
            onRefresh = {
                haptics.thresholdActivate()
                viewModel.refresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            // Discover content with leaderboard (always shows) and user shelves.
            // The Loading / Error / empty-Ready cases all render no shelf list below the
            // activity feed; only Ready-with-data surfaces user shelves.
            val shelvesReady = shelvesState as? DiscoverShelvesUiState.Ready
            DiscoverContent(
                isLoading = shelvesState is DiscoverShelvesUiState.Loading,
                users = shelvesReady?.users.orEmpty(),
                isEmpty = shelvesReady?.isEmpty ?: false,
                appHeader = appHeader,
                contentPadding = contentPadding,
                onShelfClick = onShelfClick,
                onBookClick = { id ->
                    if (isInSelectionMode) multiSelect.toggleSelection(id) else onBookClick(id)
                },
                onUserProfileClick = onUserProfileClick,
                isInSelectionMode = isInSelectionMode,
                selectedBookIds = selectedBookIds,
                onBookLongPress = multiSelect::enterSelectionMode,
            )
        }

        // Multi-select overlay: top toolbar, picker sheets, and success feedback.
        BookSelectionScaffold(multiSelect = multiSelect)
    }
}

/**
 * Empty state when no shelves are discoverable from other users.
 *
 * This is shown below the leaderboard when there are no shared shelves.
 */
@Composable
private fun EmptyShelvesState(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Explore,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Text(
            text = stringResource(Res.string.discover_no_shelves_to_discover_yet),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(Res.string.discover_when_other_users_create_shelves),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Main content showing leaderboard and users with their shelves.
 *
 * The leaderboard always shows at the top. Below it:
 * - If loading initial data, show loading indicator
 * - If empty, show empty state message
 * - If has users, show their shelves
 */
@Composable
private fun DiscoverContent(
    isLoading: Boolean,
    users: List<DiscoverUserShelves>,
    isEmpty: Boolean,
    appHeader: AppHeaderSlot,
    contentPadding: PaddingValues,
    onShelfClick: (String) -> Unit,
    onBookClick: (String) -> Unit,
    onUserProfileClick: (String) -> Unit,
    isInSelectionMode: Boolean = false,
    selectedBookIds: Set<String> = emptySet(),
    onBookLongPress: ((String) -> Unit)? = null,
) {
    val isWide =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

    LazyColumn(
        // Shell system-bar/nav insets fold into the feed's own 16dp so content scrolls under the
        // bars and rests clear of them — no outer pad that would clip the first/last item.
        contentPadding =
            PaddingValues(
                top = contentPadding.calculateTopPadding() + 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Custom shell header scrolls with the feed (search/sync/avatar live here).
        item {
            appHeader {
                Text(
                    text = ShellDestination.Discover.title,
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Live now - open Campfire (co-listening) sessions. Renders nothing while empty.
        item {
            LiveCampfiresSection(onBookClick = onBookClick)
        }

        // Discover Something New - random book discovery (top section)
        item {
            DiscoverBooksSection(
                onBookClick = onBookClick,
                isInSelectionMode = isInSelectionMode,
                selectedBookIds = selectedBookIds,
                onBookLongPress = onBookLongPress,
            )
        }

        // Recently Added - newest books in library
        item {
            RecentlyAddedSection(
                onBookClick = onBookClick,
                isInSelectionMode = isInSelectionMode,
                selectedBookIds = selectedBookIds,
                onBookLongPress = onBookLongPress,
            )
        }

        // What Others Are Listening To - social proof section
        item {
            CurrentlyListeningSection(
                onBookClick = onBookClick,
                isInSelectionMode = isInSelectionMode,
                selectedBookIds = selectedBookIds,
                onBookLongPress = onBookLongPress,
            )
        }

        // Community leaderboard + Activity feed
        if (isWide) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DiscoverLeaderboardSection(
                        onUserClick = onUserProfileClick,
                        modifier = Modifier.weight(1f),
                    )
                    ActivityFeedSection(
                        onBookClick = onBookClick,
                        onShelfClick = onShelfClick,
                        onUserClick = onUserProfileClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            item {
                DiscoverLeaderboardSection(
                    onUserClick = onUserProfileClick,
                )
            }
            item {
                ActivityFeedSection(
                    onBookClick = onBookClick,
                    onShelfClick = onShelfClick,
                    onUserClick = onUserProfileClick,
                )
            }
        }

        // Content below activity feed depends on state
        when {
            isLoading && users.isEmpty() -> {
                item {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ListenUpLoadingIndicator()
                    }
                }
            }

            isEmpty -> {
                item {
                    EmptyShelvesState()
                }
            }

            else -> {
                // Users with shelves
                items(
                    items = users,
                    key = { it.user.id },
                ) { userShelves ->
                    UserShelvesSection(
                        userShelves = userShelves,
                        onShelfClick = onShelfClick,
                        onUserClick = onUserProfileClick,
                    )
                }
            }
        }
    }
}

/**
 * Section for a single user's shelves.
 */
@Composable
private fun UserShelvesSection(
    userShelves: DiscoverUserShelves,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
) {
    val avatarColor =
        remember(userShelves.user.id) {
            stableColorForId(userShelves.user.id)
        }

    Column {
        // User header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar - user header in a shelf list row
            UserAvatar(
                userId = userShelves.user.id,
                size = AvatarSize.Small,
                onClick = { onUserClick(userShelves.user.id) },
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = userShelves.user.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        if (userShelves.shelves.size == 1) {
                            stringResource(Res.string.discover_shelf_count, userShelves.shelves.size)
                        } else {
                            stringResource(Res.string.discover_shelves_count, userShelves.shelves.size)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal scroll of shelves
        BrowseCarousel(
            items = userShelves.shelves,
            itemWidth = 140.dp,
            itemSpacing = 12.dp,
            contentPadding = PaddingValues(horizontal = 16.dp),
            key = { it.id },
        ) { shelf ->
            DiscoverShelfCard(
                shelf = shelf,
                avatarColor = avatarColor,
                onClick = { onShelfClick(shelf.id) },
            )
        }
    }
}

/**
 * Card for a discoverable shelf.
 */
@Composable
private fun DiscoverShelfCard(
    shelf: DiscoverShelfUi,
    avatarColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .width(140.dp)
                .clickable(onClick = onClick),
    ) {
        // Shelf icon
        Box(
            modifier =
                Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(avatarColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = null,
                tint = avatarColor,
                modifier = Modifier.size(48.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Shelf name
        Text(
            text = shelf.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Book count
        Text(
            text =
                if (shelf.bookCount == 1) {
                    stringResource(Res.string.genre_book_count, shelf.bookCount)
                } else {
                    stringResource(Res.string.genre_books_count, shelf.bookCount)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
