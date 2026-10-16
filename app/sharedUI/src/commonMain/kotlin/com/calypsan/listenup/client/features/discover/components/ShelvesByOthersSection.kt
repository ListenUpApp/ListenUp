package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.stableColorForId
import com.calypsan.listenup.client.presentation.discover.DiscoverShelfUi
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUserShelves
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_no_shelves_to_discover_yet
import listenup.composeapp.generated.resources.discover_shelf_count
import listenup.composeapp.generated.resources.discover_shelves_count
import listenup.composeapp.generated.resources.discover_when_other_users_create_shelves
import listenup.composeapp.generated.resources.genre_book_count
import listenup.composeapp.generated.resources.genre_books_count
import org.jetbrains.compose.resources.stringResource

/**
 * Other people's public shelves, grouped under whoever made them: a spinner before the first load,
 * an empty state when nobody has shared a shelf, otherwise one block per owner.
 */
@Composable
internal fun ShelvesByOthersSection(
    state: DiscoverShelvesUiState,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state as? DiscoverShelvesUiState.Ready
    val users = ready?.users.orEmpty()
    when {
        state is DiscoverShelvesUiState.Loading && users.isEmpty() -> {
            Box(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                ListenUpLoadingIndicator()
            }
        }

        ready?.isEmpty == true -> {
            EmptyShelvesState(modifier)
        }

        else -> {
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap)) {
                users.forEach { userShelves ->
                    key(userShelves.user.id) {
                        UserShelvesSection(
                            userShelves = userShelves,
                            onShelfClick = onShelfClick,
                            onUserClick = onUserClick,
                        )
                    }
                }
            }
        }
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
            tint = MaterialTheme.colorScheme.outline,
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
                    .padding(horizontal = Spacing.screenMargin),
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
            contentPadding = PaddingValues(horizontal = Spacing.screenMargin),
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
                    .clip(MaterialTheme.shapes.small)
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
