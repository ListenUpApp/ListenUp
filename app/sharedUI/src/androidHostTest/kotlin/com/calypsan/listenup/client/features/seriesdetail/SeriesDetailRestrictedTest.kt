package com.calypsan.listenup.client.features.seriesdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.calypsan.listenup.client.design.components.LocalRestrictedBookIds
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import kotlin.time.Duration.Companion.hours
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Series detail's two book cards — the phone row and the wide grid card — wear the lock on a
 * restricted book, and only on it: one restricted book of two is exactly one lock.
 */
@RunWith(RobolectricTestRunner::class)
class SeriesDetailRestrictedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = TALL_PHONE)
    fun `the phone row locks the restricted book only`() {
        composeRule.setContent {
            Locked {
                NarrowSeriesDetailContent(
                    state = READY,
                    onBackClick = {},
                    onBookClick = {},
                    onContributorClick = {},
                    onShowAuthors = {},
                    onEditClick = {},
                )
            }
        }
        composeRule.onAllNodesWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `the wide grid card locks the restricted book only`() {
        composeRule.setContent {
            Locked {
                WideSeriesDetailContent(
                    state = READY,
                    onBackClick = {},
                    onBookClick = {},
                    onContributorClick = {},
                    onShowAuthors = {},
                    onEditClick = {},
                )
            }
        }
        composeRule.onAllNodesWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertCountEquals(1)
    }

    @Composable
    private fun Locked(content: @Composable () -> Unit) {
        MaterialTheme {
            CompositionLocalProvider(LocalRestrictedBookIds provides setOf("restricted"), content = content)
        }
    }

    private companion object {
        const val TALL_PHONE = "w400dp-h4000dp"
        const val RESTRICTED_A11Y = "In a collection, so only people it is shared with can see it."

        fun book(id: String) =
            BookListItem(
                id = BookId(id),
                title = "Book $id",
                coverPath = "/tmp/cover-$id.webp",
                authors = emptyList(),
                narrators = emptyList(),
                duration = 1.hours.inWholeMilliseconds,
                libraryId = LibraryId("lib1"),
                folderId = FolderId("folder1"),
                addedAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            )

        val READY =
            SeriesDetailUiState.Ready(
                seriesId = "s1",
                seriesName = "Mistborn",
                seriesDescription = null,
                seriesAuthors = emptyList(),
                seriesNarrator = null,
                coverPath = null,
                featuredBookId = null,
                totalDuration = 2.hours,
                books = listOf(book("restricted"), book("open")),
                bookProgress = emptyMap(),
                finishedBookIds = emptySet(),
                resumeTarget = null,
            )
    }
}
