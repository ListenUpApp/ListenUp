package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
 * That a lock is *state*, not just a button.
 *
 * The row could always render a pinned lock and the ViewModel could always hold the set, and the
 * screen would still be wrong if nothing carried one to the other — the icon would sit there
 * unlit while drift correction quietly exempted the boundary anyway. These render the real
 * content with a locked id and assert it arrives, which is the seam that was missing.
 */
// Tall enough that the list's LazyColumn composes every row: at the default device height only
// the first is in the viewport, and the assertions would be measuring the window, not the code.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h2400dp")
class ChapterEditorContentLockTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chapters =
        List(3) { i ->
            Chapter(id = "c$i", title = "Chapter ${i + 1}", duration = 10_000L, startTime = i * 10_000L)
        }

    private fun render(
        locked: Set<String>,
        onToggleLock: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                ChapterEditorContent(
                    chapters = chapters.numbered(),
                    bookDurationMs = BOOK_MS,
                    geometry = TimelineGeometry(windowStartMs = 0L, windowEndMs = BOOK_MS, widthPx = 1_000f),
                    isWide = true,
                    selectedChapterId = null,
                    playheadMs = null,
                    onSelect = {},
                    onNudge = { _, _ -> },
                    onSnapToPlayhead = {},
                    onToggleLock = onToggleLock,
                    onMore = {},
                    onSeekFraction = {},
                    lockedChapterIds = locked,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a locked chapter offers to unlock, and its neighbours do not`() {
        render(locked = setOf("c1"))

        assertEquals(1, composeRule.onAllNodesWithContentDescription("Unlock chapter").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithContentDescription("Lock chapter").fetchSemanticsNodes().size)
    }

    @Test
    fun `with nothing locked every row offers to lock`() {
        render(locked = emptySet())

        assertEquals(3, composeRule.onAllNodesWithContentDescription("Lock chapter").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithContentDescription("Unlock chapter").fetchSemanticsNodes().size)
    }

    @Test
    fun `the toggle reports the chapter it belongs to`() {
        // The row builds its own callback from its id; a shared lambda that forgot to close over
        // the row would still compile and would lock whichever chapter happened to be first.
        val toggled = mutableListOf<String>()
        render(locked = setOf("c1"), onToggleLock = { toggled += it })

        composeRule.onNodeWithContentDescription("Unlock chapter").performClick()

        assertEquals(listOf("c1"), toggled)
    }
}
