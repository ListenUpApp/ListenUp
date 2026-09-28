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
import com.calypsan.listenup.client.design.theme.CategoryTone

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
 * @param contentDescription What the glyph means, when it carries meaning on its own — e.g. the
 *   label of an [androidx.compose.material3.IconButton] wrapping the tile. Null for a decorative
 *   leading tile whose row already names it.
 */
@Composable
fun TonalIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    accent: Color = MaterialTheme.colorScheme.primary,
    danger: Boolean = false,
    contentDescription: String? = null,
) {
    IconTile(
        icon = icon,
        containerColor =
            if (danger) MaterialTheme.colorScheme.errorContainer else accent.copy(alpha = ACCENT_TILE_ALPHA),
        contentColor = if (danger) MaterialTheme.colorScheme.error else accent,
        modifier = modifier,
        size = size,
        contentDescription = contentDescription,
    )
}

/**
 * The [TonalIconTile] for a categorical colour — a device type, say — drawn with the category's
 * composed [tone] for the current theme rather than a translucent accent, so the glyph's contrast
 * against its fill is fixed and known in both light and dark.
 *
 * @param icon The glyph to render, centred and tinted with [CategoryTone.content].
 * @param tone The category tone, typically [com.calypsan.listenup.client.design.theme.CategoryColor.current].
 * @param modifier Modifier for the tile.
 * @param size Edge length of the square tile.
 * @param contentDescription What the glyph means when it carries meaning on its own; null when decorative.
 */
@Composable
fun TonalIconTile(
    icon: ImageVector,
    tone: CategoryTone,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    contentDescription: String? = null,
) {
    IconTile(
        icon = icon,
        containerColor = tone.container,
        contentColor = tone.content,
        modifier = modifier,
        size = size,
        contentDescription = contentDescription,
    )
}

@Composable
private fun IconTile(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier,
    size: Dp,
    contentDescription: String?,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(MaterialTheme.shapes.medium)
                .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(size * TILE_ICON_RATIO),
            tint = contentColor,
        )
    }
}
