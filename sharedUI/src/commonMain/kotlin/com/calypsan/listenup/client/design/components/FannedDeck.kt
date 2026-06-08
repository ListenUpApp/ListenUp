package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A single cover in a [FannedDeck]. Minimal descriptor so the design layer stays
 * decoupled from domain list models — call sites map their books into this.
 */
data class FannedDeckCover(
    val bookId: String,
    val coverPath: String?,
    val title: String,
    val author: String?,
)

/**
 * The signature M3 Expressive "fanned deck" of square book covers — the front cover is
 * full-size and the rest fan out to the trailing edge, each progressively smaller and offset.
 *
 * Covers render back-to-front so the leading cover sits on top. Each tile uses the square
 * [BookCoverImage] (with the gradient fallback on miss), so the deck always reads as covers
 * even before art downloads.
 *
 * Renders nothing when [covers] is empty. Its measured width is
 * `size + peek * (shownCount - 1)`; its height is [size].
 *
 * @param size edge length of the front (largest) cover
 * @param peek horizontal reveal between successive covers
 * @param max maximum number of covers to show
 */
@Composable
fun FannedDeck(
    covers: List<FannedDeckCover>,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    peek: Dp = 22.dp,
    max: Int = 4,
) {
    val shown = covers.take(max)
    if (shown.isEmpty()) return
    val count = shown.size
    val totalWidth = size + peek * (count - 1)

    Box(modifier = modifier.size(width = totalWidth, height = size)) {
        // Paint trailing (smallest) covers first so the leading cover lands on top.
        for (index in count - 1 downTo 0) {
            val cover = shown[index]
            val scale = 1f - index * SCALE_STEP
            val tile = size * scale
            val verticalInset = (size - tile) / 2
            Box(
                modifier =
                    Modifier
                        .offset(x = peek * index, y = verticalInset)
                        .size(tile)
                        .shadow(8.dp, RoundedCornerShape(CORNER))
                        .border(
                            2.dp,
                            androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(CORNER),
                        ).clip(RoundedCornerShape(CORNER)),
            ) {
                BookCoverImage(
                    bookId = cover.bookId,
                    coverPath = cover.coverPath,
                    contentDescription = cover.title,
                    title = cover.title,
                    author = cover.author,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(tile),
                )
            }
        }
    }
}

private val CORNER = 14.dp

/** Each successive cover shrinks by this fraction. */
private const val SCALE_STEP = 0.07f
