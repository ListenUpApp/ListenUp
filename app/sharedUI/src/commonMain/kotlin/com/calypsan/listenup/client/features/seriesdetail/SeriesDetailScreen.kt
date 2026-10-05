package com.calypsan.listenup.client.features.seriesdetail

import com.calypsan.listenup.client.design.haptics.LocalHaptics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.FannedDeck
import com.calypsan.listenup.client.design.components.FannedDeckCover
import com.calypsan.listenup.client.design.components.HeroNavRow
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.features.contributors.ClickableContributorLine
import com.calypsan.listenup.client.features.contributors.FullCastSheet
import com.calypsan.listenup.client.presentation.bookdetail.HERO_CONTRIBUTOR_FOLD_LIMIT
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_authors
import listenup.composeapp.generated.resources.book_detail_cast_count_authors
import listenup.composeapp.generated.resources.book_detail_other_authors
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.series_book_position
import listenup.composeapp.generated.resources.series_books_in_series
import listenup.composeapp.generated.resources.series_continue_book
import listenup.composeapp.generated.resources.series_duration_finished
import listenup.composeapp.generated.resources.series_edit_series
import listenup.composeapp.generated.resources.series_label
import listenup.composeapp.generated.resources.series_progress_duration
import listenup.composeapp.generated.resources.series_start_book
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.lazy.grid.LazyGridScope
import com.calypsan.listenup.client.features.seriesdetail.components.SeriesBookLayout
import com.calypsan.listenup.client.features.seriesdetail.components.SeriesBookListActions
import com.calypsan.listenup.client.features.seriesdetail.components.StickyHeadings
import com.calypsan.listenup.client.features.seriesdetail.components.SeriesBreadcrumb
import com.calypsan.listenup.client.features.seriesdetail.components.SubSeriesSection
import com.calypsan.listenup.client.features.seriesdetail.components.seriesBookList
import com.calypsan.listenup.client.features.seriesedit.components.AddSubSeriesSheet
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import listenup.composeapp.generated.resources.series_count_books
import com.calypsan.listenup.client.design.theme.ContentShapes
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.client.design.theme.HeroInk
import com.calypsan.listenup.client.design.components.RestrictedBookMarker

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
    onContributorClick: (String) -> Unit,
    /** Opens another series — a breadcrumb crumb, a sub-series card, a group heading. Pushed, so Back returns here. */
    onSeriesClick: (String) -> Unit,
    viewModel: SeriesDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(seriesId) { viewModel.loadSeries(seriesId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val addSubSeries by viewModel.addSubSeries.collectAsStateWithLifecycle()

    // The series-authors roster sheet, opened from the folded "{lead}, N other authors" hero line.
    var showAuthorsSheet by remember { mutableStateOf(false) }

    // Immersive: let the color hero bleed behind the status bar (HeroNavRow self-insets its controls).
    ListenUpScaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { paddingValues ->
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
                    val hierarchy =
                        SeriesPageHierarchyActions(
                            onSeriesClick = onSeriesClick,
                            onToggleSection = viewModel::toggleSection,
                            onAddSubSeries = { viewModel.onAddSubSeriesEvent(AddSubSeriesEvent.Opened) },
                        )
                    val wide =
                        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
                            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
                        )
                    if (wide) {
                        WideSeriesDetailContent(
                            state = current,
                            onBackClick = onBackClick,
                            onBookClick = onBookClick,
                            onContributorClick = onContributorClick,
                            onShowAuthors = { showAuthorsSheet = true },
                            onEditClick = { onEditClick(seriesId) },
                            hierarchy = hierarchy,
                        )
                    } else {
                        NarrowSeriesDetailContent(
                            state = current,
                            onBackClick = onBackClick,
                            onBookClick = onBookClick,
                            onContributorClick = onContributorClick,
                            onShowAuthors = { showAuthorsSheet = true },
                            onEditClick = { onEditClick(seriesId) },
                            hierarchy = hierarchy,
                        )
                    }

                    AddSubSeriesSheet(state = addSubSeries, onEvent = viewModel::onAddSubSeriesEvent)

                    if (showAuthorsSheet && current.seriesAuthors.isNotEmpty()) {
                        FullCastSheet(
                            title = stringResource(Res.string.book_detail_authors),
                            countText =
                                stringResource(
                                    Res.string.book_detail_cast_count_authors,
                                    current.seriesAuthors.size,
                                ),
                            contributors = current.seriesAuthors,
                            onContributorClick = onContributorClick,
                            onDismiss = { showAuthorsSheet = false },
                        )
                    }
                }
            }
        }
    }
}

// region layouts

/** What a series page's hierarchy parts do: open another series, fold a group, add a sub-series. */
internal class SeriesPageHierarchyActions(
    val onSeriesClick: (String) -> Unit = {},
    val onToggleSection: (String) -> Unit = {},
    val onAddSubSeries: () -> Unit = {},
)

@Composable
internal fun NarrowSeriesDetailContent(
    state: SeriesDetailUiState.Ready,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onContributorClick: (String) -> Unit,
    onShowAuthors: () -> Unit,
    onEditClick: () -> Unit,
    hierarchy: SeriesPageHierarchyActions = SeriesPageHierarchyActions(),
    stickyTopInset: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
) {
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(1),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SeriesColorHero(
                state = state,
                onBackClick = onBackClick,
                onContributorClick = onContributorClick,
                onShowAuthors = onShowAuthors,
                onEditClick = onEditClick,
                onSeriesClick = hierarchy.onSeriesClick,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ContinueButton(
                state = state,
                onBookClick = onBookClick,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 6.dp),
            )
        }
        subSeriesItem(state, hierarchy, Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        seriesBookList(
            state = state,
            layout = SeriesBookLayout.Rows,
            actions = SeriesBookListActions(onBookClick, hierarchy.onSeriesClick, hierarchy.onToggleSection),
            gutter = 14.dp,
            sticky = StickyHeadings(gridState, stickyTopInset),
        )
    }
}

/** The "Sub-series" cards, on a page that has any (or an editor, who can add the first). */
private fun LazyGridScope.subSeriesItem(
    state: SeriesDetailUiState.Ready,
    hierarchy: SeriesPageHierarchyActions,
    modifier: Modifier,
) {
    if (state.childSeries.isEmpty() && !state.canEditHierarchy) return
    item(key = "sub-series", span = { GridItemSpan(maxLineSpan) }) {
        SubSeriesSection(
            childSeries = state.childSeries,
            canAddSubSeries = state.canEditHierarchy,
            isOnline = state.isOnline,
            onSeriesClick = hierarchy.onSeriesClick,
            onAddSubSeries = hierarchy.onAddSubSeries,
            modifier = modifier,
        )
    }
}

@Composable
internal fun WideSeriesDetailContent(
    state: SeriesDetailUiState.Ready,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onContributorClick: (String) -> Unit,
    onShowAuthors: () -> Unit,
    onEditClick: () -> Unit,
    hierarchy: SeriesPageHierarchyActions = SeriesPageHierarchyActions(),
) {
    val gridState = rememberLazyGridState()
    // The grid starts below the page's 20 dp margin; a heading pins below whatever of the status bar
    // still reaches past it.
    val stickyTopInset =
        (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - WIDE_PAGE_MARGIN).coerceAtLeast(0.dp)
    Row(
        modifier = Modifier.fillMaxSize().padding(WIDE_PAGE_MARGIN),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Left: color-blocked hero panel with the Continue action pinned at the bottom. A proportional
        // width (not a fixed 420 dp) leaves the book grid real room on foldable inner displays.
        Box(
            modifier =
                Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .clip(MaterialTheme.shapes.large)
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
                HeroBody(
                    state = state,
                    onContributorClick = onContributorClick,
                    onShowAuthors = onShowAuthors,
                    onSeriesClick = hierarchy.onSeriesClick,
                )
                Spacer(Modifier.height(Spacing.xl))
                ContinueButton(state = state, onBookClick = onBookClick, modifier = Modifier.fillMaxWidth())
            }
        }

        // Right: "Books in series" as a cover-card grid that flows with width (2 columns on a
        // foldable, more on desktop) — reads as a shelf rather than crushing the horizontal rows.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(0.6f).fillMaxHeight(),
            state = gridState,
        ) {
            subSeriesItem(state, hierarchy, Modifier.padding(bottom = 8.dp))
            seriesBookList(
                state = state,
                layout = SeriesBookLayout.Cards,
                actions = SeriesBookListActions(onBookClick, hierarchy.onSeriesClick, hierarchy.onToggleSection),
                gutter = 0.dp,
                sticky = StickyHeadings(gridState, stickyTopInset),
            )
        }
    }
}

private val WIDE_PAGE_MARGIN = 20.dp

// endregion
