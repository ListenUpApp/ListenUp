package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.client.presentation.bookdetail.SeriesShelf
import com.calypsan.listenup.client.presentation.bookdetail.SeriesShelfBook
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The series shelf says where to start, where to go next, and opens each book (spec §3.2.2). */
@RunWith(RobolectricTestRunner::class)
class SeriesShelfSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    // A local cover path keeps BookCoverImage on its synchronous path, clear of global Koin.
    private val shelf =
        SeriesShelf(
            seriesId = "s1",
            name = "The Stormlight Archive",
            books =
                (1..5).map { n ->
                    SeriesShelfBook(
                        id = "b$n",
                        coverPath = "/tmp/cover-b$n.webp",
                        coverHash = null,
                        sequenceLabel = "$n",
                        isThis =
                            n == 1,
                    )
                },
            thisIndex = 0,
            nextBookId = "b2",
        )

    @Test
    fun `not started, start here, and this book is marked`() {
        composeRule.setContent {
            MaterialTheme {
                SeriesShelfSection(
                    shelf,
                    ReadingStage.NotStarted,
                    onBookClick = {},
                    onSeriesClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Series · The Stormlight Archive").assertExists()
        composeRule.onNodeWithText("Book 1 of 5 · start here").assertExists()
        composeRule.onNodeWithContentDescription("Book 1, this book").assertExists()
    }

    @Test
    fun `each cover opens its book`() {
        var opened: String? = null
        composeRule.setContent {
            MaterialTheme { SeriesShelfSection(shelf, ReadingStage.Finished, onBookClick = { opened = it }, onSeriesClick = {}) }
        }
        composeRule.onNodeWithContentDescription("Book 3").performClick()
        opened shouldBe "b3"
    }

    @Test
    fun `finished points at the next book`() {
        composeRule.setContent { MaterialTheme { SeriesShelfSection(shelf, ReadingStage.Finished, onBookClick = {}, onSeriesClick = {}) } }
        composeRule.onNodeWithText("Next: book 2").assertExists()
    }

    @Test
    fun `the heading opens the series`() {
        var opened: String? = null
        composeRule.setContent {
            MaterialTheme { SeriesShelfSection(shelf, ReadingStage.InProgress, onBookClick = {}, onSeriesClick = { opened = it }) }
        }
        composeRule.onNodeWithText("Book 1 of 5").assertExists()
        composeRule.onNodeWithText("Series · The Stormlight Archive").performClick()
        opened shouldBe "s1"
    }
}
