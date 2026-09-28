package com.calypsan.listenup.client.features.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.click
import com.calypsan.listenup.client.design.components.BookCoverModel
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * With a mouse, a right-click on a book does what a long press does on a touchscreen — it opens
 * selection — and never opens the book. A plain click still opens it.
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

    private fun setContent() {
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
                )
            }
        }
    }

    private companion object {
        const val TITLE = "The Way of Kings"
    }
}
