package com.calypsan.listenup.client.features.metadata.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.design.components.PillChip

/**
 * Region selector — a horizontally scrolling row of [PillChip]s for choosing which Audible
 * market a metadata search runs against.
 */
@Composable
fun RegionSelector(
    selectedRegion: MetadataLocale,
    onRegionSelected: (MetadataLocale) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        MetadataLocale.SUPPORTED.forEach { region ->
            PillChip(
                label = region.displayName,
                onClick = { onRegionSelected(region) },
                selected = region == selectedRegion,
                leadingIcon = if (region == selectedRegion) Icons.Outlined.Check else null,
            )
        }
    }
}
