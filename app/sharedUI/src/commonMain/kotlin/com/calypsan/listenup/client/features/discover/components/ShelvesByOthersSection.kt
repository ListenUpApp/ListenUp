package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
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
import com.calypsan.listenup.client.design.components.InlineRetry
import com.calypsan.listenup.client.design.components.RailSkeleton
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
import listenup.composeapp.generated.resources.discover_error_shelves
import listenup.composeapp.generated.resources.discover_shelf_count
import listenup.composeapp.generated.resources.discover_shelves_by_others
import listenup.composeapp.generated.resources.discover_shelves_count
import listenup.composeapp.generated.resources.discover_shelves_need_connection
import listenup.composeapp.generated.resources.discover_shelves_only_you
import org.jetbrains.compose.resources.stringResource

private val SHELF_CARD_WIDTH = 156.dp
private val SHELF_MIN_CELL = 120.dp
private const val OWNERS_PER_ROW = 2

/**
 * "Shelves by others": other people's public shelves, grouped under whoever made them: the heading, then
 * one block per owner, a header naming them and their shelves as cover-mosaic cards. The heading stays in
 * every state (spec §3.8): a skeleton while loading, "need a connection" (offline) or "Couldn't load" with
 * Try again on failure, and one line when nobody has made a shelf public.
 */
@Composable
internal fun ShelvesByOthersSection(
    state: DiscoverShelvesUiState,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val users = (state as? DiscoverShelvesUiState.Ready)?.users.orEmpty()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        SectionTitle(
            title = stringResource(Res.string.discover_shelves_by_others),
            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
        )
        when (state) {
            DiscoverShelvesUiState.Loading -> {
                RailSkeleton(
                    cardWidth = if (wide) null else SHELF_CARD_WIDTH,
                    captionLines = 2,
                    modifier = Modifier.padding(horizontal = Spacing.screenMargin).testTag("shelves-skeleton"),
                )
            }

            is DiscoverShelvesUiState.Error -> {
                InlineRetry(
                    line =
                        stringResource(
                            if (state.needsConnection) {
                                Res.string.discover_shelves_need_connection
                            } else {
                                Res.string.discover_error_shelves
                            },
                        ),
                    onRetry = onRetry,
                    modifier = Modifier.padding(horizontal = Spacing.screenMargin),
                )
            }

            is DiscoverShelvesUiState.Ready -> {
                if (state.isEmpty) {
                    Text(
                        text = stringResource(Res.string.discover_shelves_only_you),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.screenMargin),
                    )
                } else {
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
                                        WideUserShelves(
                                            userShelves = userShelves,
                                            onShelfClick = onShelfClick,
                                            onUserClick = onUserClick,
                                            modifier = Modifier.weight(1f),
                                        )
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

/**
 * A discoverable shelf: its cover mosaic, name and book count, as one link to the shelf.
 *
 * Only the press ripple is clipped to the card's rounded shape. Clipping the card itself let the large
 * corner's arc cut the leading digit off the book count, which sits flush in the bottom-left corner.
 */
@Composable
internal fun DiscoverShelfCard(
    shelf: DiscoverShelfUi,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .width(width)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ),
    ) {
        DiscoverShelfCardContent(shelf)
        Box(
            Modifier
                .matchParentSize()
                .clip(MaterialTheme.shapes.large)
                .indication(interactionSource, ripple()),
        )
    }
}

@Composable
private fun DiscoverShelfCardContent(shelf: DiscoverShelfUi) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
