package com.calypsan.listenup.client.features.metadata

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion
import com.calypsan.listenup.client.presentation.metadata.CoverEntry
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
 * The cover tile shown as chosen is the cover Apply writes: one tile is always selected, "Current cover"
 * is selected exactly when Apply keeps it, and the row names the real source of the chosen cover.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class MatchPreviewCoverChoiceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chosen = mutableListOf<String>()
    private var keptCurrent = 0

    private fun show(
        appliedCover: CoverEntry?,
        currentCoverPath: String? = "/covers/b1.jpg",
    ) {
        composeRule.setContent {
            MaterialTheme {
                MatchPreviewScreen(
                    currentBook = currentBook(currentCoverPath),
                    newMetadata = MATCH,
                    selections = MetadataSelections(cover = appliedCover != null),
                    isApplying = false,
                    applyError = null,
                    previewNotFound = false,
                    selectedRegion = MetadataLocale.DEFAULT,
                    coverOptions = listOf(ITUNES, AUDIBLE),
                    isLoadingCovers = false,
                    appliedCover = appliedCover,
                    onSelectCover = { chosen += it },
                    onKeepCurrentCover = { keptCurrent++ },
                    chapterSuggestion = ChapterSuggestion.Unavailable,
                    onReviewChapters = {},
                    fallbackSources = emptyMap(),
                    contributingSources = listOf("Audible", "iTunes"),
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

    // The region chips are selectable too; a cover tile is told apart by the artwork it carries.
    private val isCoverTile =
        isSelectable() and (hasContentDescription("Current cover") or hasContentDescription("Cover from", substring = true))

    private fun assertOnlySelected(contentDescription: String) {
        composeRule.onAllNodes(isCoverTile and isSelected()).assertCountEquals(1)
        composeRule.onNode(isSelectable() and hasContentDescription(contentDescription)).assertIsSelected()
    }

    @Test
    fun `the applied candidate is the one selected tile, and the row names its source`() {
        show(appliedCover = ITUNES)

        assertOnlySelected("Cover from iTunes")
        composeRule.onNodeWithText("iTunes · 3000×3000").assertExists()
    }

    @Test
    fun `keeping the current cover selects only the Current tile`() {
        show(appliedCover = null)

        assertOnlySelected("Current cover")
    }

    @Test
    fun `a book with no artwork still offers Current, so keeping it is visibly selected`() {
        show(appliedCover = null, currentCoverPath = null)

        assertOnlySelected("Current cover")
    }

    @Test
    fun `tapping Current keeps the cover, and tapping a candidate chooses exactly that URL`() {
        show(appliedCover = ITUNES)

        composeRule.onNode(isSelectable() and hasContentDescription("Current cover")).performClick()
        composeRule.onNode(isSelectable() and hasContentDescription("Cover from Audible")).performClick()

        keptCurrent shouldBe 1
        chosen shouldBe listOf(AUDIBLE.url)
    }

    private companion object {
        val ITUNES = CoverEntry(url = "https://itunes/cover.jpg", label = "iTunes", resolution = "3000×3000")
        val AUDIBLE = CoverEntry(url = "https://audible/cover.jpg", label = "Audible", resolution = null)

        fun currentBook(coverPath: String?) =
            BookDetail(
                id = BookId("b1"),
                libraryId = LibraryId("lib"),
                folderId = FolderId("folder"),
                title = "Project Hail Mary",
                authors = emptyList(),
                narrators = emptyList(),
                duration = 1_200_000L,
                coverPath = coverPath,
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
                authors = emptyList(),
                narrators = emptyList(),
                series = emptyList(),
                genres = emptyList(),
                coverUrl = AUDIBLE.url,
                coverUrlMaxSize = ITUNES.url,
            )
    }
}
