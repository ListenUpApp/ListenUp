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
import io.kotest.matchers.shouldBe
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

    private fun narrator(
        id: String,
        name: String,
    ) = BookContributor(id = id, name = name)

    private companion object {
        const val ICON = "byline-icon"
    }
}
