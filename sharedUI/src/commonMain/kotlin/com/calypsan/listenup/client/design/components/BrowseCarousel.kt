package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Horizontal browse carousel for the home/discover/detail rows.
 *
 * A snap-flinging [LazyRow]: each item is laid out at a uniform [itemWidth] with snap-to-item fling.
 * Unlike M3's `HorizontalUncontainedCarousel` (which mask-clips each item to a rounded carousel
 * shape — cropping a card's title, rounded cover corners, and drop shadow), a plain LazyRow imposes
 * no mask, so each card renders in full. Items keep their own look (BookCard's floating cover, a
 * shelf card, etc.); the carousel only provides layout + snap motion.
 *
 * @param items the row's items, rendered uniformly at [itemWidth].
 * @param itemWidth uniform item width — MUST match the rendered card's width so a self-sizing card
 *   is neither clipped (itemWidth too small) nor left-gapped (too large).
 * @param key stable item key (recommended for content rows so scroll/animation state survives reorder).
 * @param itemContent renders one item (e.g. a BookCard / ShelfCard).
 */
@Composable
fun <T> BrowseCarousel(
    items: List<T>,
    modifier: Modifier = Modifier,
    itemWidth: Dp = 140.dp,
    itemSpacing: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    key: ((item: T) -> Any)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        flingBehavior = rememberSnapFlingBehavior(listState),
    ) {
        items(items = items, key = key) { item ->
            Box(modifier = Modifier.width(itemWidth)) {
                itemContent(item)
            }
        }
    }
}
