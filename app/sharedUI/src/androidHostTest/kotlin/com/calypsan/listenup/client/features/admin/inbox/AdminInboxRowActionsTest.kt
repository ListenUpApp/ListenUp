package com.calypsan.listenup.client.features.admin.inbox

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.InboxBookItem
import com.calypsan.listenup.client.presentation.admin.AdminInboxUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An inbox row opens the book's detail page — the triage layout, where it can be edited, matched
 * and released (spec §8). Edit stays one tap away on the row's own button, and selecting for a batch
 * release is the checkbox's job alone, so none of the three taps does another's work.
 */
@RunWith(RobolectricTestRunner::class)
class AdminInboxRowActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()
    private val edited = mutableListOf<String>()
    private val toggled = mutableListOf<String>()

    @Test
    fun `on a phone, tapping a row opens the book's detail page`() {
        render(isWide = false)
        composeRule.onNodeWithText(TITLE).performClick()
        assertTaps(opened = listOf(BOOK_ID))
    }

    @Test
    fun `on a wide window, tapping a row opens the book's detail page`() {
        render(isWide = true)
        composeRule.onNodeWithText(TITLE).performClick()
        assertTaps(opened = listOf(BOOK_ID))
    }

    @Test
    fun `the row's edit button opens Edit`() {
        render(isWide = false)
        composeRule.onNodeWithContentDescription("Review & edit").performClick()
        assertTaps(edited = listOf(BOOK_ID))
    }

    @Test
    fun `the checkbox selects, and opens nothing`() {
        render(isWide = false)
        composeRule.onNodeWithContentDescription("Select $TITLE").performClick()
        assertTaps(toggled = listOf(BOOK_ID))
    }

    private fun assertTaps(
        opened: List<String> = emptyList(),
        edited: List<String> = emptyList(),
        toggled: List<String> = emptyList(),
    ) {
        composeRule.runOnIdle {
            this.opened shouldBe opened
            this.edited shouldBe edited
            this.toggled shouldBe toggled
        }
    }

    private fun render(isWide: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                AdminInboxBody(
                    state =
                        AdminInboxUiState.Ready(
                            bookIds = listOf(BOOK_ID),
                            books =
                                listOf(
                                    InboxBookItem(
                                        id = BOOK_ID,
                                        title = TITLE,
                                        author = "Susanna Clarke",
                                        // A local path keeps the cover on its synchronous path, clear of Koin.
                                        coverPath = "/tmp/cover-$BOOK_ID.webp",
                                        durationMs = 3_600_000L,
                                    ),
                                ),
                        ),
                    isWide = isWide,
                    innerPadding = PaddingValues(),
                    onBackClick = {},
                    onBookClick = { opened += it },
                    onEditClick = { edited += it },
                    onMatchClick = {},
                    onBookSelectionToggle = { toggled += it },
                    onDismissScanIssue = {},
                    onSelectAll = {},
                    onClearSelection = {},
                    onRelease = {},
                )
            }
        }
    }

    private companion object {
        const val BOOK_ID = "b1"
        const val TITLE = "Piranesi"
    }
}
