package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import com.calypsan.listenup.client.design.haptics.LocalHaptics

/**
 * Outlined pill button with an optional filled-[selected] state — the canonical chip across the
 * player panels (speed presets, sleep durations) and the search scope filters. Unselected:
 * transparent with a 1.5.dp [outlineVariant] border. Selected: filled [primary]. An optional
 * [leadingIcon] renders before the label. The clickable area is held to a 48.dp minimum height for
 * accessibility regardless of the label's text size.
 *
 * @param label Text shown in the pill.
 * @param onClick Invoked when the pill is tapped.
 * @param modifier Modifier for the pill surface.
 * @param selected Whether the pill is in its filled, selected state.
 * @param leadingIcon Optional icon rendered before the label, tinted to the content colour.
 */
@Composable
fun PillChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val haptics = LocalHaptics.current
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = {
            haptics.selectionTick()
            onClick()
        },
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = contentColor,
        border = if (selected) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.let { icon ->
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = contentColor)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
                color = contentColor,
            )
        }
    }
}
