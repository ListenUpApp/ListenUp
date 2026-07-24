package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val ACCENT_TILE_ALPHA = 0.14f
private const val TILE_ICON_RATIO = 0.5f

/**
 * The canonical accent-tinted leading-icon tile: a rounded ([MaterialTheme.shapes.medium]) square
 * whose background is [accent] at a soft alpha with the [icon] tinted to the full [accent]. Used as
 * the leading glyph for grouped setting rows (and, later, admin rows) so every section carries its
 * accent colour into the row. When [danger] is set it switches to an error-container fill with an
 * error-tinted icon — the destructive variant for sign-out-style rows.
 *
 * @param icon The glyph to render, centred and tinted.
 * @param modifier Modifier for the tile.
 * @param size Edge length of the square tile.
 * @param accent Accent colour driving both the tinted fill and the icon tint.
 * @param danger When true, uses an error-container fill and error-tinted icon instead of [accent].
 */
@Composable
fun TonalIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    accent: Color = MaterialTheme.colorScheme.primary,
    danger: Boolean = false,
) {
    val background =
        if (danger) MaterialTheme.colorScheme.errorContainer else accent.copy(alpha = ACCENT_TILE_ALPHA)
    val tint = if (danger) MaterialTheme.colorScheme.error else accent
    Box(
        modifier =
            modifier
                .size(size)
                .clip(MaterialTheme.shapes.medium)
                .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(size * TILE_ICON_RATIO),
            tint = tint,
        )
    }
}
