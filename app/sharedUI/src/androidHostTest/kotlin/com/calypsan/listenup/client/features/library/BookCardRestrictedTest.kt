package com.calypsan.listenup.client.features.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.calypsan.listenup.client.design.components.BookCoverModel
import com.calypsan.listenup.client.design.components.LocalRestrictedBookIds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every BookCard — library, contributor, facet, genre, shelf, search grid, Continue Listening,
 * Discover — wears the lock for a restricted book, alongside its listening badge, and the *Held*
 * label always wins the shared top-start corner.
 */
@RunWith(RobolectricTestRunner::class)
class BookCardRestrictedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a restricted book's card carries the lock`() {
        setContent(restricted = setOf("b1"))
        composeRule.onNodeWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the lock sits beside the now-playing badge rather than replacing it`() {
        setContent(restricted = setOf("b1"), isPlaying = true)
        composeRule.onNodeWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription(NOW_PLAYING_A11Y, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `an unrestricted book's card carries none`() {
        setContent(restricted = emptySet())
        composeRule.onNodeWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a held book shows Held, never the lock, in the shared corner`() {
        setContent(restricted = setOf("b1"), isHeld = true)
        composeRule.onNodeWithContentDescription(HELD_A11Y, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun setContent(
        restricted: Set<String>,
        isPlaying: Boolean = false,
        isHeld: Boolean = false,
    ) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRestrictedBookIds provides restricted) {
                    BookCard(
                        cover =
                            BookCoverModel(
                                bookId = "b1",
                                title = "Dune",
                                author = "Frank Herbert",
                                coverPath = "/tmp/cover-b1.webp",
                                coverHash = null,
                            ),
                        onClick = {},
                        isPlaying = isPlaying,
                        isHeld = isHeld,
                    )
                }
            }
        }
    }

    private companion object {
        const val RESTRICTED_A11Y = "In a collection, so only people it is shared with can see it."
        const val HELD_A11Y = "Held for review, hidden from all members"
        const val NOW_PLAYING_A11Y = "Now playing"
    }
}
