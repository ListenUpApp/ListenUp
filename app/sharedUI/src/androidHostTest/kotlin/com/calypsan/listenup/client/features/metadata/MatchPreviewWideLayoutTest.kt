package com.calypsan.listenup.client.features.metadata

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion
import com.calypsan.listenup.client.presentation.metadata.MetadataSelections
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertRightOf
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Previewing a book match on a tablet pins the matched edition, its region and the apply action in a
 * side panel, and flows the field sections into columns beside it; on a phone the sections stack in
 * one list above the apply bar.
 */
@RunWith(RobolectricTestRunner::class)
class MatchPreviewWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the matched edition sits beside the field sections, which flow into columns`() {
        setContent()

        val sourceChip = composeRule.onNodeWithText("Audible · ${MetadataLocale.DEFAULT.displayName}")
        assertRightOf(composeRule.onNodeWithText("IDENTITY"), sourceChip)
        assertRightOf(composeRule.onNodeWithText("IDENTITY"), composeRule.onNodeWithText(APPLY))
        assertSideBySide(composeRule.onNodeWithText("IDENTITY"), composeRule.onNodeWithText("CLASSIFICATION"))
    }

    // A phone's width with a tall window: the lazy list only composes what fits, and the check is about
    // columns, not about how much of the list a real phone shows at once.
    @Test
    @Config(qualifiers = "w400dp-h2400dp")
    fun `on a phone the field sections stack in one list`() {
        setContent()

        assertStacked(composeRule.onNodeWithText("IDENTITY"), composeRule.onNodeWithText("CLASSIFICATION"))
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                MatchPreviewScreen(
                    currentBook = CURRENT_BOOK,
                    newMetadata = MATCH,
                    selections = MetadataSelections(selectedAuthors = setOf("AU1"), selectedGenres = setOf("Fantasy")),
                    isApplying = false,
                    applyError = null,
                    previewNotFound = false,
                    selectedRegion = MetadataLocale.DEFAULT,
                    coverOptions = emptyList(),
                    isLoadingCovers = false,
                    selectedCoverUrl = null,
                    onSelectCover = {},
                    chapterSuggestion = ChapterSuggestion.Unavailable,
                    onReviewChapters = {},
                    fallbackSources = emptyMap(),
                    coverSourceLabel = null,
                    coverResolution = null,
                    contributingSources = emptyList(),
                    onRegionSelected = {},
                    onToggleField = {},
                    onToggleAuthor = {},
                    onToggleNarrator = {},
                    onToggleSeries = {},
                    onToggleGenre = {},
                    onToggleMood = {},
                    onToggleTag = {},
                    onApply = {},
                    onBack = {},
                )
            }
        }
    }

    private companion object {
        const val APPLY = "Apply Selected Metadata"

        val CURRENT_BOOK =
            BookDetail(
                id = BookId("b1"),
                libraryId = LibraryId("lib"),
                folderId = FolderId("folder"),
                title = "Words of Radiance",
                authors = emptyList(),
                narrators = emptyList(),
                duration = 1_200_000L,
                coverPath = null,
                addedAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            )

        val MATCH =
            MetadataBook(
                asin = "A1",
                title = "Words of Radiance",
                subtitle = null,
                description = "The second book of the Stormlight Archive.",
                publisher = "Macmillan Audio",
                releaseDate = null,
                runtimeMinutes = null,
                language = null,
                authors = listOf(MetadataContributorRef(asin = "AU1", name = "Brandon Sanderson")),
                narrators = emptyList(),
                series = emptyList(),
                genres = listOf("Fantasy", "Epic"),
                coverUrl = null,
                coverUrlMaxSize = null,
            )
    }
}
