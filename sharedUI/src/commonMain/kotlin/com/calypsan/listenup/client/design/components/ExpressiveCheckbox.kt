package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp

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
 * The whole tile is the tap target when [onCheckedChange] is supplied; pass null to render a
 * read-only indicator (the parent row owns the click).
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
    val clickModifier =
        if (onCheckedChange != null) {
            Modifier.clickable(role = Role.Checkbox) { onCheckedChange(!checked) }
        } else {
            Modifier
        }
    Box(
        modifier =
            modifier
                .size(CHECKBOX_SIZE)
                .clip(RoundedCornerShape(CHECKBOX_RADIUS))
                .then(clickModifier)
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
