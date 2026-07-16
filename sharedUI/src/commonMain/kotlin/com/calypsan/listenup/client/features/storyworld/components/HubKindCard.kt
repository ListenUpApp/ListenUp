package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AvatarStack
import com.calypsan.listenup.client.design.components.AvatarStackEntry
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.presentation.storyworld.KindGroup

private val ICON_TILE_SIZE = 44.dp
private val AVATAR_STACK_SIZE = 30.dp

/**
 * Grouped surface holding one row per Story World entity kind (character/location/item) — the
 * hub's "browse by kind" entry point. Each row shows the kind's icon, label, entity count, an
 * [AvatarStack] preview of its first few entities, and a chevron; tapping a row invokes
 * [onKindClick].
 */
@Composable
fun HubKindCard(
    kindGroups: List<KindGroup>,
    onKindClick: (KindGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            kindGroups.forEachIndexed { index, group ->
                HubKindRow(group = group, onClick = { onKindClick(group) })
                if (index != kindGroups.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 14.dp + ICON_TILE_SIZE + 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HubKindRow(
    group: KindGroup,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TonalIconTile(icon = group.kind.icon(), size = ICON_TILE_SIZE)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.kind.pluralLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = entryCountLabel(group.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AvatarStack(
            entries = group.preview.map { AvatarStackEntry(name = it.name, kind = it.kind, tintSeed = it.id) },
            size = AVATAR_STACK_SIZE,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
