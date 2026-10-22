package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.design.LocalInDetailPane
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailMenuGroup
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailMenuItem
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
    fun `on its own, the book detail offers Back to Library`() {
        var backs = 0
        setContent(inDetailPane = false) { backs++ }

        composeRule.onNodeWithContentDescription("Close book").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Back to Library").performClick()
        backs shouldBe 1
    }

    @Test
    fun `beside a list, the book detail offers Close, which closes the pane`() {
        var backs = 0
        setContent(inDetailPane = true) { backs++ }

        composeRule.onNodeWithContentDescription("Back to Library").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Close book").performClick()
        backs shouldBe 1
    }

    @Test
    fun `the top bar carries no title, so the hero is the only place the book is named`() {
        setContent(inDetailPane = false) {}
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Back to Library").assertExists()
    }

    private fun setContent(
        inDetailPane: Boolean,
        onBack: () -> Unit,
    ) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalInDetailPane provides inDetailPane) {
                    BookDetailTopBar(
                        onBackClick = onBack,
                        menu = listOf(BookDetailMenuGroup(heading = null, items = listOf(BookDetailMenuItem.Share))),
                        progressBusy = false,
                        onMenuItem = {},
                        isHeld = false,
                    )
                }
            }
        }
    }
}
