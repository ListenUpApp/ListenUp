package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Horizontal browse carousel for the home/discover rows.
 *
 * Wraps M3 [HorizontalUncontainedCarousel]: uniform, full-size items with snap-to-item fling and an
 * edge peek — expressive motion without resizing/cropping (audiobook covers are square and must stay
 * legible, so Multi-Browse is deliberately not used). Items keep their own look (e.g. BookCard's
 * floating cover); the carousel only provides layout + motion.
 *
 * @param items the row's items, rendered uniformly at [itemWidth].
 * @param itemWidth uniform item width (match the card's width — 140.dp for BookCard rows).
 * @param itemContent renders one item (e.g. a BookCard / ShelfCard).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> BrowseCarousel(
    items: List<T>,
    modifier: Modifier = Modifier,
    itemWidth: Dp = 140.dp,
    itemSpacing: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    itemContent: @Composable (T) -> Unit,
) {
    val state = rememberCarouselState(itemCount = { items.size })
    HorizontalUncontainedCarousel(
        state = state,
        itemWidth = itemWidth,
        itemSpacing = itemSpacing,
        contentPadding = contentPadding,
        modifier = modifier,
    ) { index ->
        itemContent(items[index])
    }
}
