package com.calypsan.listenup.client.features.contributors

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.design.text.RightToLeft
import com.calypsan.listenup.client.domain.model.BookContributor
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import androidx.compose.ui.text.style.TextAlign
import com.calypsan.listenup.client.design.text.textAlign
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A byline is one sentence ("Narrated by A & B"), laid out word by word: its pieces must run in the sentence's own
 * reading order. In a right-to-left layout with English strings, that is still left to right, mic first.
 */
@RunWith(RobolectricTestRunner::class)
class ContributorLineDirectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `in a right-to-left layout an English byline still reads icon, Narrated by, then the names`() {
        composeRule.setContent {
            RightToLeft {
                ClickableContributorLine(
                    contributors = listOf(narrator("1", "Richard E. Grant"), narrator("2", "Michael Morpurgo")),
                    onContributorClick = {},
                    style = MaterialTheme.typography.bodyMedium,
                    nameColor = Color.Black,
                    separatorColor = Color.Black,
                    leadingIcon = {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.testTag(ICON))
                    },
                    prefix = "Narrated by ",
                )
            }
        }
        val icon = composeRule.onNodeWithTag(ICON, useUnmergedTree = true).getBoundsInRoot()
        val prefix = composeRule.onNodeWithText("Narrated by ").getBoundsInRoot()
        val first = composeRule.onNodeWithText("Richard E. Grant").getBoundsInRoot()
        val second = composeRule.onNodeWithText("Michael Morpurgo").getBoundsInRoot()

        (icon.right <= prefix.left) shouldBe true
        (prefix.right <= first.left) shouldBe true
        (first.right <= second.left) shouldBe true
    }

    @Test
    fun `in a left-to-right layout an Arabic byline reads right to left, icon first, its gap towards the names`() {
        composeRule.setContent {
            ListenUpTheme(dynamicColor = false) {
                ClickableContributorLine(
                    contributors = listOf(narrator("1", ARABIC_NAME), narrator("2", "Someone")),
                    onContributorClick = {},
                    style = MaterialTheme.typography.bodyMedium,
                    nameColor = Color.Black,
                    separatorColor = Color.Black,
                    leadingIcon = {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.testTag(ICON))
                    },
                )
            }
        }
        val icon = composeRule.onNodeWithTag(ICON, useUnmergedTree = true).getBoundsInRoot()
        val first = composeRule.onNodeWithText(ARABIC_NAME).getBoundsInRoot()
        val second = composeRule.onNodeWithText("Someone").getBoundsInRoot()

        (first.right <= icon.left) shouldBe true
        (second.right <= first.left) shouldBe true
        // The icon's 6dp gap sits between it and the first name, not on its outer side.
        (icon.left - first.right).value shouldBe (ICON_GAP_DP plusOrMinus 1f)
    }

    @Test
    fun `words laid out against the layout align by their own direction's side, as their layout now reads`() {
        composeRule.setContent {
            RightToLeft {
                ClickableContributorLine(
                    contributors = listOf(narrator("1", "Richard E. Grant")),
                    onContributorClick = {},
                    style = MaterialTheme.typography.bodyMedium,
                    nameColor = Color.Black,
                    separatorColor = Color.Black,
                    prefix = "Narrated by ",
                )
            }
        }
        // Inside the left-to-right sentence, the theme's "layout start" is the left, as TextAlign.LayoutStart there says.
        composeRule.onNodeWithText("Narrated by ").textAlign() shouldBe TextAlign.Left
    }

    private fun narrator(
        id: String,
        name: String,
    ) = BookContributor(id = id, name = name)

    private companion object {
        const val ICON = "byline-icon"
        const val ARABIC_NAME = "نجيب محفوظ"
        const val ICON_GAP_DP = 6f
    }
}
