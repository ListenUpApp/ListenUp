package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The canonical small pill badge displaying a numeric count.
 *
 * Used in section headers to indicate the total number of items in a list (e.g. chapters,
 * readers) and, in its compact primary-coloured form, as the unread badge on the shell
 * notification bell.
 *
 * @param count The number to display inside the pill.
 * @param modifier Modifier for the badge.
 * @param containerColor Fill behind the count text.
 * @param contentColor Colour of the count text; pair it with [containerColor].
 * @param minSize Minimum width and height of the pill; the pill stretches for wider counts.
 * @param maxCount Counts above this render as "N+" so the badge never stretches unbounded.
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    minSize: Dp = 26.dp,
    maxCount: Int = Int.MAX_VALUE,
) {
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .background(containerColor)
                .defaultMinSize(minWidth = minSize, minHeight = minSize)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > maxCount) "$maxCount+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = contentColor,
        )
    }
}
