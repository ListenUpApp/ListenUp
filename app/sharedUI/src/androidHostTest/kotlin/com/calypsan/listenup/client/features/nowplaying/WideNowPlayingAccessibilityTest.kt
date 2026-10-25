package com.calypsan.listenup.client.features.nowplaying

import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.features.nowplaying.components.CHAPTER_PANEL_TAG
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.client.testing.AtFontScale
import com.calypsan.listenup.client.testing.LARGEST_FONT_SCALE
import com.calypsan.listenup.client.testing.TalkBackOrder
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Spec §8 on the tablet's wide Now Playing: at the largest font the player column scrolls to its settings row and the
 * panel to its last chapter; every control keeps a 48dp target; and TalkBack and the D-pad reach the player column
 * before the panel, as the eye reads it. Native graphics, so text is measured at its real size.
 */
@Config(qualifiers = "w1280dp-h800dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class WideNowPlayingAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var composeView: View

    private fun wide(
        fontScale: Float = 1f,
        state: PlayerUiState.Loaded = chapteredState(current = 38),
    ) {
        composeRule.setContent {
            val view = LocalView.current
            SideEffect { composeView = view }
            AtFontScale(fontScale) { MaterialTheme { TestWideNowPlaying(state = state) } }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `at the largest font the player column scrolls to the card's buttons and its settings row`() {
        // A failure's card is the tallest thing the column holds in the transport's place.
        wide(
            fontScale = LARGEST_FONT_SCALE,
            state =
                chapteredState(
                    current = 38,
                    phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()),
                    primary = PrimaryControl.Retry,
                ),
        )
        composeRule.onNodeWithText("Dismiss").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sleep timer", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `at the largest font the panel scrolls its last chapter clear of Back to current chapter`() {
        // At 2x the button all but fills the panel, and a longer language wraps it; the list's foot follows its height.
        wide(fontScale = LARGEST_FONT_SCALE)
        composeRule.onNodeWithTag(CHAPTER_PANEL_TAG).performScrollToIndex(74)
        composeRule.waitForIdle()
        val lastRow = composeRule.onNodeWithTag(chapterRowTag(74)).assertIsDisplayed().fetchSemanticsNode()
        val back = composeRule.onNodeWithText("Back to current chapter").assertIsDisplayed().fetchSemanticsNode()
        withClue("last row ${lastRow.boundsInRoot}, Back to current chapter ${back.boundsInRoot}") {
            (lastRow.boundsInRoot.bottom <= back.boundsInRoot.top) shouldBe true
        }
    }

    @Test
    fun `every control on the wide screen is at least 48dp`() {
        wide()
        val min = with(composeRule.density) { 48.dp.toPx() }
        val tooSmall =
            composeRule
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()
                // A row half scrolled out of the panel is clipped by it, not small; it is measured once in view.
                .filterNot { it.boundsInRoot.height < it.size.height - 1 || it.boundsInRoot.width < it.size.width - 1 }
                .filter { it.touchBoundsInRoot.height < min || it.touchBoundsInRoot.width < min }
                .map { it.config.toString() }
        withClue(tooSmall.joinToString("\n")) { tooSmall.isEmpty() shouldBe true }
    }

    @Test
    fun `TalkBack and the D-pad reach every player-column control before the panel's heading and rows`() {
        TalkBackOrder.enableAccessibility()
        wide()
        val ids = TalkBackOrder.of(composeView, composeRule)
        val player =
            listOf(
                "collapse" to composeRule.onNodeWithContentDescription("Collapse player"),
                "series link" to composeRule.onNodeWithText("The Stormlight Archive · Book 1"),
                "title" to composeRule.onNodeWithText("The Way of Kings"),
                "transport" to composeRule.onNodeWithContentDescription("Pause"),
                "settings" to composeRule.onNodeWithContentDescription("Sleep timer", substring = true),
            ).map { (name, node) -> name to ids.indexOf(node.fetchSemanticsNode().id) }
        val panel =
            listOf(
                "panel heading" to composeRule.onNode(hasText("Chapters") and isHeading()),
                "the panel's first row" to composeRule.onNodeWithTag(firstVisibleRowTag()),
            ).map { (name, node) -> name to ids.indexOf(node.fetchSemanticsNode().id) }

        withClue("player $player, panel $panel, traversal $ids") {
            (player + panel).none { it.second < 0 } shouldBe true
            player.maxOf { it.second } shouldBeLessThan panel.minOf { it.second }
        }
    }

    @Test
    fun `the panel is its own traversal group, so TalkBack finishes the player before entering it`() {
        wide()
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true) and
                    hasAnyDescendant(hasText("Chapters") and isHeading()) and
                    hasAnyDescendant(hasTestTag(CHAPTER_PANEL_TAG)) and
                    !hasAnyDescendant(hasContentDescription("Pause")),
            ).assertExists()
    }

    /** The tag of the panel's topmost row in view: the first one TalkBack meets after the heading. */
    private fun firstVisibleRowTag(): String {
        val panelTop =
            composeRule
                .onNodeWithTag(CHAPTER_PANEL_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.top
        return (0 until 75)
            .map(::chapterRowTag)
            .mapNotNull { tag ->
                composeRule
                    .onAllNodesWithTag(tag)
                    .fetchSemanticsNodes()
                    .firstOrNull()
                    ?.let { tag to it.boundsInRoot }
            }.filter { (_, bounds) -> bounds.top >= panelTop && bounds.height > 0f }
            .minBy { (_, bounds) -> bounds.top }
            .first
    }
}
