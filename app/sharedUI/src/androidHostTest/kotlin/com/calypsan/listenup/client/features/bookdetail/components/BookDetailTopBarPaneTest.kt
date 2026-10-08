package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.design.LocalInDetailPane
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Beside its list, Book Detail's back arrow becomes a Close — the list stays on screen, so "Back"
 * would name something that does not happen. The action is the same pop either way.
 */
@RunWith(RobolectricTestRunner::class)
class BookDetailTopBarPaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `on its own, the book detail offers Back`() {
        var backs = 0
        setContent(inDetailPane = false) { backs++ }

        composeRule.onNodeWithContentDescription("Close book").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Back").performClick()
        backs shouldBe 1
    }

    @Test
    fun `beside a list, the book detail offers Close, which closes the pane`() {
        var backs = 0
        setContent(inDetailPane = true) { backs++ }

        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Close book").performClick()
        backs shouldBe 1
    }

    private fun setContent(
        inDetailPane: Boolean,
        onBack: () -> Unit,
    ) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalInDetailPane provides inDetailPane) {
                    BookDetailTopBar(
                        title = "Words of Radiance",
                        isComplete = false,
                        hasProgress = false,
                        isAdmin = false,
                        canEditMetadata = true,
                        onBackClick = onBack,
                        onEditClick = {},
                        onFindMetadataClick = {},
                        onEditChaptersClick = {},
                        onMarkCompleteClick = {},
                        onMarkNotStartedClick = {},
                        onRestartClick = {},
                        onAddToShelfClick = {},
                        onAddToCollectionClick = {},
                        onShareClick = {},
                        onDeleteClick = {},
                    )
                }
            }
        }
    }
}
