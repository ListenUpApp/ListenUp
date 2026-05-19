@file:Suppress("MagicNumber")

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
import androidx.compose.foundation.lazy.LazyRow
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
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ProfileAvatar
import com.calypsan.listenup.client.features.discover.components.ActivityFeedSection
import com.calypsan.listenup.client.features.discover.components.CurrentlyListeningSection
import com.calypsan.listenup.client.features.discover.components.DiscoverBooksSection
import com.calypsan.listenup.client.features.discover.components.DiscoverLeaderboardSection
import com.calypsan.listenup.client.features.discover.components.RecentlyAddedSection
import com.calypsan.listenup.client.presentation.discover.DiscoverShelfUi
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUserShelves
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_no_shelves_to_discover_yet
import listenup.composeapp.generated.resources.discover_when_other_users_create_shelves

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
    onShelfClick: (String) -> Unit,
    onBookClick: (String) -> Unit,
    onUserProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = koinViewModel(),
) {
    val shelvesState by viewModel.discoverShelvesState.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = shelvesState is DiscoverShelvesUiState.Loading,
        onRefresh = { viewModel.refresh() },
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // Discover content with leaderboard (always shows) and user shelves.
        // The Loading / Error / empty-Ready cases all render no shelf list below the
        // activity feed; only Ready-with-data surfaces user shelves.
        val shelvesReady = shelvesState as? DiscoverShelvesUiState.Ready
        DiscoverContent(
            isLoading = shelvesState is DiscoverShelvesUiState.Loading,
            users = shelvesReady?.users.orEmpty(),
            isEmpty = shelvesReady?.isEmpty ?: false,
            onShelfClick = onShelfClick,
            onBookClick = onBookClick,
            onUserProfileClick = onUserProfileClick,
        )
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
    onShelfClick: (String) -> Unit,
    onBookClick: (String) -> Unit,
    onUserProfileClick: (String) -> Unit,
) {
    val isWide =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

    LazyColumn(
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Discover Something New - random book discovery (top section)
        item {
            DiscoverBooksSection(
                onBookClick = onBookClick,
            )
        }

        // Recently Added - newest books in library
        item {
            RecentlyAddedSection(
                onBookClick = onBookClick,
            )
        }

        // What Others Are Listening To - social proof section
        item {
            CurrentlyListeningSection(
                onBookClick = onBookClick,
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
) {
    val avatarColor =
        remember(userShelves.user.avatarColor) {
            try {
                Color(
                    userShelves.user.avatarColor
                        .removePrefix("#")
                        .toLong(16) or 0xFF000000,
                )
            } catch (_: Exception) {
                Color(0xFF6B7280)
            }
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
            // Avatar - uses ProfileAvatar for offline-first avatar display
            ProfileAvatar(
                userId = userShelves.user.id,
                displayName = userShelves.user.displayName,
                avatarColor = userShelves.user.avatarColor,
                size = 40.dp,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = userShelves.user.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${userShelves.shelves.size} ${if (userShelves.shelves.size == 1) "shelf" else "shelves"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal scroll of shelves
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = userShelves.shelves,
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
            text = "${shelf.bookCount} ${if (shelf.bookCount == 1) "book" else "books"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
