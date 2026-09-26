package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineGeometry
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineLane
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The timeline pane's gestures, over the shared [TimelineLane].
 *
 * 2026-09-25: dragging a boundary called retime on every pointer move, and each was its own undo
 * frame — Undo after a drag walked it back a pixel at a time. One drag is one edit.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterTimelinePaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chapters =
        listOf(
            Chapter(id = "c1", title = "One", duration = 50_000L, startTime = 0L),
            Chapter(id = "c2", title = "Two", duration = 50_000L, startTime = 50_000L),
        )

    private fun render(
        onRetime: (String, Long) -> Unit = { _, _ -> },
        onLane: (TimelineLane) -> Unit = {},
    ) {
        composeRule.setContent {
            var lane by remember {
                mutableStateOf(TimelineLane(TimelineGeometry(windowStartMs = 0L, windowEndMs = 100_000L, widthPx = 0f)))
            }
            MaterialTheme {
                ChapterTimelinePane(
                    chapters = chapters.mapIndexed { i, c -> NumberedChapter(c, i + 1) },
                    bookDurationMs = 100_000L,
                    lane = lane,
                    onLaneChange = {
                        lane = it
                        onLane(it)
                    },
                    selectedChapterId = null,
                    playheadMs = null,
                    fileBoundaries = emptyList(),
                    ghosts = emptyList(),
                    lockedChapterIds = emptySet(),
                    onRetime = onRetime,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a drag across the lane is committed once, when it is released`() {
        val retimes = mutableListOf<Pair<String, Long>>()
        render(onRetime = { id, at -> retimes += id to at })

        composeRule.onNodeWithContentDescription("Chapter timeline showing 2 chapters").performTouchInput {
            val marker = Offset(width * 0.5f, height / 2f)
            down(marker)
            repeat(10) { moveBy(Offset(width * 0.01f, 0f)) }
            up()
        }
        composeRule.waitForIdle()

        assert(retimes.size == 1) { "one drag must be one edit, got ${retimes.size}: $retimes" }
        assert(retimes.single().first == "c2") { "the grabbed boundary is the one retimed, got $retimes" }
        assert(retimes.single().second > 50_000L) { "dragging right moves the boundary later, got $retimes" }
    }

    @Test
    fun `the zoom buttons narrow and widen the lane`() {
        val lengths = mutableListOf<Long>()
        render(onLane = { lengths += it.geometry.windowLengthMs })

        composeRule.onNodeWithContentDescription("Zoom in").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Zoom out").performClick()
        composeRule.waitForIdle()

        val zoomed = lengths.filter { it != 100_000L }
        assert(zoomed.firstOrNull()?.let { it < 100_000L } == true) { "zoom in narrows the window, got $lengths" }
        assert(lengths.last() == 100_000L) { "zoom out widens it back, got $lengths" }
    }
}
