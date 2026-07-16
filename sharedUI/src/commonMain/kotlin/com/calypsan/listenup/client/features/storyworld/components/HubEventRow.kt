package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.design.components.AnchorChip
import com.calypsan.listenup.client.presentation.storyworld.EventRow

private val TILE_SIZE = 36.dp
private const val TILE_TINT_ALPHA = 0.18f
private val TILE_ICON_RATIO = 0.52f

/**
 * A single "Latest events" row on the Story World hub: a type-tinted icon tile, the entry's
 * mention-resolved text, and a muted [AnchorChip] showing where in the book it sits.
 */
@Composable
fun HubEventRow(
    events: List<EventRow>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            events.forEachIndexed { index, event ->
                HubEventItem(event = event)
                if (index != events.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 14.dp + TILE_SIZE + 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HubEventItem(event: EventRow) {
    val (icon, tint) = event.type.iconAndTint()
    Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(TILE_SIZE)
                    .clip(RoundedCornerShape(TILE_SIZE / 3))
                    .background(
                        tint.copy(alpha = TILE_TINT_ALPHA).compositeOver(MaterialTheme.colorScheme.surfaceContainerLow),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(TILE_SIZE * TILE_ICON_RATIO),
            )
        }
        Text(
            text = event.renderedText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AnchorChip(label = anchorLabelText(event.anchor), muted = true)
    }
}

/**
 * Decorative icon + tint per [WorldEventType] — reserved (not-yet-reduced) types fall back to the
 * NOTE treatment. Internal (not private): also drives the composer's [AssertionChip].
 */
internal fun WorldEventType.iconAndTint(): Pair<ImageVector, Color> =
    when (this) {
        WorldEventType.ENTERS_SCENE -> Icons.AutoMirrored.Outlined.Login to Color(0xFF1F8A5B)

        WorldEventType.EXITS_SCENE -> Icons.AutoMirrored.Outlined.Logout to Color(0xFFC0562F)

        WorldEventType.MOVES_TO -> Icons.AutoMirrored.Outlined.ArrowForward to Color(0xFF2E8BFF)

        WorldEventType.DEPARTS -> Icons.Outlined.ArrowOutward to Color(0xFF5B7A8E)

        WorldEventType.NOTE,
        WorldEventType.ALIAS,
        WorldEventType.BORN,
        WorldEventType.DIES,
        WorldEventType.ITEM_TRANSFER,
        WorldEventType.RELATIONSHIP_CHANGE,
        -> Icons.AutoMirrored.Outlined.Notes to Color(0xFFC98A2E)
    }
