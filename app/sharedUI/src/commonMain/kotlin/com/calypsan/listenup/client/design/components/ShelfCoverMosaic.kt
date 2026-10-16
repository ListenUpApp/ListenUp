package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.ShelfCover

private const val MOSAIC_TILES = 4
private const val MOSAIC_COLUMNS = 2

/**
 * A shelf's 2×2 cover mosaic (spec §2.7, §2.8 item 5): up to four member covers in shelf order, and the
 * container tone where a shelf has fewer, or this device lacks the book. Decorative: the card around it
 * names the shelf.
 *
 * @param covers The shelf's cover tiles, in shelf order; only the first four are drawn.
 */
@Composable
fun ShelfCoverMosaic(
    covers: List<ShelfCover>,
    modifier: Modifier = Modifier,
) {
    val tiles = List(MOSAIC_TILES) { covers.getOrNull(it) }
    Column(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(6.dp)
                .clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tiles.chunked(MOSAIC_COLUMNS).forEach { pair ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pair.forEach { tile ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        if (tile == null) {
                            Spacer(Modifier.fillMaxSize().testTag("mosaic-empty"))
                        } else {
                            BookCoverImage(
                                bookId = tile.bookId,
                                coverPath = tile.coverPath,
                                contentDescription = null,
                                coverHash = tile.coverHash,
                                modifier = Modifier.fillMaxSize().testTag("mosaic-cover"),
                            )
                        }
                    }
                }
            }
        }
    }
}
