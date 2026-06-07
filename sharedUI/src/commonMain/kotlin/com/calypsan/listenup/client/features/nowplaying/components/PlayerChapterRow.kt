package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_now_playing
import org.jetbrains.compose.resources.stringResource

/**
 * A single row in the Now Playing chapter list.
 *
 * Current-chapter rows are highlighted with [primaryContainer] background; all other rows
 * are transparent. Text and icon colours follow the M3 Expressive container-content mapping:
 * [onPrimaryContainer] for the active row, [onSurface]/[onSurfaceVariant] for inactive rows.
 *
 * @param number 1-based chapter number shown at the leading edge.
 * @param title Chapter title text, ellipsised when too long.
 * @param durationLabel Human-readable duration string (e.g. "25:04").
 * @param isCurrent Whether this chapter is the currently playing one.
 * @param onClick Called when the row is tapped.
 */
@Composable
fun PlayerChapterRow(
    number: Int,
    title: String,
    durationLabel: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val numberColor =
        if (isCurrent) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val titleColor =
        if (isCurrent) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val subtitleColor =
        if (isCurrent) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val trailingColor =
        if (isCurrent) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .drawBehind {
                    if (isCurrent) drawRect(containerColor)
                }.clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chapter number — fixed minimum width so titles align.
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = numberColor,
            modifier = Modifier.widthIn(min = 26.dp),
        )

        // Title + duration, title takes remaining width.
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = durationLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = subtitleColor,
        )

        Spacer(Modifier.size(8.dp))

        // Playing indicator or play arrow.
        Icon(
            imageVector = if (isCurrent) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
            contentDescription = if (isCurrent) stringResource(Res.string.player_now_playing) else null,
            modifier = Modifier.size(24.dp),
            tint = trailingColor,
        )
    }
}
