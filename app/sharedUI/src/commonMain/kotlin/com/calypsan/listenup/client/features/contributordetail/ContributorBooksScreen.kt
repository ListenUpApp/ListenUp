package com.calypsan.listenup.client.features.contributordetail

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.features.contributoredit.components.ContributorColorScheme
import com.calypsan.listenup.client.features.contributoredit.components.rememberContributorColorScheme
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksUiState
import com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksViewModel
import com.calypsan.listenup.client.presentation.contributordetail.SeriesGroup
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_book_count
import listenup.composeapp.generated.resources.common_books_count
import listenup.composeapp.generated.resources.contributor_books_by_count
import listenup.composeapp.generated.resources.contributor_books_by_s_count
import listenup.composeapp.generated.resources.contributor_other_books

/**
 * Deep Dive screen - explore all books by a contributor in a specific role.
 *
 * Design Philosophy: "A clean, organized grid that feels like a continuation of the profile."
 * Features a condensed gradient header that echoes the Artist Portfolio aesthetic,
 * followed by an organized grid of books grouped by series.
 *
 * Layout:
 * 1. Condensed Hero Header (200dp) - Role name with gradient backdrop
 * 2. Series Sections - Horizontal carousels per series
 * 3. Standalone Grid - Responsive grid for non-series books
 */
@Composable
fun ContributorBooksScreen(
    contributorId: String,
    role: String,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    viewModel: ContributorBooksViewModel = koinViewModel(),
) {
    LaunchedEffect(contributorId, role) {
        viewModel.loadBooks(contributorId, role)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = state) {
            ContributorBooksUiState.Idle, ContributorBooksUiState.Loading -> {
                ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
            }

            is ContributorBooksUiState.Error -> {
                Text(
                    text = current.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            is ContributorBooksUiState.Ready -> {
                DeepDiveContent(
                    contributorId = contributorId,
                    state = current,
                    onBackClick = onBackClick,
                    onBookClick = onBookClick,
                )
            }
        }
    }
}

// =============================================================================
// MAIN CONTENT LAYOUT
// =============================================================================

@Composable
private fun DeepDiveContent(
    contributorId: String,
    state: ContributorBooksUiState.Ready,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
) {
    val colorScheme = rememberContributorColorScheme(contributorId)
    val surfaceColor = MaterialTheme.colorScheme.surface

    // If we have series groups, use a hybrid layout
    // Otherwise, use a pure grid layout
    if (state.seriesGroups.isNotEmpty()) {
        HybridLayout(
            state = state,
            colorScheme = colorScheme,
            surfaceColor = surfaceColor,
            onBackClick = onBackClick,
            onBookClick = onBookClick,
        )
    } else {
        GridOnlyLayout(
            state = state,
            colorScheme = colorScheme,
            surfaceColor = surfaceColor,
            onBackClick = onBackClick,
            onBookClick = onBookClick,
        )
    }
}

/**
 * Hybrid layout: Header + Series carousels + Standalone grid
 * Used when there are both series and standalone books.
 */
@Composable
private fun HybridLayout(
    state: ContributorBooksUiState.Ready,
    colorScheme: ContributorColorScheme,
    surfaceColor: Color,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // Condensed header
        item {
            CondensedHeader(
                roleDisplayName = state.roleDisplayName,
                contributorName = state.contributorName,
                totalBooks = state.totalBooks,
                colorScheme = colorScheme,
                surfaceColor = surfaceColor,
                onBackClick = onBackClick,
            )
        }

        // Series sections with horizontal carousels
        items(
            items = state.seriesGroups,
            key = { it.seriesName },
        ) { seriesGroup ->
            SeriesCarouselSection(
                seriesGroup = seriesGroup,
                bookProgress = state.bookProgress,
                onBookClick = onBookClick,
            )
        }

        // Standalone books section
        if (state.hasStandaloneBooks) {
            item {
                StandaloneBooksGrid(
                    books = state.standaloneBooks,
                    bookProgress = state.bookProgress,
                    onBookClick = onBookClick,
                )
            }
        }
    }
}

/**
 * Grid-only layout: Header + Full responsive grid
 * Used when all books are standalone (no series).
 */
@Composable
private fun GridOnlyLayout(
    state: ContributorBooksUiState.Ready,
    colorScheme: ContributorColorScheme,
    surfaceColor: Color,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = 24.dp,
                end = 24.dp,
                bottom = 32.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Full-width header
        item(span = { GridItemSpan(maxLineSpan) }) {
            CondensedHeader(
                roleDisplayName = state.roleDisplayName,
                contributorName = state.contributorName,
                totalBooks = state.totalBooks,
                colorScheme = colorScheme,
                surfaceColor = surfaceColor,
                onBackClick = onBackClick,
            )
        }

        // All books in a responsive grid
        items(
            items = state.standaloneBooks,
            key = { it.id.value },
        ) { book ->
            BookCard(
                cover = book.toCoverModel(),
                onClick = { onBookClick(book.id.value) },
                duration = book.formatDuration(),
                progress = state.bookProgress[book.id],
            )
        }
    }
}

// =============================================================================
// CONDENSED HEADER - Role identity with gradient backdrop
// =============================================================================

@Composable
private fun CondensedHeader(
    roleDisplayName: String,
    contributorName: String,
    totalBooks: Int,
    colorScheme: ContributorColorScheme,
    surfaceColor: Color,
    onBackClick: () -> Unit,
) {
    // Create a smooth gradient that echoes the Artist Portfolio
    val gradientColors =
        listOf(
            colorScheme.primaryDark,
            colorScheme.primaryMuted.copy(alpha = 0.6f),
            surfaceColor.copy(alpha = 0.95f),
            surfaceColor,
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Brush.verticalGradient(gradientColors)),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp),
        ) {
            // Floating back button
            IconButton(
                onClick = onBackClick,
                modifier =
                    Modifier
                        .padding(top = 8.dp)
                        .size(48.dp)
                        .background(
                            color = surfaceColor.copy(alpha = 0.5f),
                            shape = CircleShape,
                        ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Role title and contributor name
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Text(
                    text = roleDisplayName,
                    style =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text =
                        if (totalBooks == 1) {
                            stringResource(Res.string.contributor_books_by_count, totalBooks, contributorName)
                        } else {
                            stringResource(Res.string.contributor_books_by_s_count, totalBooks, contributorName)
                        },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// =============================================================================
// SERIES CAROUSEL SECTION
// =============================================================================

@Composable
private fun SeriesCarouselSection(
    seriesGroup: SeriesGroup,
    bookProgress: Map<BookId, Float>,
    onBookClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = 16.dp),
    ) {
        // Series header
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                text = seriesGroup.seriesName,
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            Text(
                text =
                    if (seriesGroup.books.size == 1) {
                        stringResource(Res.string.common_book_count, seriesGroup.books.size)
                    } else {
                        stringResource(Res.string.common_books_count, seriesGroup.books.size)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Horizontal carousel
        BrowseCarousel(
            items = seriesGroup.books,
            itemWidth = 150.dp,
            itemSpacing = 16.dp,
            contentPadding = PaddingValues(horizontal = 24.dp),
            key = { it.id.value },
        ) { book ->
            BookCard(
                cover = book.toCoverModel(),
                onClick = { onBookClick(book.id.value) },
                duration = book.formatDuration(),
                progress = bookProgress[book.id],
                cardWidth = 150.dp,
            )
        }
    }
}

// =============================================================================
// STANDALONE BOOKS GRID
// =============================================================================

@Composable
private fun StandaloneBooksGrid(
    books: List<BookListItem>,
    bookProgress: Map<BookId, Float>,
    onBookClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = 16.dp),
    ) {
        // Section header
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(Res.string.contributor_other_books),
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            Text(
                text =
                    if (books.size == 1) {
                        stringResource(Res.string.common_book_count, books.size)
                    } else {
                        stringResource(Res.string.common_books_count, books.size)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Responsive grid using chunked rows
        // This provides a clean grid while staying inside LazyColumn
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Calculate items per row based on available space
            // Using 3 for phones, works well with 24dp horizontal padding
            books.chunked(3).forEach { rowBooks ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    rowBooks.forEach { book ->
                        BookCard(
                            cover = book.toCoverModel(),
                            onClick = { onBookClick(book.id.value) },
                            duration = book.formatDuration(),
                            progress = bookProgress[book.id],
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Fill remaining slots with spacers
                    repeat(3 - rowBooks.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
