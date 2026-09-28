package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import androidx.compose.foundation.shape.CircleShape

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
 * @param selected The pill's state in a choice set — filled when true. Null (the default) means the
 *   pill is a plain action (Invite, +5 min) and claims no selection state; a Boolean exposes it as
 *   selected/not selected to TalkBack, not only by colour.
 * @param leadingIcon Optional icon rendered before the label, tinted to the content colour.
 * @param selectionRole The role a selectable pill announces: [Role.RadioButton] for a single-choice
 *   set (speed, boost, region), [Role.Checkbox] for a multi-select filter. Ignored for actions.
 */
@Composable
fun PillChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    leadingIcon: ImageVector? = null,
    selectionRole: Role = Role.RadioButton,
) {
    val haptics = LocalHaptics.current
    val isSelected = selected == true
    val contentColor =
        if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = CircleShape
    val color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val border = if (isSelected) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant)
    val tap = {
        haptics.selectionTick()
        onClick()
    }
    val content: @Composable () -> Unit = {
        PillChipContent(label = label, leadingIcon = leadingIcon, isSelected = isSelected, contentColor = contentColor)
    }
    if (selected == null) {
        Surface(
            onClick = tap,
            modifier = modifier.semantics { role = Role.Button },
            shape = shape,
            color = color,
            contentColor = contentColor,
            border = border,
            content = content,
        )
    } else {
        Surface(
            selected = selected,
            onClick = tap,
            modifier = modifier.semantics { role = selectionRole },
            shape = shape,
            color = color,
            contentColor = contentColor,
            border = border,
            content = content,
        )
    }
}

@Composable
private fun PillChipContent(
    label: String,
    leadingIcon: ImageVector?,
    isSelected: Boolean,
    contentColor: Color,
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
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
            color = contentColor,
        )
    }
}
