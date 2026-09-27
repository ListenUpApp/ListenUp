package com.calypsan.listenup.client.features.bookedit.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.design.compactTouchTarget
import com.calypsan.listenup.client.design.haptics.LocalHaptics

/**
 * An input chip's trailing "remove" affordance: the chip's 24dp close glyph on a full 48dp target
 * that overlaps the chip's own padding, so the chip keeps its size.
 */
@Composable
internal fun ChipRemoveButton(
    contentDescription: String,
    onRemove: () -> Unit,
) {
    val haptics = LocalHaptics.current
    IconButton(
        onClick = {
            haptics.press()
            onRemove()
        },
        modifier = Modifier.compactTouchTarget(footprint = InputChipDefaults.AvatarSize),
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = contentDescription,
            modifier = Modifier.size(InputChipDefaults.AvatarSize),
        )
    }
}
