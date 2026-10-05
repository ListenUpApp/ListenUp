package com.calypsan.listenup.client.features.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_store_region_hint
import listenup.composeapp.generated.resources.admin_store_region_title
import org.jetbrains.compose.resources.stringResource

/** Test tag for the store menu's anchor field. */
internal const val STORE_REGION_FIELD_TAG = "admin_store_region_field"

/**
 * Admin → Store region: the Audible store this library's match searches start in. One exposed dropdown
 * (an M3 menu anchored to a read-only field — one control, not ten pills), saved the moment a store is
 * chosen. Each search can still pick another store for itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoreRegionGroup(
    region: String,
    onRegionChange: (String) -> Unit,
) {
    val haptics = LocalHaptics.current
    var expanded by remember { mutableStateOf(false) }
    val selected = MetadataLocale.SUPPORTED.firstOrNull { it.region == region } ?: MetadataLocale(region)
    val title = stringResource(Res.string.admin_store_region_title)

    SectionGroup(label = title) {
        SectionSegment {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selected.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(title) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        shape = MaterialTheme.shapes.medium,
                        singleLine = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .testTag(STORE_REGION_FIELD_TAG),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        MetadataLocale.SUPPORTED.forEach { locale ->
                            val isSelected = locale.region == selected.region
                            DropdownMenuItem(
                                text = { Text(locale.displayName) },
                                leadingIcon =
                                    if (isSelected) {
                                        { Icon(Icons.Filled.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                onClick = {
                                    haptics.selectionTick()
                                    expanded = false
                                    if (!isSelected) onRegionChange(locale.region)
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(Res.string.admin_store_region_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
