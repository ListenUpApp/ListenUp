package com.calypsan.listenup.client.features.seriesdetail

import com.calypsan.listenup.client.design.haptics.LocalHaptics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.design.components.FannedDeck
import com.calypsan.listenup.client.design.components.FannedDeckCover
import com.calypsan.listenup.client.design.components.HeroNavRow
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.features.contributors.ClickableContributorLine
import com.calypsan.listenup.client.presentation.bookdetail.HERO_CONTRIBUTOR_FOLD_LIMIT
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_other_authors
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.series_edit_series
import listenup.composeapp.generated.resources.series_label
import org.jetbrains.compose.resources.stringResource
import com.calypsan.listenup.client.features.seriesdetail.components.SeriesBreadcrumb
import listenup.composeapp.generated.resources.series_count_books
import com.calypsan.listenup.client.design.theme.ContentShapes
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.client.design.theme.HeroInk

// region hero

/** The full color-blocked hero used in the narrow layout (rounded bottom, top action row). */
@Composable
internal fun SeriesColorHero(
    state: SeriesDetailUiState.Ready,
    onBackClick: () -> Unit,
    onContributorClick: (String) -> Unit,
    onShowAuthors: () -> Unit,
    /** Null when the reader may not edit the series (no Edit metadata): no pencil is offered. */
    onEditClick: (() -> Unit)?,
    onSeriesClick: (String) -> Unit,
) {
    val haptics = LocalHaptics.current
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(ContentShapes.hero)
                .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        HeroBlob(modifier = Modifier.align(Alignment.TopEnd).offset(x = 70.dp, y = (-50).dp).size(220.dp))
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeroNavRow(onBack = onBackClick) {
                if (onEditClick != null && !LocalDeviceContext.current.isLeanback) {
                    IconButton(
                        onClick = {
                            haptics.press()
                            onEditClick()
                        },
                        modifier =
                            Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(Res.string.series_edit_series),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HeroBody(
                    state = state,
                    onContributorClick = onContributorClick,
                    onShowAuthors = onShowAuthors,
                    onSeriesClick = onSeriesClick,
                )
            }
        }
    }
}

/** Soft organic accent blob behind the hero content (echoes the design's brand squircle). */
@Composable
internal fun HeroBlob(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .clip(BlobShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
    )
}

/** Asymmetric rounded squircle approximating the design's organic blob. */
private val BlobShape =
    RoundedCornerShape(
        topStartPercent = 46,
        topEndPercent = 54,
        bottomEndPercent = 46,
        bottomStartPercent = 54,
    )

/** Deck + overline + title + authors + stat row. Shared by both layouts. */
@Composable
internal fun HeroBody(
    state: SeriesDetailUiState.Ready,
    onContributorClick: (String) -> Unit,
    onShowAuthors: () -> Unit,
    onSeriesClick: (String) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FannedDeck(
            covers = state.books.map { it.toDeckCover() },
            size = 150.dp,
            peek = 34.dp,
            max = 4,
        )
        Spacer(Modifier.height(22.dp))
        // A sub-series names where it sits instead of the bare overline.
        if (state.ancestors.isEmpty()) {
            Text(
                text = stringResource(Res.string.series_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
        } else {
            SeriesBreadcrumb(ancestors = state.ancestors, onCrumbClick = onSeriesClick)
        }
        Text(
            text = state.seriesName,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.semantics { heading() },
        )
        // Authors — up to two names individually tappable; folds to "{lead}, N other authors"
        // beyond that, opening the full authors roster sheet. Mirrors the Book Detail hero.
        if (state.seriesAuthors.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ClickableContributorLine(
                contributors = state.seriesAuthors,
                onContributorClick = onContributorClick,
                style = MaterialTheme.typography.titleMedium,
                nameColor = HeroInk.muted(),
                separatorColor = HeroInk.muted(),
                modifier = Modifier.fillMaxWidth(),
                foldLimit = HERO_CONTRIBUTOR_FOLD_LIMIT,
                overflowTextRes = Res.string.book_detail_other_authors,
                onOverflowClick = onShowAuthors,
            )
        }
        if (state.isGrouped) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(Res.string.series_count_books, state.childSeries.size, state.books.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            HeroStat(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                value = "${state.books.size} books",
                label = "${state.finishedCount} finished",
            )
            HeroStat(
                icon = Icons.Default.Schedule,
                value = state.formatTotalDuration(),
                label = "Total",
            )
        }
    }
}

@Composable
internal fun HeroActionRow(
    onBackClick: () -> Unit,
    /** Null when the reader may not edit the series (no Edit metadata): no pencil is offered. */
    onEditClick: (() -> Unit)?,
) {
    val tint = MaterialTheme.colorScheme.onPrimaryContainer
    val haptics = LocalHaptics.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                haptics.press()
                onBackClick()
            },
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.common_back), tint = tint)
        }
        Spacer(Modifier.weight(1f))
        if (onEditClick != null && !LocalDeviceContext.current.isLeanback) {
            IconButton(
                onClick = {
                    haptics.press()
                    onEditClick()
                },
            ) {
                Icon(Icons.Default.Edit, stringResource(Res.string.series_edit_series), tint = tint)
            }
        }
    }
}

@Composable
private fun HeroStat(
    icon: ImageVector,
    value: String,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = HeroInk.muted(),
            )
        }
    }
}

// endregion

private fun BookListItem.toDeckCover(): FannedDeckCover =
    FannedDeckCover(
        bookId = id.value,
        coverPath = coverPath,
        title = title,
        author = authors.firstOrNull()?.name,
    )
