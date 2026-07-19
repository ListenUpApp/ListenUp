package com.calypsan.listenup.client.features.genredestination

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.presentation.genredestination.GenreCrumb
import com.calypsan.listenup.client.presentation.genredestination.GenreIdentity
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.browse_facet_books_label
import listenup.composeapp.generated.resources.browse_facet_total_label
import listenup.composeapp.generated.resources.common_search
import listenup.composeapp.generated.resources.genre_destination_more_options
import org.jetbrains.compose.resources.stringResource

/**
 * Genre-hued hero band for a genre destination page: the back/search/overflow row, a scalloped
 * icon badge, the breadcrumb trail (root-first ancestors, each tappable), the genre title, an
 * optional curator blurb, and two stat chips (book count + total length).
 *
 * The backdrop is [FacetHeroScaffold]'s angled gradient from [GenreIdentity.hue] to a darkened
 * variant of the same hue — the genre's accent colour, deepened toward the bottom-right so the
 * cream text stays legible over both ends. The chrome (gradient, back button, badge, stat chip
 * styling) is shared with the flat facet (tag/mood) hero via [FacetHeroScaffold]; only the
 * breadcrumb/blurb body below the badge is genre-specific.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenreDestinationHero(
    identity: GenreIdentity,
    breadcrumb: List<GenreCrumb>,
    stats: FacetStats,
    onBackClick: () -> Unit,
    onGenreClick: (String) -> Unit,
) {
    val haptics = LocalHaptics.current

    FacetHeroScaffold(
        hue = identity.hue,
        icon = identity.icon.toImageVector(),
        onBackClick = onBackClick,
        trailingActions = {
            // Search and overflow are chrome-only for now — no destination is wired yet.
            HeroIconButton(
                icon = Icons.Filled.Search,
                contentDescription = stringResource(Res.string.common_search),
                onClick = {},
            )
            HeroIconButton(
                icon = Icons.Filled.MoreVert,
                contentDescription = stringResource(Res.string.genre_destination_more_options),
                onClick = {},
            )
        },
    ) {
        if (breadcrumb.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            BreadcrumbRow(
                breadcrumb = breadcrumb,
                onGenreClick = { genreId ->
                    haptics.selectionTick()
                    onGenreClick(genreId)
                },
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = identity.name,
            style =
                MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                ),
            color = HeroInk,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // Curator blurbs aren't synced from the domain model yet — Genre carries no
        // description field — so this is null today and the block below never renders.
        identity.blurb?.let { blurb ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = HeroInk.copy(alpha = 0.85f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroStatChip(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                value = stats.bookCount.toString(),
                label = stringResource(Res.string.browse_facet_books_label),
            )
            HeroStatChip(
                icon = Icons.Outlined.Schedule,
                value = DurationFormatter.hoursMinutesCompact(stats.totalDurationMs.milliseconds),
                label = stringResource(Res.string.browse_facet_total_label),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BreadcrumbRow(
    breadcrumb: List<GenreCrumb>,
    onGenreClick: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        breadcrumb.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = HeroInk.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = crumb.name,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = HeroInk,
                modifier = Modifier.clickable { onGenreClick(crumb.genreId.value) },
            )
        }
    }
}
