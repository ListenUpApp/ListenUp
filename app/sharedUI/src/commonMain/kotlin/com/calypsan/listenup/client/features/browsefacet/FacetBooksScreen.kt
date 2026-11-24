package com.calypsan.listenup.client.features.browsefacet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.features.genredestination.FacetHeroScaffold
import com.calypsan.listenup.client.features.genredestination.EmptyFacetMessage
import com.calypsan.listenup.client.features.genredestination.FacetSectionHeader
import com.calypsan.listenup.client.features.genredestination.FacetHeroInk
import com.calypsan.listenup.client.features.genredestination.HeroStatChip
import com.calypsan.listenup.client.features.genredestination.toImageVector
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetUiState
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetViewModel
import com.calypsan.listenup.client.presentation.genredestination.FacetIdentity
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.browse_facet_books_label
import listenup.composeapp.generated.resources.browse_facet_kind_mood
import listenup.composeapp.generated.resources.browse_facet_kind_tag
import listenup.composeapp.generated.resources.browse_facet_no_books_mood
import listenup.composeapp.generated.resources.browse_facet_no_books_tag
import listenup.composeapp.generated.resources.browse_facet_total_label
import listenup.composeapp.generated.resources.browse_facet_unavailable_mood
import listenup.composeapp.generated.resources.browse_facet_unavailable_tag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.client.design.components.FloatingBackButton

/**
 * Facet-browse screen — every book carrying a tapped Tag or Mood chip, the flat-facet analogue of
 * the genre-browse screen.
 *
 * The layout mirrors [com.calypsan.listenup.client.features.genredestination.GenreDestinationScreen]:
 * a hue-gradient hero as the grid's first (full-span) item — carrying a scalloped facet medallion,
 * a kind eyebrow ("Tag" / "Mood"), the facet name, and two stat chips (book count + total length) —
 * followed by the "{n} audiobooks" section header and an [GridCells.Adaptive] grid of [BookCard]s
 * that reflows continuously with width (compact phone -> wide tablet). The hero is deliberately
 * *flat*: no breadcrumb, no sub-genre toggle, no sort control — those only make sense for genres'
 * hierarchical scope. The hue and icon are derived from the facet's name via
 * [FacetIdentity.hue] / [FacetIdentity.icon] (the same deterministic identity genres use), and the
 * chrome itself is [FacetHeroScaffold] — the exact component the genre hero renders with, so the two
 * destination pages never drift into look-alike-but-different heroes.
 *
 * @param facetName The facet's display name, carried by the route so the hero renders immediately
 *   while the Room observation hydrates the (authoritative) name and book set.
 */
@Composable
fun FacetBooksScreen(
    kind: FacetKind,
    facetId: String,
    facetName: String,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    viewModel: BrowseFacetViewModel,
) {
    LaunchedEffect(kind, facetId) {
        viewModel.load(kind, facetId)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when (val current = state) {
            BrowseFacetUiState.Loading -> {
                // Render the hero immediately from the route-supplied name, so the identity is
                // on-screen while Room hydrates the book set below the spinner.
                Column(modifier = Modifier.fillMaxSize()) {
                    FacetHero(kind = kind, facetName = facetName, bookCount = null, onBackClick = onBackClick)
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ListenUpLoadingIndicator()
                    }
                }
            }

            is BrowseFacetUiState.NotFound -> {
                Text(
                    text = unavailableLabel(current.kind),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center),
                )
                FloatingBackButton(onBackClick = onBackClick, modifier = Modifier.align(Alignment.TopStart))
            }

            is BrowseFacetUiState.Ready -> {
                FacetBooksContent(
                    state = current,
                    onBackClick = onBackClick,
                    onBookClick = onBookClick,
                )
            }
        }
    }
}

@Composable
private fun FacetBooksContent(
    state: BrowseFacetUiState.Ready,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = Spacing.screenMargin,
                end = Spacing.screenMargin,
                bottom = 32.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "hero", span = { GridItemSpan(maxLineSpan) }, contentType = "hero") {
            FacetHero(
                kind = state.kind,
                facetName = state.facetName,
                bookCount = state.bookCount,
                totalDurationMs = state.totalDurationMs,
                onBackClick = onBackClick,
            )
        }

        item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            FacetSectionHeader(bookCount = state.bookCount, modifier = Modifier.padding(bottom = 12.dp))
        }

        if (state.books.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                EmptyFacetMessage(
                    message =
                        when (state.kind) {
                            FacetKind.Tag -> stringResource(Res.string.browse_facet_no_books_tag)
                            FacetKind.Mood -> stringResource(Res.string.browse_facet_no_books_mood)
                        },
                )
            }
        } else {
            items(
                items = state.books,
                key = { it.id.value },
                contentType = { "book" },
            ) { book ->
                BookCard(
                    cover = book.toCoverModel(),
                    onClick = { onBookClick(book.id.value) },
                    duration = book.formatDuration(),
                )
            }
        }
    }
}

/**
 * Facet hero body: the kind eyebrow, the facet name, and stat chips — rendered inside the shared
 * [FacetHeroScaffold] chrome. Flat by design: no breadcrumb, no trailing actions. Stat chips render
 * only once [bookCount] is known (i.e. after the Room observation resolves).
 */
@Composable
private fun FacetHero(
    kind: FacetKind,
    facetName: String,
    bookCount: Int?,
    onBackClick: () -> Unit,
    totalDurationMs: Long = 0L,
) {
    FacetHeroScaffold(
        hue = FacetIdentity.hue(facetName),
        icon = FacetIdentity.icon(facetName).toImageVector(),
        onBackClick = onBackClick,
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = kindLabel(kind).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = FacetHeroInk.copy(alpha = 0.85f),
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = facetName,
            style =
                MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                ),
            color = FacetHeroInk,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )

        if (bookCount != null) {
            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroStatChip(
                    icon = ListenUpIcons.MenuBook,
                    value = bookCount.toString(),
                    label = stringResource(Res.string.browse_facet_books_label),
                )
                HeroStatChip(
                    icon = ListenUpIcons.Schedule,
                    value = DurationFormatter.hoursMinutesCompact(totalDurationMs.milliseconds),
                    label = stringResource(Res.string.browse_facet_total_label),
                )
            }
        }
    }
}

@Composable
private fun kindLabel(kind: FacetKind): String =
    when (kind) {
        FacetKind.Tag -> stringResource(Res.string.browse_facet_kind_tag)
        FacetKind.Mood -> stringResource(Res.string.browse_facet_kind_mood)
    }

@Composable
private fun unavailableLabel(kind: FacetKind): String =
    when (kind) {
        FacetKind.Tag -> stringResource(Res.string.browse_facet_unavailable_tag)
        FacetKind.Mood -> stringResource(Res.string.browse_facet_unavailable_mood)
    }
