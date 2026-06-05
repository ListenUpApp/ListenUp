package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/**
 * Canonical section header: a bold title with an optional coral "See all" action on the trailing
 * baseline. Used by the Home sections (Continue Listening, This Week, My Shelves) and available for
 * any list section that needs the same treatment.
 *
 * (Several feature screens still carry their own private `SectionHeader` composables —
 * bookdetail/library/profile/search — which could be consolidated onto this later.)
 *
 * @param title The section heading.
 * @param modifier Optional modifier.
 * @param onSeeAll When non-null, renders a trailing "See all" affordance invoking this.
 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (onSeeAll != null) {
            Text(
                text = "See all",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onSeeAll),
            )
        }
    }
}
