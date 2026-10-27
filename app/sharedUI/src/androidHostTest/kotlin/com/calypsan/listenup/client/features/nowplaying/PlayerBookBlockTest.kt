package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.nowplaying.components.PlayerBookBlock
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P4–P9 (§3.1–§3.2). */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class PlayerBookBlockTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun block(state: com.calypsan.listenup.client.presentation.player.PlayerUiState.Loaded) {
        composeRule.setContent {
            MaterialTheme {
                PlayerBookBlock(
                    state = state,
                    coverSide = com.calypsan.listenup.client.features.nowplaying.components.PlayerCoverSide,
                    onContributor = { opened += it },
                    onShowAuthors = {},
                    onShowNarrators = {},
                )
            }
        }
    }

    @Test
    fun `the counter, the chapter's own title, the book title and both bylines`() {
        block(loadedPlayerState(coverPath = NO_COVER))
        composeRule.onNodeWithText("Chapter 6 of 75").assertExists()
        composeRule.onNodeWithText("Bridge Four").assertExists()
        composeRule.onNodeWithText("The Way of Kings").assertExists()
        composeRule.onNodeWithText("Brandon Sanderson").performClick()
        composeRule.onNodeWithText("Kate Reading").performClick()
        opened shouldBe listOf("a1", "n2")
    }

    @Test
    fun `a generic chapter title stands alone, without our counter`() {
        block(loadedPlayerState(coverPath = NO_COVER, chapterTitle = "Chapter 14", isGenericTitle = true))
        composeRule.onNodeWithText("Chapter 14").assertExists()
        composeRule.onNodeWithText("Chapter 6 of 75").assertDoesNotExist()
    }

    @Test
    fun `a chapterless book reads Whole book, with no counter`() {
        block(loadedPlayerState(coverPath = NO_COVER, isWholeBook = true))
        composeRule.onNodeWithText("Whole book").assertExists()
        composeRule.onNodeWithText("Chapter 1 of 1").assertDoesNotExist()
    }

    @Test
    fun `more than three narrators fold to the lead and a count`() {
        block(
            loadedPlayerState(
                coverPath = NO_COVER,
                narrators = (1..5).map { PlayerContributor("n$it", "Narrator $it", 1) },
            ),
        )
        composeRule.onNodeWithText("Narrator 1 & 4 more").assertExists()
    }
}
