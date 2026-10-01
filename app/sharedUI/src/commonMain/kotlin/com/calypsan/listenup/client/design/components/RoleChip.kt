package com.calypsan.listenup.client.design.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A small filled-tonal role badge: a [TonalLabel] pill carrying a bold [label].
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
    TonalLabel(
        label = label,
        modifier = modifier,
        containerColor = if (isRoot) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isRoot) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        icon = if (isRoot) Icons.Filled.Shield else null,
    )
}
