package com.calypsan.listenup.client.features.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import com.calypsan.listenup.client.design.components.BookCoverModel
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * With a mouse, a right-click on a book does what a long press does on a touchscreen (the Library's book menu, or
 * selection elsewhere) and never opens the book. A plain click still opens it. TalkBack hears what the long press
 * does: "Book actions" where the host says so, "Select" by default (Home, Discover and shelves are unchanged).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class BookCardPointerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var clicks = 0
    private var longPresses = 0

    @Test
    fun `a right-click takes the long-press path, not the click`() {
        setContent()

        composeRule.onAllNodesWithText(TITLE).onFirst().performMouseInput { rightClick() }

        composeRule.runOnIdle {
            longPresses shouldBe 1
            clicks shouldBe 0
        }
    }

    @Test
    fun `a left click still opens the book`() {
        setContent()

        composeRule.onAllNodesWithText(TITLE).onFirst().performMouseInput { click() }

        composeRule.runOnIdle {
            clicks shouldBe 1
            longPresses shouldBe 0
        }
    }

    @Test
    fun `a long press says Book actions to TalkBack when the host names it`() {
        setContent(longPressLabel = "Book actions")

        composeRule.onNode(hasClickAction()).assert(longClickLabel("Book actions"))
    }

    @Test
    fun `the default long-press label is still Select`() {
        setContent()

        composeRule.onNode(hasClickAction()).assert(longClickLabel("Select"))
    }

    private fun longClickLabel(label: String) =
        SemanticsMatcher("long click label is $label") { node ->
            node.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == label
        }

    private fun setContent(longPressLabel: String? = null) {
        composeRule.setContent {
            MaterialTheme {
                BookCard(
                    cover =
                        BookCoverModel(
                            bookId = "b1",
                            title = TITLE,
                            author = "Brandon Sanderson",
                            // A local path keeps BookCoverImage off the Koin-backed async fallback.
                            coverPath = "/tmp/cover-b1.webp",
                            coverHash = null,
                        ),
                    onClick = { clicks++ },
                    onLongPress = { longPresses++ },
                    longPressLabel = longPressLabel,
                )
            }
        }
    }

    private companion object {
        const val TITLE = "The Way of Kings"
    }
}
