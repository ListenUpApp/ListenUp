package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A small filled-tonal role badge: a pill ([RoundedCornerShape] at 50%) carrying a bold [label].
 * For privileged roles, set [isRoot] to switch to a tertiary-container fill with a leading shield
 * glyph; otherwise it uses the secondary container. The canonical role indicator across admin
 * surfaces — user rows and invite rows compose it to show "Root" / "Admin" / "User".
 *
 * @param label Role text shown in the pill, e.g. `"Root"` or `"User"`.
 * @param modifier Modifier for the pill surface.
 * @param isRoot When true, uses the tertiary-container fill and a leading shield glyph.
 */
@Composable
fun RoleChip(
    label: String,
    modifier: Modifier = Modifier,
    isRoot: Boolean = false,
) {
    val containerColor =
        if (isRoot) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    val contentColor =
        if (isRoot) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 28.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isRoot) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = contentColor,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
    }
}
