package com.calypsan.listenup.client.features.genredestination

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.genredestination.GenreCrumb
import com.calypsan.listenup.client.presentation.genredestination.GenreIdentity
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.browse_facet_books_label
import listenup.composeapp.generated.resources.browse_facet_total_label
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_search
import listenup.composeapp.generated.resources.genre_destination_more_options
import org.jetbrains.compose.resources.stringResource

/** Cream ink used for text/icons/badges over the genre hero's dark gradient. */
private val HeroInk = Color(0xFFF4ECE3)

/** CSS-style gradient angle for the hero backdrop: 158° — mostly top-to-bottom, tilted right. */
private const val HERO_GRADIENT_ANGLE_DEGREES = 158f

/**
 * Genre-hued hero band for a genre destination page: the back/search/overflow row, a scalloped
 * icon badge, the breadcrumb trail (root-first ancestors, each tappable), the genre title, an
 * optional curator blurb, and two stat chips (book count + total length).
 *
 * The backdrop is a [HERO_GRADIENT_ANGLE_DEGREES]-angled gradient from [GenreIdentity.hue] to a
 * darkened variant of the same hue (see [darken]) — the genre's accent colour, deepened toward the
 * bottom-right so the cream text stays legible over both ends.
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
    val hue = genreHueColor(identity.hue)
    val gradientColors = listOf(hue, darken(hue))

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                // Bleed the tint to the screen edges, undoing the grid's horizontal content padding.
                .padding(horizontal = -Spacing.screenMargin)
                .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                .drawWithCache {
                    val brush = angledGradientBrush(gradientColors, HERO_GRADIENT_ANGLE_DEGREES, size)
                    onDrawBehind { drawRect(brush) }
                },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = Spacing.screenMargin)
                    .padding(top = 8.dp, bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                    onClick = onBackClick,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ScallopBadge(size = 92.dp, containerColor = HeroInk.copy(alpha = 0.16f)) {
                    Icon(
                        imageVector = identity.icon.toImageVector(),
                        contentDescription = null,
                        tint = HeroInk,
                        modifier = Modifier.size(38.dp),
                    )
                }

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
    }
}

@Composable
private fun HeroIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .size(40.dp)
                .background(HeroInk.copy(alpha = 0.14f), CircleShape),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = HeroInk)
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

/** A single rounded translucent-white stat chip: leading icon, bold value, subdued label. */
@Composable
private fun HeroStatChip(
    icon: ImageVector,
    value: String,
    label: String,
) {
    Row(
        modifier =
            Modifier
                .background(HeroInk.copy(alpha = 0.14f), CircleShape)
                .padding(start = 13.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = HeroInk.copy(alpha = 0.85f),
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = HeroInk,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = HeroInk.copy(alpha = 0.7f),
        )
    }
}
