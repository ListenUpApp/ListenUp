package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.ShelfCover
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img

/**
 * A shelf's 2×2 cover mosaic (spec §2.7): up to four member covers in shelf order, and the container
 * tone where a shelf has fewer, or this device lacks the book. Decorative (`aria-hidden`): the card
 * around it names the shelf. Its size comes from CSS (`--mosaic-size`).
 */
@Composable
fun ShelfMosaic(covers: List<ShelfCover>) {
    Div(attrs = {
        classes("mosaic")
        attr("aria-hidden", "true")
    }) {
        repeat(MOSAIC_TILES) { index ->
            val cover = covers.getOrNull(index)
            val url = cover?.let { coverUrl(it.bookId, it.coverHash, width = MOSAIC_TILE_PX) }
            Div(attrs = {
                classes("mosaic-tile")
                if (url == null) classes("is-empty")
            }) {
                url?.let { Img(src = it, alt = "", attrs = { attr("loading", "lazy") }) }
            }
        }
    }
}

private const val MOSAIC_TILES = 4

/** A tile's request width: half the largest mosaic, at 2× density. */
private const val MOSAIC_TILE_PX = 72
