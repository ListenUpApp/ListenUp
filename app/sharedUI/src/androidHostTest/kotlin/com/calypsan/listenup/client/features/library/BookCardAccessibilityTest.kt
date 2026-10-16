package com.calypsan.listenup.client.features.library

import androidx.compose.material3.MaterialTheme
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

/** Spec §8.2: a Home card is one element with one label, and pressing it still opens the book. */
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
}
