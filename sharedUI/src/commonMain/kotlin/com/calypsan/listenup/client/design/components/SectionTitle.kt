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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_see_all
import org.jetbrains.compose.resources.stringResource

/**
 * Canonical section header: a bold title with an optional coral "See all" action on the trailing
 * baseline. Used by the Home sections (Continue Listening, This Week, My Shelves) and available for
 * any list section that needs the same treatment.
 *
 * (Several feature screens still carry their own private `SectionHeader` composables —
 * bookdetail/library/profile/search — which could be consolidated onto this later.)
 *
 * For an icon action (e.g. a refresh button) rather than the "See all" text, pass a [trailing] slot
 * instead. [onSeeAll] and [trailing] are mutually exclusive in practice; when both are supplied the
 * "See all" text wins.
 *
 * @param title The section heading.
 * @param modifier Optional modifier.
 * @param onSeeAll When non-null, renders a trailing "See all" affordance invoking this.
 * @param trailing Optional trailing slot (e.g. an icon button), aligned to the title baseline. Ignored
 *   when [onSeeAll] is non-null.
 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            onSeeAll != null -> {
                Text(
                    // Never wrap — the title yields space (above) so "See all" stays a single line.
                    text = stringResource(Res.string.common_see_all),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.clickable(onClick = onSeeAll),
                )
            }

            trailing != null -> {
                trailing()
            }
        }
    }
}
