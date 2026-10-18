package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_offline_pill
import org.jetbrains.compose.resources.stringResource

private val PillHeight = 32.dp
private val PillIcon = 18.dp

/**
 * The quiet offline pill under the greeting (spec §3.1): the device or the server is out of reach, and downloaded books
 * still play. Informational only — the dimmed cards below are what it explains. Announced politely (§8.2).
 */
@Composable
internal fun OfflinePill(modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = PillHeight).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(PillIcon),
            )
            Text(
                text = stringResource(Res.string.home_offline_pill),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
