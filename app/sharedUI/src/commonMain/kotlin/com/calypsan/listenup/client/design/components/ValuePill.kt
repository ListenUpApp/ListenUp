package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Expressive value selector: a filled tonal pill that shows the current [value] and a trailing
 * `expand_more` glyph, inviting a tap to open a menu of options. Replaces the heavier
 * dropdown-text-field affordance in grouped setting rows. Purely presentational — the
 * [androidx.compose.material3.DropdownMenu] anchoring lives at the call site, which wraps this pill
 * and the menu in a [androidx.compose.foundation.layout.Box].
 *
 * @param value The current value rendered in the pill.
 * @param onClick Invoked when the pill is tapped (typically to open the menu).
 * @param modifier Modifier for the pill surface.
 * @param containerColor Fill behind the pill.
 * @param contentColor Colour for the value text and the trailing glyph.
 */
@Composable
fun ValuePill(
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = 40.dp)
                    .padding(start = 16.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
        }
    }
}
