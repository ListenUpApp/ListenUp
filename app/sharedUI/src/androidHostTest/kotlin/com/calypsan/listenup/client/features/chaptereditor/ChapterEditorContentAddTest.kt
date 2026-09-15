package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.calypsan.listenup.client.design.timeline.TimelineGeometry
import com.calypsan.listenup.client.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val BOOK_MS = 30_000L

/**
 * A book that already has chapters must still offer a way to add one. iOS has a bottom bar and web
 * a button under the list; Android only offered it from the empty state, so a listener with 300
 * chapters and one missing could not add it. The control is the list's last item, where the spec
 * draws it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h2400dp")
class ChapterEditorContentAddTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chapters =
        List(2) { i ->
            Chapter(id = "c$i", title = "Chapter ${i + 1}", duration = 15_000L, startTime = i * 15_000L)
        }

    private fun render(
        onAddAtPlayhead: () -> Unit,
        playheadMs: Long? = 7_000L,
    ) {
        composeRule.setContent {
            MaterialTheme {
                ChapterEditorContent(
                    chapters = chapters.numbered(),
                    bookDurationMs = BOOK_MS,
                    geometry = TimelineGeometry(windowStartMs = 0L, windowEndMs = BOOK_MS, widthPx = 1_000f),
                    isWide = true,
                    selectedChapterId = null,
                    playheadMs = playheadMs,
                    onSelect = {},
                    onNudge = { _, _ -> },
                    onSnapToPlayhead = {},
                    onToggleLock = {},
                    onMore = {},
                    onSeekFraction = {},
                    onAddAtPlayhead = onAddAtPlayhead,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a populated list still offers to add a chapter at the playhead`() {
        var added = 0
        render(onAddAtPlayhead = { added++ })

        composeRule.onNodeWithText("Add chapter at playhead").assertIsDisplayed()
        composeRule.onNodeWithText("Add chapter at playhead").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, added)
    }

    @Test
    fun `without a playhead the add control is absent, as on iOS and web`() {
        render(onAddAtPlayhead = {}, playheadMs = null)

        composeRule.onNodeWithText("Add chapter at playhead").assertDoesNotExist()
    }
}
