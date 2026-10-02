package com.calypsan.listenup.client.features.metadata

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion
import com.calypsan.listenup.client.presentation.metadata.MetadataSelections
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hardcover in the Android match preview (#1542): its moods carry a "from Hardcover" chip and toggle like any
 * mood, and the genres it added sit under their own "from Hardcover" chip beside the match's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class MatchPreviewHardcoverTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val moodsToggled = mutableListOf<String>()

    private fun show() {
        composeRule.setContent {
            MaterialTheme {
                MatchPreviewScreen(
                    currentBook = CURRENT_BOOK,
                    newMetadata = MATCH,
                    selections =
                        MetadataSelections(
                            selectedGenres = setOf("Science Fiction", "Space Opera"),
                            selectedMoods = setOf("Hopeful", "Funny"),
                        ),
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
                    fallbackSources = mapOf(BookField.MOODS to "Hardcover"),
                    genreSources = mapOf("Space Opera" to "Hardcover"),
                    coverSourceLabel = null,
                    coverResolution = null,
                    contributingSources = listOf("Audible", "Hardcover"),
                    onRegionSelected = {},
                    onToggleField = {},
                    onToggleAuthor = {},
                    onToggleNarrator = {},
                    onToggleSeries = {},
                    onToggleGenre = {},
                    onToggleMood = { moodsToggled += it },
                    onToggleTag = {},
                    onApply = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `Hardcover's moods and its added genre each carry a from-Hardcover chip, the match's own genre none`() {
        show()

        composeRule.onAllNodesWithText("from Hardcover").assertCountEquals(2)
    }

    @Test
    fun `a Hardcover mood toggles like any mood`() {
        show()

        composeRule.onNodeWithText("Hopeful").performClick()

        moodsToggled shouldBe listOf("Hopeful")
    }

    private companion object {
        val CURRENT_BOOK =
            BookDetail(
                id = BookId("b1"),
                libraryId = LibraryId("lib"),
                folderId = FolderId("folder"),
                title = "Project Hail Mary",
                authors = emptyList(),
                narrators = emptyList(),
                duration = 1_200_000L,
                coverPath = null,
                addedAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            )

        val MATCH =
            MetadataBook(
                asin = "B08G9RZBTT",
                title = "Project Hail Mary",
                subtitle = null,
                description = null,
                publisher = null,
                releaseDate = null,
                runtimeMinutes = null,
                language = null,
                authors = listOf(MetadataContributorRef(asin = "AU1", name = "Andy Weir")),
                narrators = emptyList(),
                series = emptyList(),
                genres = listOf("Science Fiction", "Space Opera"),
                moods = listOf("Hopeful", "Funny"),
                coverUrl = null,
                coverUrlMaxSize = null,
            )
    }
}
