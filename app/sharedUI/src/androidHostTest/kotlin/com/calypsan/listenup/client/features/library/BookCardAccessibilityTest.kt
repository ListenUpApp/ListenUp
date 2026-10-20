package com.calypsan.listenup.client.features.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.design.components.BookCoverModel
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Spec §8.2: a Home card is one element with one label, and pressing it still opens the book. Spec §5.2: while
 * selecting, a card is a checkbox to TalkBack, checked or not; outside selection it is a button.
 */
@RunWith(RobolectricTestRunner::class)
class BookCardAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val label = "Babel, by R. F. Kuang, 11 percent listened, 18h 40m left"

    @Test
    fun `a labelled card is announced once, by its label, and still opens`() {
        var opened = 0
        composeRule.setContent {
            MaterialTheme {
                BookCard(
                    cover = BookCoverModel("b1", "Babel", "R. F. Kuang", coverPath = "/tmp/cover-b1.webp", coverHash = null),
                    onClick = { opened++ },
                    libraryStatus = BookCardStatus.InProgress(fraction = 0.11f, timeLeftMs = 67_200_000L),
                    progressUnderTitle = true,
                    accessibilityLabel = label,
                )
            }
        }

        composeRule.onNodeWithContentDescription(label).assertHasClickAction().performClick()
        opened shouldBe 1
        // The title, author and time left are inside the label, not separate stops.
        composeRule.onAllNodesWithText("Babel").assertCountEquals(0)
        composeRule.onAllNodesWithText("18h 40m left").assertCountEquals(0)
    }

    @Test
    fun `an unlabelled card keeps announcing its own text`() {
        composeRule.setContent {
            MaterialTheme {
                BookCard(
                    cover = BookCoverModel("b1", "Babel", "R. F. Kuang", coverPath = "/tmp/cover-b1.webp", coverHash = null),
                    onClick = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Babel").assertCountEquals(1)
    }

    @Test
    fun `while selecting, a card is a checkbox, Off then On`() {
        var selected by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                BookCard(
                    cover = BookCoverModel("b1", "Babel", "R. F. Kuang", coverPath = "/tmp/cover-b1.webp", coverHash = null),
                    onClick = { selected = !selected },
                    isInSelectionMode = true,
                    isSelected = selected,
                )
            }
        }

        val card = composeRule.onNode(hasClickAction())
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        card.performClick()
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test
    fun `a labelled card while selecting is a checkbox too`() {
        composeRule.setContent {
            MaterialTheme {
                BookCard(
                    cover = BookCoverModel("b1", "Babel", "R. F. Kuang", coverPath = "/tmp/cover-b1.webp", coverHash = null),
                    onClick = {},
                    accessibilityLabel = label,
                    isInSelectionMode = true,
                    isSelected = true,
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(label)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test
    fun `outside selection a labelled card is a button, with no checked state`() {
        composeRule.setContent {
            MaterialTheme {
                BookCard(
                    cover = BookCoverModel("b1", "Babel", "R. F. Kuang", coverPath = "/tmp/cover-b1.webp", coverHash = null),
                    onClick = {},
                    accessibilityLabel = label,
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(label)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
    }
}
