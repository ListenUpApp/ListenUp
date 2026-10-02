package com.calypsan.listenup.client.features.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A held book in search: the never-stranded path to its triage page. It carries the *Held* marker
 * (amber, the inbox glyph and the word — never colour alone) and, like every search row, offers
 * nothing but opening it.
 */
@RunWith(RobolectricTestRunner::class)
class BookResultRowHeldTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var opens = 0

    @Test
    fun `a held hit carries the Held marker, read in full`() {
        setContent(isHeld = true)

        composeRule.onNodeWithContentDescription(HELD_A11Y, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `an ordinary hit carries no marker`() {
        setContent(isHeld = false)

        composeRule.onNodeWithContentDescription(HELD_A11Y, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a held hit opens like any other, and offers no play`() {
        setContent(isHeld = true)

        composeRule.onNodeWithText("Play").assertDoesNotExist()
        composeRule.onNodeWithText(TITLE).performClick()
        composeRule.runOnIdle { opens shouldBe 1 }
    }

    private fun setContent(isHeld: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                BookResultRow(
                    hit =
                        SearchHit(
                            id = "b1",
                            type = SearchHitType.BOOK,
                            name = TITLE,
                            author = "Kaliane Bradley",
                            duration = 42_720_000L,
                            // A local path keeps BookCoverImage off the Koin-backed async fallback.
                            coverPath = "/tmp/cover-b1.webp",
                            isHeld = isHeld,
                        ),
                    query = "",
                    onClick = { opens++ },
                )
            }
        }
    }

    private companion object {
        const val TITLE = "The Ministry of Time"
        const val HELD_A11Y = "Held for review, hidden from all members"
    }
}
