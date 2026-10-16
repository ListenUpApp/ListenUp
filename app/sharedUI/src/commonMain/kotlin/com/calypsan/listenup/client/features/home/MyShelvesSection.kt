package com.calypsan.listenup.client.features.home

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.CoverMosaic
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.Shelf
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_my_shelves
import listenup.composeapp.generated.resources.home_new_shelf
import listenup.composeapp.generated.resources.home_private_shelf
import listenup.composeapp.generated.resources.home_shelf_books_count
import listenup.composeapp.generated.resources.home_shelf_books_count_duration
import listenup.composeapp.generated.resources.home_shelf_one_book
import listenup.composeapp.generated.resources.home_shelf_one_book_duration
import listenup.composeapp.generated.resources.home_shelves_invitation
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

private val ShelfTileWidth = 156.dp
private val ShelfRowMosaic = 88.dp
private val ShelfTileInset = 6.dp
private val RowMosaicGap = 3.dp
private val LockSize = 14.dp

/**
 * "My shelves" (spec §2.7): always shown, and New shelf always offered. There is no See all until the My shelves list
 * screen exists (open question 1: Home PR 4 adds it with its destination).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MyShelvesSection(
    shelves: List<Shelf>,
    width: HomeWidth,
    onOpenShelf: (String) -> Unit,
    onCreateShelf: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalMargin: Dp = Spacing.screenMargin,
) {
    Column(modifier.fillMaxWidth()) {
        SectionTitle(
            title = stringResource(Res.string.home_my_shelves),
            modifier = Modifier.padding(horizontal = horizontalMargin),
            trailing = {
                FilledTonalButton(onClick = onCreateShelf, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(Res.string.home_new_shelf))
                }
            },
        )
        Spacer(Modifier.height(Spacing.titleGap))
        when {
            shelves.isEmpty() -> {
                Text(
                    text = stringResource(Res.string.home_shelves_invitation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = horizontalMargin),
                )
            }

            width == HomeWidth.Compact -> {
                BrowseCarousel(
                    items = shelves,
                    itemWidth = ShelfTileWidth,
                    itemSpacing = Spacing.itemGap,
                    contentPadding = PaddingValues(horizontal = horizontalMargin),
                    key = { it.idString },
                ) { shelf -> ShelfTile(shelf, onOpen = { onOpenShelf(shelf.idString) }) }
            }

            else -> {
                ShelfRowGrid(shelves, onOpenShelf, Modifier.padding(horizontal = horizontalMargin))
            }
        }
    }
}

/** Two row cards per line on medium and expanded windows. */
@Composable
private fun ShelfRowGrid(
    shelves: List<Shelf>,
    onOpenShelf: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        shelves.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                pair.forEach { shelf ->
                    ShelfRowCard(shelf, onOpen = { onOpenShelf(shelf.idString) }, modifier = Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShelfTile(
    shelf: Shelf,
    onOpen: () -> Unit,
) {
    Column(
        Modifier.width(ShelfTileWidth).clip(MaterialTheme.shapes.large).clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(ShelfTileInset),
        ) {
            CoverMosaic(shelf.covers)
        }
        ShelfText(shelf)
    }
}

@Composable
private fun ShelfRowCard(
    shelf: Shelf,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverMosaic(shelf.covers, modifier = Modifier.size(ShelfRowMosaic), gap = RowMosaicGap)
            ShelfText(shelf)
        }
    }
}

/** The name (with the lock when private) over "6 books · 74h". */
@Composable
private fun ShelfText(shelf: Shelf) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = shelf.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (shelf.isPrivate) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = stringResource(Res.string.home_private_shelf),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(LockSize),
                )
            }
        }
        Text(
            text = shelfMeta(shelf),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "6 books · 74h", "1 book", "1 book · 31h". */
@Composable
private fun shelfMeta(shelf: Shelf): String {
    val length =
        shelf.totalDurationSeconds
            .takeIf { it > 0 }
            ?.let { DurationFormatter.hoursMinutesCompact(it.seconds) }
    return when {
        shelf.bookCount == 1 && length != null -> stringResource(Res.string.home_shelf_one_book_duration, length)
        shelf.bookCount == 1 -> stringResource(Res.string.home_shelf_one_book)
        length != null -> stringResource(Res.string.home_shelf_books_count_duration, shelf.bookCount, length)
        else -> stringResource(Res.string.home_shelf_books_count, shelf.bookCount)
    }
}
