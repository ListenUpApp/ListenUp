package com.calypsan.listenup.client.features.browsefacet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetUiState
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.browse_facet_books_label
import listenup.composeapp.generated.resources.browse_facet_kind_mood
import listenup.composeapp.generated.resources.browse_facet_kind_tag
import listenup.composeapp.generated.resources.browse_facet_no_books_mood
import listenup.composeapp.generated.resources.browse_facet_no_books_tag
import listenup.composeapp.generated.resources.browse_facet_total_label
import listenup.composeapp.generated.resources.browse_facet_unavailable_mood
import listenup.composeapp.generated.resources.browse_facet_unavailable_tag
import listenup.composeapp.generated.resources.common_back
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.compose.resources.stringResource

/**
 * Facet-browse screen — every book carrying a tapped Tag or Mood chip, the flat-facet analogue of
 * the genre-browse screen.
 *
 * The layout mirrors the facet-books mockup within our design system: a facet-tinted hero (secondary
 * tone for [FacetKind.Tag], tertiary for [FacetKind.Mood], matching the Book Detail chips) carrying a
 * scalloped facet medallion, the kind label, the facet name, and two stat chips (book count + total
 * length); below it an [GridCells.Adaptive] grid of [BookCard]s that reflows continuously with width
 * (compact phone → wide tablet) per the responsive-everywhere rule.
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
                // Render the facet-tinted hero immediately from the route-supplied name, so the
                // identity is on-screen while Room hydrates the book set below the spinner.
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
        item(span = { GridItemSpan(maxLineSpan) }) {
            FacetHero(
                kind = state.kind,
                facetName = state.facetName,
                bookCount = state.bookCount,
                totalDurationMs = state.totalDurationMs,
                onBackClick = onBackClick,
            )
        }

        if (state.books.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyFacetMessage(kind = state.kind)
            }
        } else {
            items(
                items = state.books,
                key = { it.id.value },
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
 * Facet-tinted hero: a colour-blocked band echoing the facet's chip treatment, carrying the back
 * button, a scalloped facet medallion, the kind label, the facet name, and stat chips. Stat chips
 * render only once [bookCount] is known (i.e. after the Room observation resolves).
 */
@Composable
private fun FacetHero(
    kind: FacetKind,
    facetName: String,
    bookCount: Int?,
    onBackClick: () -> Unit,
    totalDurationMs: Long = 0L,
) {
    val palette = facetPalette(kind)

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                // Bleed the tint to the screen edges, undoing the grid's horizontal content padding.
                .padding(horizontal = -Spacing.screenMargin)
                .background(
                    color = palette.container,
                    shape = RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp),
                ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = Spacing.screenMargin)
                    .padding(top = 8.dp, bottom = 28.dp),
        ) {
            IconButton(
                onClick = onBackClick,
                modifier =
                    Modifier
                        .size(44.dp)
                        .background(palette.onContainer.copy(alpha = 0.08f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                    tint = palette.onContainer,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ScallopBadge(size = 96.dp, containerColor = palette.onContainer.copy(alpha = 0.12f)) {
                    Icon(
                        imageVector = palette.glyph,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(40.dp),
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                KindLabel(kind = kind, color = palette.onContainer)

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = facetName,
                    style =
                        MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                    color = palette.onContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (bookCount != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FacetStatChip(
                            icon = Icons.AutoMirrored.Outlined.MenuBook,
                            value = bookCount.toString(),
                            label = stringResource(Res.string.browse_facet_books_label),
                            contentColor = palette.onContainer,
                        )
                        FacetStatChip(
                            icon = Icons.Outlined.Schedule,
                            value = DurationFormatter.hoursMinutes(totalDurationMs.milliseconds),
                            label = stringResource(Res.string.browse_facet_total_label),
                            contentColor = palette.onContainer,
                        )
                    }
                }
            }
        }
    }
}

/** Uppercase pill carrying the facet kind, sitting under the medallion. */
@Composable
private fun KindLabel(
    kind: FacetKind,
    color: Color,
) {
    Row(
        modifier =
            Modifier
                .background(color.copy(alpha = 0.08f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = facetPalette(kind).glyph,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = kindLabel(kind).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = color,
        )
    }
}

/** A single rounded stat chip: leading icon, bold value, subdued label. */
@Composable
private fun FacetStatChip(
    icon: ImageVector,
    value: String,
    label: String,
    contentColor: Color,
) {
    Row(
        modifier =
            Modifier
                .background(contentColor.copy(alpha = 0.06f), CircleShape)
                .padding(start = 13.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor.copy(alpha = 0.85f),
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = contentColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor.copy(alpha = 0.7f),
        )
    }
}

/** Empty-state message shown when the facet exists but currently has no live books. */
@Composable
private fun EmptyFacetMessage(kind: FacetKind) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text =
                when (kind) {
                    FacetKind.Tag -> stringResource(Res.string.browse_facet_no_books_tag)
                    FacetKind.Mood -> stringResource(Res.string.browse_facet_no_books_mood)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Floating back button used on the Loading / NotFound states (which have no hero). */
@Composable
private fun FloatingBackButton(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onBackClick,
        modifier =
            modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(Res.string.common_back),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Resolved facet-tint palette + glyph, pulled live from the active expressive theme. */
private data class FacetPalette(
    val container: Color,
    val onContainer: Color,
    val accent: Color,
    val glyph: ImageVector,
)

@Composable
private fun facetPalette(kind: FacetKind): FacetPalette =
    when (kind) {
        FacetKind.Tag -> {
            FacetPalette(
                container = MaterialTheme.colorScheme.secondaryContainer,
                onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
                accent = MaterialTheme.colorScheme.secondary,
                glyph = Icons.Default.Tag,
            )
        }

        FacetKind.Mood -> {
            FacetPalette(
                container = MaterialTheme.colorScheme.tertiaryContainer,
                onContainer = MaterialTheme.colorScheme.onTertiaryContainer,
                accent = MaterialTheme.colorScheme.tertiary,
                glyph = Icons.Default.Mood,
            )
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
