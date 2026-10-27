package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.calypsan.listenup.client.features.nowplaying.components.NARRATOR_LINE_TAG
import com.calypsan.listenup.client.features.nowplaying.components.PLAYER_COVER_TAG
import com.calypsan.listenup.client.features.nowplaying.components.PlayerBookBlock
import com.calypsan.listenup.client.features.nowplaying.components.PlayerCoverSide
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone title block (Simon, 2026-10-27): bold title, chapter, mic + "Narrated by …", each centred on the cover, to
 * within 1dp. Measured on the words' own line bounds, not the nodes, at a short title, a two-line title and 2× text.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class PhoneTitleCentringTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun block(
        state: PlayerUiState.Loaded,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    PlayerBookBlock(
                        state = state,
                        coverSide = PlayerCoverSide,
                        onContributor = {},
                        onShowAuthors = {},
                        onShowNarrators = {},
                    )
                }
            }
        }
    }

    /** [text] as the block draws it, not the cover placeholder's lettering of the same title. */
    private fun words(text: String) = hasText(text) and !hasAnyAncestor(hasTestTag(PLAYER_COVER_TAG))

    private fun assertCentredOnCover(title: String) {
        val axis = composeRule.onNodeWithTag(PLAYER_COVER_TAG, useUnmergedTree = true).centreXPx()
        val slack = composeRule.density.density
        val titleLines = composeRule.onNode(words(title), useUnmergedTree = true).lineCentresPx()
        titleLines.forEachIndexed { line, centre ->
            withClue("title line ${line + 1}") { centre shouldBe (axis plusOrMinus slack) }
        }
        withClue("chapter") {
            composeRule.onNodeWithText("Bridge Four", useUnmergedTree = true).lineCentresPx().single() shouldBe
                (axis plusOrMinus slack)
        }
        withClue("narrator line, mic included") {
            composeRule
                .onAllNodes(hasAnyAncestor(hasTestTag(NARRATOR_LINE_TAG)), useUnmergedTree = true)
                .unionCentreXPx() shouldBe (axis plusOrMinus slack)
        }
    }

    @Test
    fun `a short title, the chapter and the narrators sit on the cover's axis`() {
        block(loadedPlayerState(coverPath = NO_COVER))
        assertCentredOnCover("The Way of Kings")
    }

    @Test
    fun `a title that wraps to two lines keeps each line on the axis`() {
        val long = "The Way of Kings, Being the First Book of the Stormlight Archive"
        block(loadedPlayerState(coverPath = NO_COVER, title = long))
        (composeRule.onNode(words(long), useUnmergedTree = true).lineCentresPx().size >= 2) shouldBe true
        assertCentredOnCover(long)
    }

    @Test
    fun `at twice the text size the block stays centred`() {
        block(loadedPlayerState(coverPath = NO_COVER), fontScale = 2f)
        assertCentredOnCover("The Way of Kings")
    }

    @Test
    fun `the order is title, chapter, then who narrates it`() {
        block(loadedPlayerState(coverPath = NO_COVER))
        val title = composeRule.onNode(words("The Way of Kings"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val chapter = composeRule.onNodeWithText("Bridge Four", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val narrated =
            composeRule
                .onNode(
                    hasText("Narrated by", substring = true),
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
                .boundsInRoot
        (title.bottom <= chapter.top && chapter.bottom <= narrated.top) shouldBe true
    }
}
