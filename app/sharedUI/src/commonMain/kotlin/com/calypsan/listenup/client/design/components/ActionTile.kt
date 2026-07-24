package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val SUBTITLE_ALPHA = 0.78f
private const val CHEVRON_ALPHA = 0.7f
private val BADGE_SIZE = 50.dp

/**
 * A color-blocked navigation tile: a rounded ([MaterialTheme.shapes.large]) [Surface] filled
 * with [containerColor], leading with a [ScallopBadge] holding [icon], a [title] + muted [subtitle]
 * text column, and a trailing chevron. The canonical "big nav tile" for management/landing actions —
 * the Admin Management section composes one per destination, and any screen needing a vivid,
 * full-width call-to-action row can reuse it.
 *
 * @param title Primary label, [MaterialTheme.typography.titleMedium].
 * @param subtitle Secondary description line, [MaterialTheme.typography.bodyMedium], slightly muted.
 * @param icon Glyph rendered inside the leading scallop badge.
 * @param onClick Invoked when the tile is tapped.
 * @param modifier Modifier for the tile surface.
 * @param containerColor Fill behind the whole tile.
 * @param contentColor Colour for the title, subtitle, and chevron; defaults to the on-color for
 *   [containerColor].
 * @param badgeColor Fill behind the leading scallop badge.
 * @param badgeContentColor Tint for the [icon] inside the badge.
 */
@Composable
fun ActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = contentColorFor(containerColor),
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    badgeContentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScallopBadge(size = BADGE_SIZE, containerColor = badgeColor) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = badgeContentColor,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = SUBTITLE_ALPHA),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = contentColor.copy(alpha = CHEVRON_ALPHA),
            )
        }
    }
}
