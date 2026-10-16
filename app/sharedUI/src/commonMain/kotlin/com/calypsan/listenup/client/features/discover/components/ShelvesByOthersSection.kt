package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AdaptiveCardGrid
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.ShelfCoverMosaic
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.discover.DiscoverShelfUi
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUserShelves
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_book_count
import listenup.composeapp.generated.resources.common_books_count
import listenup.composeapp.generated.resources.discover_no_shelves_to_discover_yet
import listenup.composeapp.generated.resources.discover_shelf_count
import listenup.composeapp.generated.resources.discover_shelves_by_others
import listenup.composeapp.generated.resources.discover_shelves_count
import listenup.composeapp.generated.resources.discover_when_other_users_create_shelves
import org.jetbrains.compose.resources.stringResource

private val SHELF_CARD_WIDTH = 156.dp
private val SHELF_MIN_CELL = 120.dp
private const val OWNERS_PER_ROW = 2

/**
 * "Shelves by others": other people's public shelves, grouped under whoever made them. A spinner before
 * the first load and an empty state when nobody has shared a shelf (both PR 6's to redesign); otherwise
 * the heading, then one block per owner: a header naming them and their shelves as cover-mosaic cards.
 */
@Composable
internal fun ShelvesByOthersSection(
    state: DiscoverShelvesUiState,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
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
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                SectionTitle(
                    title = stringResource(Res.string.discover_shelves_by_others),
                    modifier = Modifier.padding(horizontal = Spacing.screenMargin),
                )
                if (wide) {
                    // Owners in pairs, each column's shelves a wrapping grid (board Discover-AndroidTablet).
                    users.chunked(OWNERS_PER_ROW).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                            verticalAlignment = Alignment.Top,
                        ) {
                            pair.forEach { userShelves ->
                                key(userShelves.user.id) {
                                    WideUserShelves(userShelves, onShelfClick, onUserClick, Modifier.weight(1f))
                                }
                            }
                            // An odd owner out keeps half the width, so their cards match their neighbours'.
                            if (pair.size < OWNERS_PER_ROW) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
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

/** One owner: a header that opens their profile, then their shelves as mosaic cards. */
@Composable
private fun UserShelvesSection(
    userShelves: DiscoverUserShelves,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ShelfOwnerHeader(
            userShelves = userShelves,
            onUserClick = onUserClick,
            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
        )
        BrowseCarousel(
            items = userShelves.shelves,
            itemWidth = SHELF_CARD_WIDTH,
            itemSpacing = Spacing.md,
            contentPadding = PaddingValues(horizontal = Spacing.screenMargin),
            key = { it.id },
        ) { shelf ->
            DiscoverShelfCard(shelf = shelf, width = SHELF_CARD_WIDTH, onClick = { onShelfClick(shelf.id) })
        }
    }
}

/** One owner in a wide layout's column: their header over a wrapping grid of their shelves. */
@Composable
private fun WideUserShelves(
    userShelves: DiscoverUserShelves,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ShelfOwnerHeader(userShelves = userShelves, onUserClick = onUserClick)
        AdaptiveCardGrid(
            items = userShelves.shelves,
            minCellWidth = SHELF_MIN_CELL,
            key = { it.id },
        ) { shelf, width ->
            DiscoverShelfCard(shelf = shelf, width = width, onClick = { onShelfClick(shelf.id) })
        }
    }
}

/** Whose shelves these are: avatar, name and how many, as one link to their profile. */
@Composable
private fun ShelfOwnerHeader(
    userShelves: DiscoverUserShelves,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val owner = userShelves.user
    val count = userShelves.shelves.size
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) { onUserClick(owner.id) }
                .semantics(mergeDescendants = true) {}
                .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        UserAvatar(
            userId = owner.id,
            size = AvatarSize.Small,
            fallbackName = owner.displayName,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Column {
            Text(
                text = owner.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    if (count == 1) {
                        stringResource(Res.string.discover_shelf_count, count)
                    } else {
                        stringResource(Res.string.discover_shelves_count, count)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A discoverable shelf: its cover mosaic, name and book count, as one link to the shelf. */
@Composable
internal fun DiscoverShelfCard(
    shelf: DiscoverShelfUi,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .width(width)
                .clip(MaterialTheme.shapes.large)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        ShelfCoverMosaic(covers = shelf.covers, modifier = Modifier.fillMaxWidth())
        Text(
            text = shelf.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text =
                if (shelf.bookCount == 1) {
                    stringResource(Res.string.common_book_count, shelf.bookCount)
                } else {
                    stringResource(Res.string.common_books_count, shelf.bookCount)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
