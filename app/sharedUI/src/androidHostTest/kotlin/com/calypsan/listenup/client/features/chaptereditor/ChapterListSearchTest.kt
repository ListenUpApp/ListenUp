package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.design.timeline.TimelineGeometry
import com.calypsan.listenup.client.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val BOOK_MS = 40_000L

/**
 * The list's search, rendered.
 *
 * [ChapterListFilterTest] covers the matching itself; this covers the thing that only shows up
 * once the filter is wired to real rows — that a narrowed list still prints each chapter's true
 * number. Numbering the rows on screen would be the easy mistake, and it would quietly relabel
 * chapter 4 as chapter 1 in front of someone about to save.
 *
 * The device is tall enough that every row composes; at the default height these counts would be
 * measuring the viewport instead of the filter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h2400dp")
class ChapterListSearchTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chapters =
        listOf("Prologue", "The Way of Kings", "Words of Radiance", "Oathbringer")
            .mapIndexed { i, t ->
                Chapter(id = "c$i", title = t, duration = 10_000L, startTime = i * 10_000L)
            }

    private fun render(query: String) {
        composeRule.setContent {
            MaterialTheme {
                ChapterEditorContent(
                    chapters = chapters.numbered(),
                    bookDurationMs = BOOK_MS,
                    geometry = TimelineGeometry(0L, BOOK_MS, 1_000f),
                    isWide = true,
                    selectedChapterId = null,
                    playheadMs = null,
                    onSelect = {},
                    onNudge = { _, _ -> },
                    onSnapToPlayhead = {},
                    onToggleLock = {},
                    onMore = {},
                    onSeekFraction = {},
                    query = query,
                    onQueryChange = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `an empty query shows the whole book`() {
        render("")

        assertEquals(1, composeRule.onAllNodesWithText("Prologue").fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("Oathbringer").fetchSemanticsNodes().size)
    }

    @Test
    fun `A FILTERED ROW STILL SHOWS ITS TRUE NUMBER`() {
        // A partial query, so the search field's own text is not itself a match for the row title
        // — otherwise the assertion below counts the text box as a second result.
        render("Oath")

        composeRule.onNodeWithText("Oathbringer").assertIsDisplayed()
        composeRule.onNodeWithText("4").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Prologue").fetchSemanticsNodes().size)
    }

    @Test
    fun `a query matching nothing says so instead of showing an empty pane`() {
        render("Mistborn")

        composeRule.onNodeWithText("No chapters match", substring = true).assertIsDisplayed()
    }
}
