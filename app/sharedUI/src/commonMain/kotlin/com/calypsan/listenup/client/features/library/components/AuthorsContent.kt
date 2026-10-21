package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.calypsan.listenup.client.design.components.BookCoverImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.ContributorCoverImage
import com.calypsan.listenup.client.design.transitions.contributorHeroKey
import com.calypsan.listenup.client.design.transitions.heroElement
import com.calypsan.listenup.client.design.components.AlphabetRailWidth
import com.calypsan.listenup.client.design.components.AlphabetScrollbar
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.design.theme.Spacing
import androidx.compose.ui.graphics.Color
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_empty_tab_description
import listenup.composeapp.generated.resources.common_no_items_yet
import listenup.composeapp.generated.resources.contributor_name_profile_image
import listenup.composeapp.generated.resources.genre_book_count
import listenup.composeapp.generated.resources.genre_books_count

/**
 * Content for the Authors tab in the Library screen.
 *
 * Displays a list of authors with their book counts.
 *
 * @param authors List of authors with book counts
 * @param sortState Current sort state (category + direction)
 * @param letters The shared letter sections over the list; empty for a non-name sort
 * @param onCategorySelected Called when user selects a new category
 * @param onDirectionToggle Called when user toggles sort direction
 * @param onAuthorClick Callback when an author is clicked
 * @param showCovers Whether each row shows its two newest covers (the wide Library, spec c7)
 * @param modifier Optional modifier
 */
@Composable
fun AuthorsContent(
    authors: List<ContributorWithBookCount>,
    sortState: SortState,
    letters: List<LetterSection>,
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    onAuthorClick: (String) -> Unit,
    showCovers: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (authors.isEmpty()) {
            AuthorsEmptyState()
        } else {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()

            // The shared letter sections: empty on a non-name sort, so no scrollbar.
            val alphabetIndex =
                remember(letters) { libraryAlphabetIndex(letters, withHeaders = true, leadingItems = 0) }
            val rows = remember(authors, letters) { letteredRows(authors, letters) { it.contributor.id.value } }

            val isScrolling by remember {
                derivedStateOf { listState.isScrollInProgress }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // The same sort control as Books and Series, at the top of the list (spec §5.1 c5).
                LibrarySortCard(
                    state = sortState,
                    categories = SortCategory.contributorCategories,
                    count = authors.size,
                    unit = if (authors.size == 1) "author" else "authors",
                    ignoreArticles = false,
                    showArticleToggle = false,
                    onCategorySelected = onCategorySelected,
                    onDirectionToggle = onDirectionToggle,
                    onToggleArticles = {},
                    visible = true,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        contentPadding =
                            PaddingValues(
                                start = Spacing.gridMargin,
                                end = Spacing.gridMargin,
                                top = 12.dp,
                                bottom = 16.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        contributorRows(
                            rows = rows,
                            onClick = onAuthorClick,
                            showCovers = showCovers,
                            reserveRail =
                                alphabetIndex != null,
                        )
                    }

                    // Alphabet scrollbar (only for name sort)
                    // Anchored to TopEnd so it stays fixed relative to content start
                    if (alphabetIndex != null) {
                        AlphabetScrollbar(
                            alphabetIndex = alphabetIndex,
                            onLetterSelected = { index ->
                                scope.launch { listState.scrollToItem(index) }
                            },
                            isScrolling = isScrolling,
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 12.dp, end = 4.dp, bottom = 0.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * An Authors or Narrators list's rows: a header before each letter on a name sort (spec L25), from
 * the shared sections, and a card per person.
 */
internal fun LazyListScope.contributorRows(
    rows: List<LetteredRow<ContributorWithBookCount>>,
    onClick: (String) -> Unit,
    showCovers: Boolean = false,
    reserveRail: Boolean = false,
) {
    items(
        items = rows,
        key = { it.key },
        contentType = { if (it is LetteredRow.Header) "header" else "person" },
    ) { row ->
        when (row) {
            is LetteredRow.Header -> {
                SectionHeader(letter = row.letter)
            }

            is LetteredRow.Entry -> {
                ContributorCard(
                    contributorWithCount = row.value,
                    onClick = { onClick(row.value.contributor.id.value) },
                    showCovers = showCovers,
                    reserveRail = reserveRail,
                )
            }
        }
    }
}

/**
 * Card displaying a contributor (author/narrator) with their book count.
 * Shows contributor image if available, otherwise displays initials.
 *
 * On a wide Library ([showCovers]) the row ends in the contributor's two newest covers (spec c7). They are decoration:
 * the row's name for TalkBack is unchanged. The avatar stays the M3E cookie (open question 6). Beside the letter index
 * ([reserveRail]) they stop short of it, so the index never sits over a cover.
 */
@Composable
internal fun ContributorCard(
    contributorWithCount: ContributorWithBookCount,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCovers: Boolean = false,
    reserveRail: Boolean = false,
) {
    val haptics = LocalHaptics.current
    val contributor = contributorWithCount.contributor

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable {
                    haptics.press()
                    onClick()
                },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar with image or initials
            Surface(
                shape = cookieScallopShape(),
                color = Color.hsl((contributor.id.value.hashCode() and 0x7FFFFFFF).rem(360).toFloat(), 0.4f, 0.65f),
                // The hero is the whole avatar, not the photo inside it. Most contributors have no
                // photo — ContributorCoverImage draws nothing at all for them, and the initials that
                // stand in are a sibling — so a photo-only shared element had nothing to animate.
                modifier =
                    Modifier
                        .size(48.dp)
                        .heroElement(contributorHeroKey(contributor.id.value), cookieScallopShape()),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    var imageLoaded by remember(contributor.id.value) { mutableStateOf(false) }

                    if (!imageLoaded) {
                        Text(
                            text = initialsOf(contributor.name),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    ContributorCoverImage(
                        contributorId = contributor.id.value,
                        imagePath = contributor.imagePath,
                        contentDescription =
                            stringResource(
                                Res.string.contributor_name_profile_image,
                                contributor.name,
                            ),
                        frame = contributor.imageFrame,
                        modifier = Modifier.fillMaxSize(),
                        onState = { state ->
                            if (state is coil3.compose.AsyncImagePainter.State.Success) {
                                imageLoaded = true
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Contributor info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contributor.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        if (contributorWithCount.bookCount == 1) {
                            stringResource(Res.string.genre_book_count, contributorWithCount.bookCount)
                        } else {
                            stringResource(Res.string.genre_books_count, contributorWithCount.bookCount)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (showCovers && contributorWithCount.previewBookIds.isNotEmpty()) {
                PreviewCovers(
                    bookIds = contributorWithCount.previewBookIds.take(PREVIEW_COVERS),
                    modifier = if (reserveRail) Modifier.padding(end = AlphabetRailWidth) else Modifier,
                )
            }
        }
    }
}

/** "Ann Leckie" → "AL"; a one-word name gives its first two letters. */
private fun initialsOf(name: String): String {
    val parts = name.trim().split("\\s+".toRegex())
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
        name.length >= 2 -> name.take(2)
        else -> name.take(1)
    }.uppercase()
}

/**
 * Up to two square covers at a people row's end; decoration under the row's own name. Each sits on a quiet tile, so a
 * book with no cover is a tile rather than a gap — web's waiting tile.
 */
@Composable
private fun PreviewCovers(
    bookIds: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.padding(start = Spacing.md).clearAndSetSemantics {},
    ) {
        bookIds.forEach { bookId ->
            // The tile under the cover: an `outline` edge (3:1 on the card, WCAG 1.4.11) and a book glyph, so a book
            // with no cover reads as a cover-shaped placeholder, as on web and iOS. A loaded cover draws over both.
            val shape = RoundedCornerShape(6.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(PREVIEW_COVER_SIZE)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                        .testTag(PREVIEW_COVER_TAG),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Book,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(PREVIEW_GLYPH_SIZE),
                )
                BookCoverImage(
                    bookId = bookId,
                    coverPath = null,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Test tag on each of a people row's preview covers. */
internal const val PREVIEW_COVER_TAG = "contributor-preview-cover"
private const val PREVIEW_COVERS = 2
private val PREVIEW_COVER_SIZE = 40.dp
private val PREVIEW_GLYPH_SIZE = 20.dp

/**
 * Empty state when no authors in library.
 */
@Composable
private fun AuthorsEmptyState() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = stringResource(Res.string.common_no_items_yet, "authors"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.library_empty_tab_description, "Authors"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
