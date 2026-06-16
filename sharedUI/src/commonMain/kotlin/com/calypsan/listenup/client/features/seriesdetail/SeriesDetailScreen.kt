package com.calypsan.listenup.client.features.seriesdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.FannedDeck
import com.calypsan.listenup.client.design.components.FannedDeckCover
import com.calypsan.listenup.client.design.components.HeroNavRow
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.series_books_in_series
import listenup.composeapp.generated.resources.series_book_position
import listenup.composeapp.generated.resources.series_continue_book
import listenup.composeapp.generated.resources.series_edit_series
import listenup.composeapp.generated.resources.series_label
import listenup.composeapp.generated.resources.series_progress_duration
import listenup.composeapp.generated.resources.series_start_book

/**
 * Series detail — a color-blocked hero with the expressive fanned cover deck, a "Continue"
 * action, and a numbered "Books in series" list with per-book progress.
 *
 * Adapts by width:
 * - Narrow: one scrolling column (hero → continue → numbered list).
 * - Wide: a fixed hero panel beside a two-column book grid.
 */
@Composable
fun SeriesDetailScreen(
    seriesId: String,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onEditClick: (String) -> Unit,
    viewModel: SeriesDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(seriesId) { viewModel.loadSeries(seriesId) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    // The color hero bleeds edge-to-edge behind the status bar (the system clock sits on the
    // color block, matching the design), so the Scaffold keeps only the bottom (nav-bar) inset;
    // HeroNavRow re-insets the floating back/edit controls below the status bar.
    Scaffold(contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom)) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            when (val current = state) {
                SeriesDetailUiState.Idle, SeriesDetailUiState.Loading -> {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is SeriesDetailUiState.Error -> {
                    Text(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                is SeriesDetailUiState.Ready -> {
                    val wide =
                        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
                            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
                        )
                    if (wide) {
                        WideSeriesDetailContent(current, onBackClick, onBookClick) { onEditClick(seriesId) }
                    } else {
                        NarrowSeriesDetailContent(current, onBackClick, onBookClick) { onEditClick(seriesId) }
                    }
                }
            }
        }
    }
}

// region layouts

@Composable
private fun NarrowSeriesDetailContent(
    state: SeriesDetailUiState.Ready,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onEditClick: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SeriesColorHero(
                state = state,
                onBackClick = onBackClick,
                onEditClick = onEditClick,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ContinueButton(
                state = state,
                onBookClick = onBookClick,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            BooksSectionHeader(count = state.books.size, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp))
        }
        itemsIndexed(state.books, key = { _, b -> b.id.value }) { index, book ->
            SeriesBookRow(
                book = book,
                positionLabel = book.seriesSequence ?: (index + 1).toString(),
                finished = book.id in state.finishedBookIds,
                progress = state.bookProgress[book.id],
                highlighted = book.id == state.resumeTarget && state.bookProgress[book.id] != null,
                onClick = { onBookClick(book.id.value) },
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
    }
}

@Composable
private fun WideSeriesDetailContent(
    state: SeriesDetailUiState.Ready,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onEditClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Left: color-blocked hero panel with the Continue action pinned at the bottom.
        Box(
            modifier =
                Modifier
                    .width(420.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            HeroBlob(modifier = Modifier.align(Alignment.TopEnd).offset(x = 60.dp, y = (-60).dp).size(240.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HeroActionRow(onBackClick = onBackClick, onEditClick = onEditClick)
                Spacer(Modifier.height(8.dp))
                HeroBody(state)
                Spacer(Modifier.height(24.dp))
                ContinueButton(state = state, onBookClick = onBookClick, modifier = Modifier.fillMaxWidth())
            }
        }

        // Right: numbered "Books in series" as a two-column grid.
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BooksSectionHeader(count = state.books.size, modifier = Modifier.padding(bottom = 4.dp))
            }
            itemsIndexed(state.books, key = { _, b -> b.id.value }) { index, book ->
                SeriesBookRow(
                    book = book,
                    positionLabel = book.seriesSequence ?: (index + 1).toString(),
                    finished = book.id in state.finishedBookIds,
                    progress = state.bookProgress[book.id],
                    highlighted = book.id == state.resumeTarget && state.bookProgress[book.id] != null,
                    onClick = { onBookClick(book.id.value) },
                )
            }
        }
    }
}

// endregion

// region hero

/** The full color-blocked hero used in the narrow layout (rounded bottom, top action row). */
@Composable
private fun SeriesColorHero(
    state: SeriesDetailUiState.Ready,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
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
                if (!LocalDeviceContext.current.isLeanback) {
                    IconButton(
                        onClick = onEditClick,
                        modifier =
                            Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), CircleShape),
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
                HeroBody(state)
            }
        }
    }
}

/** Soft organic accent blob behind the hero content (echoes the design's brand squircle). */
@Composable
private fun HeroBlob(modifier: Modifier = Modifier) {
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

/** Deck + overline + title + author + stat row. Shared by both layouts. */
@Composable
private fun HeroBody(state: SeriesDetailUiState.Ready) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FannedDeck(
            covers = state.books.map { it.toDeckCover() },
            size = 150.dp,
            peek = 34.dp,
            max = 4,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = stringResource(Res.string.series_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.seriesName,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        state.seriesAuthor?.let { author ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = author,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
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
private fun HeroActionRow(
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onPrimaryContainer
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.common_back), tint = tint)
        }
        Spacer(Modifier.weight(1f))
        if (!LocalDeviceContext.current.isLeanback) {
            IconButton(onClick = onEditClick) {
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
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
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
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

/** Brand "Continue / Start Book N" pill. Hidden when the whole series is finished. */
@Composable
private fun ContinueButton(
    state: SeriesDetailUiState.Ready,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetId = state.resumeTarget ?: return
    val target = state.books.firstOrNull { it.id == targetId } ?: return
    val index = state.books.indexOfFirst { it.id == targetId }
    val positionLabel = target.seriesSequence ?: (index + 1).toString()
    val continueLabel =
        if (state.bookProgress[targetId] != null) {
            stringResource(Res.string.series_continue_book, positionLabel)
        } else {
            stringResource(Res.string.series_start_book, positionLabel)
        }

    Row(
        modifier =
            modifier
                .height(58.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { onBookClick(targetId.value) }
                .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
        Spacer(Modifier.width(10.dp))
        Text(
            text = continueLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

// endregion

// region book rows

@Composable
private fun BooksSectionHeader(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(Res.string.series_books_in_series),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
        CountBadge(count)
    }
}

@Composable
private fun CountBadge(count: Int) {
    Box(
        modifier =
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun SeriesBookRow(
    book: BookListItem,
    positionLabel: String,
    finished: Boolean,
    progress: Float?,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val titleColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val subColor =
        if (highlighted) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(rowColor)
                .clickable(onClick = onClick)
                .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box {
            BookCoverImage(
                bookId = book.id.value,
                coverPath = book.coverPath,
                coverHash = book.coverHash,
                contentDescription = book.title,
                title = book.title,
                author = book.authors.firstOrNull()?.name,
                modifier = Modifier.size(68.dp).clip(RoundedCornerShape(12.dp)),
            )
            if (finished) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 5.dp, y = 5.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.series_book_position, positionLabel),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            if (progress != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                    )
                    Text(
                        text = stringResource(Res.string.series_progress_duration, (progress * 100).toInt(), book.formatDuration()),
                        style = MaterialTheme.typography.labelMedium,
                        color = subColor,
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    text = if (finished) "${book.formatDuration()} · Finished" else book.formatDuration(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = subColor,
                )
            }
        }

        BookRowAction(finished = finished, highlighted = highlighted)
    }
}

@Composable
private fun BookRowAction(
    finished: Boolean,
    highlighted: Boolean,
) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val tint = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val icon =
        when {
            highlighted -> Icons.Default.GraphicEq
            finished -> Icons.Default.Replay
            else -> Icons.Default.PlayArrow
        }
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
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
