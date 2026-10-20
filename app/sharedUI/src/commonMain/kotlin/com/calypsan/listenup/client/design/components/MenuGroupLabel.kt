package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.client.design.theme.Spacing

/**
 * The heading over a group in an M3 `DropdownMenu`, which has no native sections: a label, after the
 * `HorizontalDivider` that separates the group (m3.material.io/components/menus). A heading for TalkBack.
 */
@Composable
fun MenuGroupLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .padding(MenuDefaults.DropdownMenuItemContentPadding)
                .padding(vertical = Spacing.xs)
                .semantics { heading() },
    )
}
