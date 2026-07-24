package com.calypsan.listenup.client.features.genredestination

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.genre_destination_audiobook_count
import listenup.composeapp.generated.resources.genre_destination_audiobooks_count
import org.jetbrains.compose.resources.stringResource

/*
 * Shared hue-gradient hero chrome for facet destination pages — the genre-browse page and the flat
 * tag/mood facet-browse page. Both pages carry the same visual identity language (angled hue
 * gradient, cream ink, scalloped icon badge, translucent stat chips); this file is the single home
 * for that chrome so the two pages never drift into look-alike-but-different implementations.
 */

/** Cream ink used for text/icons/badges over a facet hero's dark gradient. */
internal val HeroInk = Color(0xFFF4ECE3)

/** CSS-style gradient angle for the hero backdrop: 158deg - mostly top-to-bottom, tilted right. */
internal const val HERO_GRADIENT_ANGLE_DEGREES = 158f

/**
 * Hue-gradient hero shell shared by the genre and flat-facet (tag/mood) destination pages: the
 * angled-gradient backdrop bleeding to the screen edges, the back button (plus optional
 * [trailingActions]), and a centered scalloped badge carrying [icon]. Callers supply the
 * identity-specific body — breadcrumb/eyebrow, title, blurb, stat chips — as [content], laid out
 * below the badge.
 */
@Composable
internal fun FacetHeroScaffold(
    hue: String,
    icon: ImageVector,
    onBackClick: () -> Unit,
    trailingActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val hueColor = genreHueColor(hue)
    val gradientColors = listOf(hueColor, darken(hueColor))

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailingActions)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ScallopBadge(size = 92.dp, containerColor = HeroInk.copy(alpha = 0.16f)) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = HeroInk,
                        modifier = Modifier.size(38.dp),
                    )
                }

                content()
            }
        }
    }
}

/** Translucent circular icon button rendered against the hero's dark gradient. */
@Composable
internal fun HeroIconButton(
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

/** A single rounded translucent-white stat chip: leading icon, bold value, subdued label. */
@Composable
internal fun HeroStatChip(
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

/** "{n} audiobook(s)" section header shown above a facet destination page's book grid. */
@Composable
internal fun FacetSectionHeader(
    bookCount: Int,
    modifier: Modifier = Modifier,
) {
    val text =
        if (bookCount == 1) {
            stringResource(Res.string.genre_destination_audiobook_count, bookCount)
        } else {
            stringResource(Res.string.genre_destination_audiobooks_count, bookCount)
        }

    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}
