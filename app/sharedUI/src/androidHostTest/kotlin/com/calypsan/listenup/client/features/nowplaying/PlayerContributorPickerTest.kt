package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.player.ContributorPickerType
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.testing.assertTouchTargetAtLeast
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.5.5 (PK1) on Android: the player's people, each with how many of their books are in the library. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class PlayerContributorPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun picker(type: ContributorPickerType) {
        val book =
            loadedPlayerState(
                authors = listOf(PlayerContributor("a1", "Brandon Sanderson", 0)),
                narrators = listOf(PlayerContributor("n1", "Michael Kramer", 14), PlayerContributor("n2", "Kate Reading", 1)),
            ).book
        composeRule.setContent {
            MaterialTheme {
                PlayerContributorPicker(
                    type = type,
                    book = book,
                    onContributorClick = { opened += it },
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `narrators - the title, and each person's books in the library`() {
        picker(ContributorPickerType.NARRATORS)
        composeRule.onNodeWithText("Narrators").assertExists()
        composeRule.onNodeWithText("14 books in your library", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("1 book in your library", useUnmergedTree = true).assertExists()
        composeRule
            .onNode(hasText("Michael Kramer"))
            .assert(hasContentDescription("Michael Kramer, 14 books in your library"))
            .assertTouchTargetAtLeast()
    }

    @Test
    fun `a tap reports the person`() {
        picker(ContributorPickerType.NARRATORS)
        composeRule.onNodeWithText("Michael Kramer").performClick()
        opened shouldBe listOf("n1")
    }

    @Test
    fun `authors - the title, and no line for a count of zero`() {
        picker(ContributorPickerType.AUTHORS)
        composeRule.onNodeWithText("Authors").assertExists()
        composeRule.onNodeWithText("0 books in your library", useUnmergedTree = true).assertDoesNotExist()
    }
}
