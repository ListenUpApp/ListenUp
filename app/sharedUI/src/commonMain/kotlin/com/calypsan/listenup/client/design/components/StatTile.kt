package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val LABEL_ALPHA = 0.72f

/**
 * The colour pairing that gives a [StatTile] its tone: a container fill, an on-container content
 * colour for the big value, and an icon accent. Build one with the [StatTileTone] factories so the
 * tones stay consistent across stat blocks.
 *
 * @param container Fill behind the whole tile.
 * @param content Colour for the large value figure (and, faded, the label).
 * @param icon Colour for the leading glyph.
 */
@Immutable
data class StatTileColors(
    val container: Color,
    val content: Color,
    val icon: Color,
)

/** Canonical [StatTileColors] tones drawn from the active [MaterialTheme.colorScheme]. */
object StatTileTone {
    /** Primary-container tone — the headline metric in a stat block. */
    @Composable
    fun primary(): StatTileColors =
        StatTileColors(
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = MaterialTheme.colorScheme.primary,
        )

    /** Tertiary-container tone — the secondary, contrasting metric. */
    @Composable
    fun tertiary(): StatTileColors =
        StatTileColors(
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            icon = MaterialTheme.colorScheme.tertiary,
        )

    /** Neutral surface tone — a muted, lower-emphasis metric. */
    @Composable
    fun neutral(): StatTileColors =
        StatTileColors(
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurfaceVariant,
        )
}

/**
 * The signature M3 Expressive tonal stat tile: a rounded ([MaterialTheme.shapes.large]) [Surface]
 * filled with a [StatTileColors] tone, leading with a small accent [icon] above a large emphasized
 * [value] figure and a muted [label]. Used side by side in a [androidx.compose.foundation.layout.Row]
 * (each with `Modifier.weight(1f)`) to summarise a couple of headline numbers — the ABS import
 * Apply and Complete screens pair "sessions written" with "users merged".
 *
 * @param value The headline figure, rendered large and bold.
 * @param label The supporting caption beneath the value.
 * @param icon The leading glyph, tinted with the tone's accent.
 * @param modifier Modifier for the tile surface (typically `Modifier.weight(1f)` in a row).
 * @param colors The tone driving the fill, value, and icon colours.
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    colors: StatTileColors = StatTileTone.neutral(),
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = colors.container,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.icon,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMediumEmphasized,
                fontWeight = FontWeight.ExtraBold,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.content.copy(alpha = LABEL_ALPHA),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
