package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val HEADER_TILE_SIZE = 30.dp

/**
 * The canonical accent-headed grouped-section container: an accent-tinted [TonalIconTile] paired
 * with an UPPERCASE overline [label] in [accent], floating above a [surfaceContainerLow] card
 * ([MaterialTheme.shapes.large]) whose body is the [content] [Column]. Used wherever the app
 * groups related rows under a coloured heading — Settings sections (Appearance, Playback, …) and
 * Admin sections (Server, Users, Management) all compose this with their own accent and rows.
 *
 * The card owns the shape and fill; rows supplied as [content] are flat (no own surface) and
 * typically [SettingRow]s separated by their own inset dividers.
 *
 * @param label Section heading, rendered uppercased and bold in [accent].
 * @param icon Glyph for the accent-tinted header tile.
 * @param modifier Modifier for the whole section column.
 * @param accent Accent colour driving the header tile tint and the label colour.
 * @param trailing Optional content rendered at the end of the header row (e.g. a count badge plus an
 *   action button); when null the header is just the tile and label.
 * @param content Section body rows, laid out in a [Column] inside the card.
 */
@Composable
fun SectionGroup(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TonalIconTile(icon = icon, size = HEADER_TILE_SIZE, accent = accent)
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = if (trailing != null) Modifier.weight(1f) else Modifier,
            )
            trailing?.invoke(this)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(content = content)
        }
    }
}
