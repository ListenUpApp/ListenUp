package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Leading-icon column width: tile (44) + row gap (14) ≈ the divider's inset start. */
private val DIVIDER_INSET = 70.dp

/**
 * A flat row meant to live INSIDE a [SectionGroup] card — it owns no surface of its own, only the
 * row chrome: an optional leading [TonalIconTile], a [title] (+ optional [subtitle]) text column,
 * and an optional [trailing] control slot. The canonical row for accent-headed grouped sections —
 * Settings entries (selector pills, toggles, info values, navigation chevrons) and Admin rows
 * (users, actions) all compose this.
 *
 * When [showDivider] is set, an inset [HorizontalDivider] (start-inset ~70.dp, clearing the leading
 * tile) is drawn at the top so consecutive rows read as a divided list. When [danger] is set, the
 * leading tile switches to its error variant and the [title] renders in the error colour — the
 * destructive variant for sign-out-style rows. Supplying [onClick] makes the whole row clickable.
 *
 * @param title Primary label, [MaterialTheme.typography.titleMedium].
 * @param modifier Modifier for the row.
 * @param subtitle Optional secondary line, single-line ellipsised [onSurfaceVariant] body text.
 * @param icon Optional leading glyph; when null the row has no leading tile.
 * @param accent Accent colour for the leading tile.
 * @param danger When true, uses the error-tinted tile and an error-coloured title.
 * @param showDivider When true, draws an inset divider above the row.
 * @param onClick Optional tap handler; when set the whole row is clickable.
 * @param trailing Optional trailing control (pill, switch, chevron, value text).
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    danger: Boolean = false,
    showDivider: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val rowModifier =
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    Column(modifier = rowModifier) {
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = DIVIDER_INSET),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            icon?.let { TonalIconTile(icon = it, accent = accent, danger = danger) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        if (danger) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}
