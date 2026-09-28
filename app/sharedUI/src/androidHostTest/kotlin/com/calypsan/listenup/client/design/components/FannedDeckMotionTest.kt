package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import com.calypsan.listenup.client.design.motion.LocalReduceMotion
import com.calypsan.listenup.client.design.motion.LocalTouchExplorationActive
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins when a series card's [FannedDeck] may cycle on its own. It moves every few seconds, forever,
 * on every card: that is fine for most people, but not for someone who removed animations (WCAG
 * 2.2.2) or who is exploring the card with TalkBack, whose focus the moving covers would shift.
 */
@RunWith(RobolectricTestRunner::class)
class FannedDeckMotionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the deck advances on its own by default`() {
        val (before, after) = frontBeforeAndAfterSeveralCycles(reduceMotion = false, touchExploration = false)
        after shouldNotBe before
    }

    @Test
    fun `the deck holds still when animations are removed`() {
        val (before, after) = frontBeforeAndAfterSeveralCycles(reduceMotion = true, touchExploration = false)
        after shouldBe before
    }

    @Test
    fun `the deck holds still while TalkBack explores by touch`() {
        val (before, after) = frontBeforeAndAfterSeveralCycles(reduceMotion = false, touchExploration = true)
        after shouldBe before
    }

    /** Advances one cycle plus the stagger, so a moving deck has moved exactly once. */
    private fun frontBeforeAndAfterSeveralCycles(
        reduceMotion: Boolean,
        touchExploration: Boolean,
    ): Pair<Int, Int> {
        var front = -1
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalReduceMotion provides reduceMotion,
                LocalTouchExplorationActive provides touchExploration,
            ) {
                front = rememberDeckFront(covers = COVERS, animate = true, cycleMillis = CYCLE_MS)
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        val before = front
        composeRule.mainClock.advanceTimeBy(CYCLE_MS + MAX_STAGGER_MS)
        composeRule.waitForIdle()
        return before to front
    }

    private companion object {
        const val CYCLE_MS = 1_000L
        const val MAX_STAGGER_MS = 500L
        val COVERS =
            listOf("a", "b", "c").map { id ->
                FannedDeckCover(bookId = id, coverPath = null, title = "Book $id", author = null)
            }
    }
}
