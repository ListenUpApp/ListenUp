package com.calypsan.listenup.client.features.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.calypsan.listenup.client.design.components.BookCoverModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The wide search grid's cover card wears *Held* at the cover's top-leading corner (spec §7). */
@RunWith(RobolectricTestRunner::class)
class BookCardHeldTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a held book's cover carries the Held marker`() {
        setContent(isHeld = true)

        composeRule.onNodeWithContentDescription(HELD_A11Y, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `an ordinary cover carries none`() {
        setContent(isHeld = false)

        composeRule.onNodeWithContentDescription(HELD_A11Y, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun setContent(isHeld: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                BookCard(
                    cover =
                        BookCoverModel(
                            bookId = "b1",
                            title = "The Ministry of Time",
                            author = "Kaliane Bradley",
                            coverPath = "/tmp/cover-b1.webp",
                            coverHash = null,
                        ),
                    onClick = {},
                    isHeld = isHeld,
                )
            }
        }
    }

    private companion object {
        const val HELD_A11Y = "Held for review, hidden from all members"
    }
}
