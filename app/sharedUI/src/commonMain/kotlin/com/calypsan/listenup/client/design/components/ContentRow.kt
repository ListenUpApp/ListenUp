package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A soft, clickable list-row tile: a rounded [containerColor] [Surface] wrapping a centred [Row].
 * The canonical row chrome for grouped result/entity lists (search hits, admin users, settings
 * entries) — feature screens supply the leading glyph, text column, and trailing affordance as
 * [content]; the tile owns the shape, fill, and padding.
 *
 * @param onClick Invoked when the row is tapped.
 * @param modifier Modifier for the row surface.
 * @param containerColor Fill behind the row content.
 * @param content Row contents, laid out start-to-end and vertically centred.
 */
@Composable
fun ContentRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
