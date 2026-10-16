package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.ShelfCover
import org.jetbrains.compose.web.dom.Div

/**
 * A shelf's 2×2 mosaic of member covers (spec §2.7; Discover's shelves reuse it). Missing tiles are quiet fills.
 * Hidden from assistive technology: the card's own text names the shelf.
 */
@Composable
fun CoverMosaic(covers: List<ShelfCover>) {
    Div(attrs = {
        classes("mosaic")
        attr("aria-hidden", "true")
    }) {
        repeat(MOSAIC_TILES) { index ->
            val cover = covers.getOrNull(index)
            if (cover == null) {
                Div(attrs = { classes("mosaic-tile", "is-empty") })
            } else {
                Cover(
                    title = "",
                    imageUrl = coverUrl(cover.bookId, cover.coverHash, width = MOSAIC_RUNG),
                    size = null,
                    radius = MOSAIC_RADIUS,
                    decorative = true,
                    attrs = { classes("mosaic-tile") },
                )
            }
        }
    }
}

private const val MOSAIC_TILES = 4
private const val MOSAIC_RUNG = 96
private const val MOSAIC_RADIUS = 4
