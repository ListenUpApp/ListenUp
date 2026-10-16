package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.calypsan.listenup.client.design.theme.Spacing

/** How many cells of at least [minCell] fit across [available], [spacing] apart. Never fewer than one. */
internal fun adaptiveColumns(
    available: Dp,
    minCell: Dp,
    spacing: Dp,
): Int = maxOf(1, ((available + spacing) / (minCell + spacing)).toInt())

/**
 * A wrapping grid whose column count flows with the width, like `GridCells.Adaptive`, for content that
 * sits inside a scrolling column where a lazy grid cannot measure. Every item shows; nothing is hidden
 * in a row that ran out of room.
 */
@Composable
fun <T> AdaptiveCardGrid(
    items: List<T>,
    minCellWidth: Dp,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    spacing: Dp = Spacing.md,
    itemContent: @Composable (item: T, cellWidth: Dp) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = adaptiveColumns(maxWidth, minCellWidth, spacing)
        val cell = (maxWidth - spacing * (columns - 1)) / columns
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            maxItemsInEachRow = columns,
        ) {
            items.forEach { item -> key(key(item)) { itemContent(item, cell) } }
        }
    }
}
