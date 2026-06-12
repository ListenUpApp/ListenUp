package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_count_unit
import listenup.composeapp.generated.resources.library_ignoring_articles
import listenup.composeapp.generated.resources.library_title_sort_toggle
import org.jetbrains.compose.resources.stringResource

/**
 * The Library's count + "Title sort" row.
 *
 * Shows "{count} {unit}" on the left (with a "· ignoring A · An · The" caption when [ignoreArticles]
 * is on), and the design's "Title sort" toggle on the right — a filled-coral pill when on, an
 * outlined pill when off. Toggling it flips article-aware title sorting (so "The Jungle Book" files
 * under J).
 *
 * @param count Number of items under the active filter.
 * @param unit Plural noun for [count] (e.g. "titles").
 * @param ignoreArticles Whether leading articles (A / An / The) are ignored when sorting by title.
 * @param onToggleArticles Invoked when the Title-sort toggle is tapped.
 * @param modifier Optional modifier.
 */
@Composable
fun LibrarySortBar(
    count: Int,
    unit: String,
    ignoreArticles: Boolean,
    onToggleArticles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenMargin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.library_count_unit, count, unit),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (ignoreArticles) {
                Text(
                    text = stringResource(Res.string.library_ignoring_articles),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        TitleSortToggle(on = ignoreArticles, onClick = onToggleArticles)
    }
}

/** Filled-coral when [on], outlined when off; a `check` (when on) + `sort_by_alpha` + label. */
@Composable
private fun TitleSortToggle(
    on: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (on) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier =
                Modifier
                    .height(42.dp)
                    .padding(start = if (on) 14.dp else 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (on) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Icon(
                imageVector = Icons.Default.SortByAlpha,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(Res.string.library_title_sort_toggle),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
