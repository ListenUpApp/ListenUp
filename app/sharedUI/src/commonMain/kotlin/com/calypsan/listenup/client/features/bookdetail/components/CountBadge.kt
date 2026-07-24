package com.calypsan.listenup.client.features.bookdetail.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A small pill badge displaying a numeric count, coloured with [MaterialTheme.colorScheme.tertiaryContainer].
 *
 * Used in section headers to indicate the total number of items in a list (e.g. chapters, readers).
 *
 * @param count The number to display inside the pill.
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}
