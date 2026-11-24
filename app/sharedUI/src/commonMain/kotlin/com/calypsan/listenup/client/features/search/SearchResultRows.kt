@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.calypsan.listenup.client.features.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.ContentRow
import com.calypsan.listenup.client.design.components.ContributorPhotoCircle
import com.calypsan.listenup.client.design.components.HeldLabel
import com.calypsan.listenup.client.design.components.RestrictedBookMarker
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.highlightMatch
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.features.seriesdetail.components.seriesPlacementLine
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.genre_book_count
import listenup.composeapp.generated.resources.genre_books_count
import listenup.composeapp.generated.resources.search_cover_for
import listenup.composeapp.generated.resources.search_see_all
import listenup.composeapp.generated.resources.search_see_all_group_a11y
import org.jetbrains.compose.resources.stringResource

/**
 * Expressive group header: an emphasized title with a scalloped cookie count badge tinted to
 * the group's container role. When the group is capped in the main results view, [onSeeAll] is
 * non-null and a trailing "See all" affordance opens the full single-type page.
 */
@Composable
internal fun GroupHeader(
    title: String,
    count: Int,
    badgeContainer: Color,
    badgeContent: Color,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        ScallopBadge(size = 34.dp, containerColor = badgeContainer) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = badgeContent,
            )
        }
        if (onSeeAll != null) {
            Spacer(modifier = Modifier.weight(1f))
            SeeAllAction(groupTitle = title, onClick = onSeeAll)
        }
    }
}

/** Trailing "See all" affordance in a capped group header: a label plus a forward chevron. */
@Composable
private fun SeeAllAction(
    groupTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    // Every group has a "See all"; TalkBack hears which one this is.
    val spokenLabel = stringResource(Res.string.search_see_all_group_a11y, groupTitle)
    Surface(
        onClick = {
            haptics.press()
            onClick()
        },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            modifier.semantics {
                role = Role.Button
                contentDescription = spokenLabel
            },
    ) {
        Row(
            modifier =
                Modifier
                    .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                    .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.search_see_all),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = ListenUpIcons.KeyboardArrowRightFilled,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun BookResultRow(
    hit: SearchHit,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContentRow(onClick = onClick, modifier = modifier) {
        Box {
            BookCoverImage(
                bookId = hit.id,
                coverPath = hit.coverPath,
                coverHash = hit.coverHash,
                contentDescription = stringResource(Res.string.search_cover_for, hit.name),
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(60.dp)
                        .clip(MaterialTheme.shapes.small),
            )
            // A held hit says so inline at the row's end; it is never in the restricted set.
            RestrictedBookMarker(
                bookId = hit.id,
                compact = true,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatch(hit.name, query),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            hit.author?.let { author ->
                Text(
                    text = highlightMatch(author, query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        hit.formatDuration()?.let { duration ->
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = duration,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The never-stranded path: a held book is found, marked, and opens into its triage page.
        if (hit.isHeld) {
            Spacer(modifier = Modifier.width(8.dp))
            HeldLabel()
        }
    }
}

@Composable
internal fun PersonResultRow(
    hit: SearchHit,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContentRow(onClick = onClick, modifier = modifier) {
        // The contributor's framed photo when they have one; otherwise today's circular icon badge.
        ContributorPhotoCircle(
            contributorId = hit.id,
            imagePath = hit.imagePath,
            frame = hit.imageFrame,
            size = 52.dp,
        ) {
            Box(
                modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ListenUpIcons.PersonFilled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatch(hit.name, query),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            hit.bookCount?.let { count ->
                Text(
                    text = bookCountLabel(count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SeriesResultRow(
    hit: SearchHit,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContentRow(onClick = onClick, modifier = modifier) {
        ScallopBadge(size = 52.dp, containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
            Icon(
                ListenUpIcons.PlaylistPlayFilled,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatch(hit.name, query),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Where the series sits — "in Cosmere › Mistborn · 8 books"; a root shows just its count.
            hit.bookCount?.let { count ->
                Text(
                    text = seriesPlacementLine(hit.seriesPath, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Tag hits as a wrapping row of Expressive pills, each highlighting the matched query. */
@Composable
internal fun TagFlow(
    tags: List<SearchHit>,
    query: String,
    onResultClick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        tags.forEach { hit ->
            Surface(
                onClick = {
                    haptics.press()
                    onResultClick(hit)
                },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.semantics { role = Role.Button },
            ) {
                Row(
                    modifier = Modifier.height(42.dp).padding(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = ListenUpIcons.TagFilled,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = highlightMatch(hit.name, query),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun bookCountLabel(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.genre_book_count, count)
    } else {
        stringResource(Res.string.genre_books_count, count)
    }
