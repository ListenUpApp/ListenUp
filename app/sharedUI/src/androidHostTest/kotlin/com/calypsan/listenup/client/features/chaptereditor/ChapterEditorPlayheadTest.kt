package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineGeometry
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineLane
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val BOOK_MS = 30_000L

/**
 * The playhead reaches the editor as a reader, not a value, so the caller never recomposes on a
 * tick. The rows derive their "NOW" badge from it, and that badge must still come and go as the
 * playhead moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h2400dp")
class ChapterEditorPlayheadTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chapters =
        List(2) { i ->
            Chapter(id = "c$i", title = "Chapter ${i + 1}", duration = 15_000L, startTime = i * 15_000L)
        }

    @Test
    fun `the NOW badge follows a playhead the editor reads rather than receives`() {
        val position = mutableLongStateOf(1_000L)
        composeRule.setContent {
            MaterialTheme {
                ChapterEditorContent(
                    chapters = chapters.numbered(),
                    bookDurationMs = BOOK_MS,
                    lane = TimelineLane(TimelineGeometry(windowStartMs = 0L, windowEndMs = BOOK_MS, widthPx = 1_000f)),
                    onLaneChange = {},
                    isWide = true,
                    selectedChapterId = null,
                    playheadMs = { position.longValue },
                    onSelect = {},
                    onNudge = { _, _ -> },
                    onSnapToPlayhead = {},
                    onToggleLock = {},
                    rowMenu = ChapterRowMenuActions({}, {}, null, {}),
                    onEditTime = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("NOW").assertCountEquals(1)

        // Past the end: no chapter contains it, so the badge must go — read live, not captured.
        position.longValue = 40_000L
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("NOW").assertCountEquals(0)

        position.longValue = 16_000L
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("NOW").assertCountEquals(1)
    }
}
