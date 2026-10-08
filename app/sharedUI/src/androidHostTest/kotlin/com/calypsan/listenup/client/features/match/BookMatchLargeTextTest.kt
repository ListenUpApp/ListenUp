package com.calypsan.listenup.client.features.match

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.calypsan.listenup.client.testing.AtFontScale
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertNoMidWordBreaks
import com.calypsan.listenup.client.testing.assertStacked
import com.calypsan.listenup.client.testing.textLayout
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** At the largest text size the Apply bar stacks: the summary above, the button's label on one line. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookMatchLargeTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `at the largest text the summary moves above a one-line Apply changes`() {
        composeRule.setContent {
            AtFontScale {
                MaterialTheme {
                    ReviewPane(
                        state = MatchFixtures.ready,
                        bookId = MatchFixtures.BOOK_ID,
                        bookTitle = "Project Hail Mary",
                        viewerId = MatchFixtures.VIEWER_ID,
                        isTwoPane = false,
                        actions = RecordingMatchActions(),
                    )
                }
            }
        }

        assertStacked(composeRule.onNodeWithTag(APPLY_SUMMARY_TAG), composeRule.onNodeWithTag(APPLY_BUTTON_TAG))
        val label = composeRule.onNode(hasText("Apply changes"), useUnmergedTree = true)
        label.assertNoMidWordBreaks()
        label.textLayout().lineCount shouldBe 1
    }
}
