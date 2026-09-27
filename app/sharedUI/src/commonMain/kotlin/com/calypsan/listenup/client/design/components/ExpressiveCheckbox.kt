package com.calypsan.listenup.client.design.components

import com.calypsan.listenup.client.design.compactTouchTarget
import androidx.compose.runtime.remember
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics

private val CHECKBOX_SIZE = 26.dp
private val CHECKBOX_RADIUS = 8.dp
private const val CHECK_ICON_RATIO = 0.7f

/**
 * The signature M3 Expressive selection glyph for grouped field/chapter rows: a soft-cornered
 * ([CHECKBOX_RADIUS]) square that fills with [accent] and shows a check when [checked], or sits as a
 * 2.dp [MaterialTheme.colorScheme.outline] outline when unchecked. Replaces the stock material
 * [androidx.compose.material3.Checkbox] inside the metadata-match field list and chapter-review sheet
 * so selection reads as a bold coral tile rather than a hairline tick.
 *
 * The whole tile is a [Role.Checkbox] toggle when [onCheckedChange] is supplied, with a 48dp touch
 * target around its 26dp tile; pass null to render a read-only indicator (the parent row owns the click — make that row `toggleable` so it is the
 * checkbox TalkBack announces). Either way the checked state is exposed to accessibility services.
 *
 * @param checked Whether the box is in its filled, selected state.
 * @param modifier Modifier for the box.
 * @param onCheckedChange Invoked with the toggled value when the tile is tapped; null = read-only.
 * @param accent Fill colour when checked (defaults to the primary/coral accent).
 */
@Composable
fun ExpressiveCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    if (onCheckedChange == null) {
        // Read-only: the parent row owns the click, but the state is still announced — and
        // merges up into that row, so "checked" reaches TalkBack either way.
        CheckboxTile(
            checked = checked,
            accent = accent,
            modifier = modifier.semantics { toggleableState = ToggleableState(checked) },
        )
        return
    }
    val haptics = LocalHaptics.current
    val interactionSource = remember { MutableInteractionSource() }
    // A full 48dp target centred on the 26dp tile; it overlaps the row's padding, so the row keeps
    // its height. The press ripple stays on the tile.
    Box(
        modifier =
            modifier
                .compactTouchTarget(footprint = CHECKBOX_SIZE)
                .toggleable(
                    value = checked,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Checkbox,
                ) { on ->
                    haptics.toggle(on = on)
                    onCheckedChange(on)
                },
        contentAlignment = Alignment.Center,
    ) {
        CheckboxTile(
            checked = checked,
            accent = accent,
            modifier = Modifier.indication(interactionSource, ripple()),
        )
    }
}

/** The drawn tile: filled with a check when [checked], an outline when not. */
@Composable
private fun CheckboxTile(
    checked: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            Modifier
                .size(CHECKBOX_SIZE)
                .clip(RoundedCornerShape(CHECKBOX_RADIUS))
                .then(modifier)
                .then(
                    if (checked) {
                        Modifier.background(accent)
                    } else {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(CHECKBOX_RADIUS))
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(CHECKBOX_SIZE * CHECK_ICON_RATIO),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
