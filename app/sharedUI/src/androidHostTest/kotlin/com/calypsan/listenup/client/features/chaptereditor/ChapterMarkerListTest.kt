package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterMarkerTier
import com.calypsan.listenup.client.presentation.chaptereditor.PartSuggestion
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineGeometry
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineLane
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val BOOK_MS = 60_000L

/**
 * Markers in the editor's list: a header row above the chapter that carries one, the offer above
 * everything, and neither getting in the way of search or of a book that has no markers at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h2400dp")
class ChapterMarkerListTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private val marked =
        listOf(
            Chapter(id = "c1", title = "Credits", duration = 15_000L, startTime = 0L),
            Chapter(id = "c2", title = "Dawn", duration = 15_000L, startTime = 15_000L, partTitle = "The First Part"),
            Chapter(id = "c3", title = "Dusk", duration = 15_000L, startTime = 30_000L, bookTitle = "Omnibus One"),
        )

    private fun render(
        chapters: List<Chapter> = marked,
        query: String = "",
        suggestion: PartSuggestion? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                ChapterEditorContent(
                    chapters = chapters.numbered(),
                    bookDurationMs = BOOK_MS,
                    lane = TimelineLane(TimelineGeometry(windowStartMs = 0L, windowEndMs = BOOK_MS, widthPx = 1_000f)),
                    onLaneChange = {},
                    isWide = true,
                    selectedChapterId = null,
                    playheadMs = { null },
                    onSelect = {},
                    rowEdits =
                        ChapterRowEdits(
                            onNudge = { _, _ -> },
                            onSnapToPlayhead = {},
                            onToggleLock = {},
                            onEditTime = {},
                        ),
                    rowMenu = ChapterRowMenuActions({}, {}, null, {}),
                    query = query,
                    partSuggestion = suggestion,
                    markers =
                        ChapterMarkerActions(
                            onEdit = { id, tier -> calls += "edit:$id:$tier" },
                            onAcceptSuggestion = { calls += "accept" },
                            onDismissSuggestion = { calls += "dismiss" },
                        ),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun text(value: String): SemanticsNodeInteraction = composeRule.onNodeWithText(value)

    @Test
    fun `a marker is a header row above the chapter that carries it`() {
        render()

        text("The First Part").assertIsDisplayed()
        text("Omnibus One").assertIsDisplayed()
    }

    @Test
    fun `tapping a header edits that marker at its own tier`() {
        render()

        text("The First Part").performClick()
        text("Omnibus One").performClick()

        calls shouldBe listOf("edit:c2:${ChapterMarkerTier.Part}", "edit:c3:${ChapterMarkerTier.Book}")
    }

    @Test
    fun `search is a flat result set, so headers step aside`() {
        render(query = "Daw")

        text("Dawn").assertIsDisplayed()
        text("The First Part").assertDoesNotExist()
    }

    @Test
    fun `the offer names the chapters it would mark`() {
        render(
            chapters = marked.map { it.copy(partTitle = null, bookTitle = null) },
            suggestion = PartSuggestion(listOf("Part I", "Part II", "Part III")),
        )

        text("3 chapters look like parts").assertIsDisplayed()
        text("Part I, Part II and Part III. They stay as chapters and open each part.").assertIsDisplayed()
    }

    @Test
    fun `the offer takes its answer in place`() {
        render(suggestion = PartSuggestion(listOf("Part I", "Part II")))

        text("Use as parts").performClick()
        text("Not now").performClick()

        calls shouldBe listOf("accept", "dismiss")
    }

    @Test
    fun `no offer is drawn while searching, and none without one`() {
        render(query = "Daw", suggestion = PartSuggestion(listOf("Part I", "Part II")))

        text("Use as parts").assertDoesNotExist()
    }
}
