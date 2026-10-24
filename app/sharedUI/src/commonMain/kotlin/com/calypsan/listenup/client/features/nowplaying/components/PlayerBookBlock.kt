package com.calypsan.listenup.client.features.nowplaying.components

import com.calypsan.listenup.client.design.theme.primaryInk
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.contributors.ClickableContributorLine
import com.calypsan.listenup.client.features.nowplaying.asBookContributors
import com.calypsan.listenup.client.features.nowplaying.authorLine
import com.calypsan.listenup.client.features.nowplaying.chapterCounter
import com.calypsan.listenup.client.features.nowplaying.chapterTitleLine
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_go_to_series
import listenup.composeapp.generated.resources.player_np_by
import listenup.composeapp.generated.resources.player_np_byline_fold
import listenup.composeapp.generated.resources.player_np_read_by
import listenup.composeapp.generated.resources.player_np_series_book
import org.jetbrains.compose.resources.stringResource

/** The phone cover's side (spec P4), before the available height caps it. */
internal val PlayerCoverSide = 324.dp

/** More names than this fold to the lead and a count (P8, P9). */
private const val BYLINE_FOLD_LIMIT = 3

/**
 * Now Playing's book (spec §3.1.1, P4–P9): the flat square cover, the "Chapter 6 of 75" counter, the chapter's own
 * title, the book title, and both bylines with every name a link. The book sentence is not here: it sits between the
 * scrubber's times, a progress leaf.
 */
@Composable
fun PlayerBookBlock(
    state: PlayerUiState.Loaded,
    coverSide: Dp,
    onContributor: (String) -> Unit,
    onShowAuthors: () -> Unit,
    onShowNarrators: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PlayerCover(state = state, side = coverSide)
        Spacer(Modifier.height(Spacing.xl))
        PlayerTitleBlock(
            state = state,
            onContributor = onContributor,
            onShowAuthors = onShowAuthors,
            onShowNarrators = onShowNarrators,
            alignment = Alignment.CenterHorizontally,
        )
    }
}

/** The cover alone: square, 28dp corners, flat — no shadow, no glow (F4, F6). */
@Composable
internal fun PlayerCover(
    state: PlayerUiState.Loaded,
    side: Dp,
    modifier: Modifier = Modifier,
) {
    BookCoverImage(
        bookId = state.book.id,
        coverPath = state.book.coverPath,
        contentDescription = null,
        title = state.book.title,
        author = state.book.authorLine,
        coverHash = state.book.coverHash,
        modifier =
            modifier
                .size(side)
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.large)
                // Decorative: the title beside it names the book, so its placeholder never reads it twice.
                .clearAndSetSemantics {},
    )
}

/** The order [PlayerTitleBlock] sets the book in. */
internal enum class TitleArrangement {
    /** The phone's (§3.1.1): counter · chapter title · book title · bylines. */
    Phone,

    /**
     * The wide player column's (`NP-AndroidTablet`): the series link · the book title · the bylines, then the counter
     * over the chapter title.
     */
    Wide,
}

/** The gap between the wide column's bylines and its chapter (the board's 26dp). */
private val WideChapterGap = 26.dp

/**
 * The counter (in `primary`, only beside a real chapter title: C6), the chapter line, the title at `titleLarge`
 * Medium and the two bylines. [alignment] centres it on a phone and starts it in the wide layout's column; a phone on
 * its side drops the counter and the bylines ([showsCounter], [showsBylines]) so the transport still fits.
 *
 * [TitleArrangement.Wide] is the tablet column's order (P10): the first series as a link above a `headlineMedium` title
 * ("The Stormlight Archive · Book 1", which [onSeries] follows), the bylines, then the counter over the chapter title.
 */
@Composable
internal fun PlayerTitleBlock(
    state: PlayerUiState.Loaded,
    onContributor: (String) -> Unit,
    onShowAuthors: () -> Unit,
    onShowNarrators: () -> Unit,
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier,
    titleLines: Int = 2,
    showsBylines: Boolean = true,
    showsCounter: Boolean = true,
    arrangement: TitleArrangement = TitleArrangement.Phone,
    onSeries: (String) -> Unit = {},
) {
    if (arrangement == TitleArrangement.Wide) {
        WideTitleBlock(
            state = state,
            onContributor = onContributor,
            onShowAuthors = onShowAuthors,
            onShowNarrators = onShowNarrators,
            onSeries = onSeries,
            titleLines = titleLines,
            modifier = modifier,
        )
        return
    }
    val textAlign = if (alignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start
    Column(modifier = modifier, horizontalAlignment = alignment) {
        state.chapterCounter()?.takeIf { showsCounter }?.let { counter ->
            Text(
                text = counter,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primaryInk,
                textAlign = textAlign,
            )
        }
        Text(
            text = state.chapterTitleLine(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = state.book.title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = textAlign,
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (showsBylines) {
            val bylineArrangement =
                if (alignment == Alignment.CenterHorizontally) {
                    Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally)
                } else {
                    Arrangement.Start
                }
            Bylines(state, onContributor, onShowAuthors, onShowNarrators, bylineArrangement)
        }
    }
}

/** The wide column's book: series link · title · bylines · counter over the chapter title (`NP-AndroidTablet`). */
@Composable
private fun WideTitleBlock(
    state: PlayerUiState.Loaded,
    onContributor: (String) -> Unit,
    onShowAuthors: () -> Unit,
    onShowNarrators: () -> Unit,
    onSeries: (String) -> Unit,
    titleLines: Int,
    modifier: Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        state.book.series.firstOrNull()?.let { series -> SeriesLink(series = series, onSeries = onSeries) }
        Text(
            text = state.book.title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.xs))
        Bylines(state, onContributor, onShowAuthors, onShowNarrators, Arrangement.Start)
        Spacer(Modifier.height(WideChapterGap))
        state.chapterCounter()?.let { counter ->
            Text(
                text = counter,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = state.chapterTitleLine(),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * "The Stormlight Archive · Book 1", or the bare name when the book has no place in the series: a text link to the
 * series page with a 48dp target (§8). TalkBack hears the words, "button", and "double tap to go to series".
 */
@Composable
private fun SeriesLink(
    series: PlayerSeriesRef,
    onSeries: (String) -> Unit,
) {
    val haptics = LocalHaptics.current
    val words =
        series.sequenceLabel?.let { stringResource(Res.string.player_np_series_book, series.name, it) } ?: series.name
    Text(
        text = words,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(Res.string.player_go_to_series),
                ) {
                    haptics.press()
                    onSeries(series.id)
                }.wrapContentHeight(Alignment.CenterVertically),
    )
}

/** The author and narrator lines. */
@Composable
private fun Bylines(
    state: PlayerUiState.Loaded,
    onContributor: (String) -> Unit,
    onShowAuthors: () -> Unit,
    onShowNarrators: () -> Unit,
    arrangement: Arrangement.Horizontal,
) {
    Byline(
        people = state.book.authors,
        lead = stringResource(Res.string.player_np_by),
        onContributor = onContributor,
        onShowAll = onShowAuthors,
        arrangement = arrangement,
    )
    Byline(
        people = state.book.narrators,
        lead = stringResource(Res.string.player_np_read_by),
        onContributor = onContributor,
        onShowAll = onShowNarrators,
        arrangement = arrangement,
    )
}

/** "by Brandon Sanderson" / "Read by Michael Kramer & Kate Reading"; more than three fold to the full-cast sheet. */
@Composable
private fun Byline(
    people: List<PlayerContributor>,
    lead: String,
    onContributor: (String) -> Unit,
    onShowAll: () -> Unit,
    arrangement: Arrangement.Horizontal,
) {
    if (people.isEmpty()) return
    ClickableContributorLine(
        contributors = people.asBookContributors(),
        onContributorClick = onContributor,
        style = MaterialTheme.typography.bodyMedium,
        nameColor = MaterialTheme.colorScheme.onSurfaceVariant,
        separatorColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = arrangement,
        prefix = "$lead ",
        foldLimit = BYLINE_FOLD_LIMIT,
        overflowTextRes = Res.string.player_np_byline_fold,
        onOverflowClick = onShowAll,
    )
}
