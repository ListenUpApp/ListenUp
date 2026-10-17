package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.ShelfCover

private const val MOSAIC_SIDE = 2

/**
 * A shelf's 2×2 mosaic of square member covers (spec §2.7, §2.8 item 5). Tiles without a cover — fewer than four
 * books, or art not on this device yet — are `surfaceContainerHighest` placeholders. Decorative: the card's text names
 * the shelf.
 *
 * @param covers Up to four cover tiles, in shelf order.
 * @param modifier Modifier from the parent; the mosaic is always square.
 * @param gap The space between tiles.
 */
@Composable
fun CoverMosaic(
    covers: List<ShelfCover>,
    modifier: Modifier = Modifier,
    gap: Dp = 4.dp,
) {
    Column(
        modifier.aspectRatio(1f).clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(MOSAIC_SIDE) { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(MOSAIC_SIDE) { column ->
                    val cover = covers.getOrNull(row * MOSAIC_SIDE + column)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        if (cover != null) {
                            BookCoverImage(
                                bookId = cover.bookId,
                                coverPath = cover.coverPath,
                                coverHash = cover.coverHash,
                                contentDescription = null,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
