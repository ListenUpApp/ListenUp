package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.EntityTile
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.features.storyworld.components.anchorLabelText
import com.calypsan.listenup.client.presentation.storyworld.StandRowUi
import com.calypsan.listenup.client.presentation.storyworld.StorySoFarUiState
import com.calypsan.listenup.client.presentation.storyworld.StorySoFarViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.story_so_far_card_footer
import listenup.composeapp.generated.resources.story_so_far_card_subtitle
import listenup.composeapp.generated.resources.story_so_far_en_route
import listenup.composeapp.generated.resources.story_so_far_en_route_unknown
import listenup.composeapp.generated.resources.story_so_far_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val CARD_SHAPE = RoundedCornerShape(24.dp)
private const val BORDER_ALPHA = 0.18f
private const val GRADIENT_START_ALPHA = 0.16f
private val LEADING_TILE_SIZE = 42.dp
private val ROW_TILE_SIZE = 40.dp
private const val VISIBLE_ROW_COUNT = 3

/**
 * Story So Far CTA card shown on Book Detail — a live spoiler-safe glance at the book's Story
 * World (the top three "where things stand" rows folded up to the viewer's own listening
 * frontier), reached from its own [StorySoFarViewModel] instance. Renders nothing until the
 * world has folded in at least one row: an empty or loading world would just be a dead-end tease,
 * and [com.calypsan.listenup.client.features.bookdetail.components.StoryWorldSection] already
 * covers that entry point.
 *
 * @param bookId The book to recap.
 * @param onOpenFull Called with [bookId] when the header, a row, or the footer is tapped — opens
 *   the full Story So Far screen.
 * @param modifier Modifier for the card.
 */
@Composable
fun StorySoFarCard(
    bookId: String,
    onOpenFull: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StorySoFarViewModel = koinViewModel(),
) {
    LaunchedEffect(bookId) { viewModel.load(bookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val ready = state as? StorySoFarUiState.Ready ?: return
    if (ready.standRows.isEmpty()) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = BORDER_ALPHA)),
    ) {
        Column {
            CardHeader(frontierLabelText = anchorLabelText(ready.frontierLabel), onClick = { onOpenFull(bookId) })
            val visibleRows = ready.standRows.take(VISIBLE_ROW_COUNT)
            Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                visibleRows.forEachIndexed { index, row ->
                    CardStandRow(row = row)
                    if (index != visibleRows.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
            CardFooter(onClick = { onOpenFull(bookId) })
        }
    }
}

/** The gradient identity header — leading tile, title/subtitle, trailing chevron. Clickable. */
@Composable
private fun CardHeader(
    frontierLabelText: String,
    onClick: () -> Unit,
) {
    val gradientStart =
        MaterialTheme.colorScheme.primary
            .copy(alpha = GRADIENT_START_ALPHA)
            .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
    val gradientEnd = MaterialTheme.colorScheme.surfaceContainerLow

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(Brush.linearGradient(listOf(gradientStart, gradientEnd)))
                .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TonalIconTile(icon = Icons.Outlined.Public, size = LEADING_TILE_SIZE)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.story_so_far_title),
                fontSize = 16.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.story_so_far_card_subtitle, frontierLabelText),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One compact "where things stand" row — leading [EntityTile], name, and a "loc · status" line. */
@Composable
private fun CardStandRow(row: StandRowUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntityTile(name = row.entity.name, kind = row.entity.kind, tintSeed = row.entity.id, size = ROW_TILE_SIZE)
        Column {
            Text(
                text = row.entity.name,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            row.cardStatusLine()?.let { line ->
                Text(
                    text = line,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The "loc · status" caption for a [CardStandRow] — its location label and status line, dot-joined. */
@Composable
private fun StandRowUi.cardStatusLine(): String? {
    val from = enRouteFrom
    val location =
        when {
            enRoute && from != null -> stringResource(Res.string.story_so_far_en_route, from)
            enRoute -> stringResource(Res.string.story_so_far_en_route_unknown)
            else -> locationName
        }
    return listOfNotNull(location, statusLine).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The footer CTA row — a top divider plus "See full world state", clickable to [onClick]. */
@Composable
private fun CardFooter(onClick: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.story_so_far_card_footer),
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
