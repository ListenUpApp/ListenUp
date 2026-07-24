package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val SUBTITLE_ALPHA = 0.85f
private val INDICATOR_SIZE = 24.dp

/**
 * A selectable tonal card for mutually-exclusive choices: a leading [TonalIconTile] paired with a
 * trailing radio/check indicator, above a bold [title] and muted [subtitle]. When [selected] the
 * card fills with [MaterialTheme.colorScheme.secondaryContainer], gains a [secondary] border, and the
 * trailing indicator becomes a filled check; unselected it sits on [surfaceContainerLow] with a
 * hollow outlined indicator. Used side-by-side for choices like the invite Member/Admin role.
 *
 * @param title Primary label, bold.
 * @param subtitle Secondary description line.
 * @param icon Glyph rendered in the leading tonal tile.
 * @param selected Whether this option is the current selection.
 * @param onClick Invoked when the card is tapped.
 * @param modifier Modifier for the card surface (e.g. `Modifier.weight(1f)` when laid out in a row).
 */
@Composable
fun SelectableOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val containerColor = if (selected) colors.secondaryContainer else colors.surfaceContainerLow
    val titleColor = if (selected) colors.onSecondaryContainer else colors.onSurface
    val subtitleColor =
        if (selected) colors.onSecondaryContainer.copy(alpha = SUBTITLE_ALPHA) else colors.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        border = if (selected) BorderStroke(2.dp, colors.secondary) else null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TonalIconTile(
                    icon = icon,
                    size = 42.dp,
                    accent = colors.secondary,
                )
                SelectionIndicator(selected = selected)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SelectionIndicator(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    if (selected) {
        Box(
            modifier = Modifier.size(INDICATOR_SIZE).clip(CircleShape).background(colors.secondary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = colors.onSecondary,
            )
        }
    } else {
        Surface(
            modifier = Modifier.size(INDICATOR_SIZE),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(2.dp, colors.outline),
            content = {},
        )
    }
}
