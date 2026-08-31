package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The selection toolbar's actions, and the one rule that governs which of them appear.
 *
 * A null callback means "this host cannot do that" — the action is not drawn at all, rather than
 * drawn and dead. That idiom already carried "Add to collection" for non-admins; bulk editing is
 * the second action to use it, and it is the one that matters most: a screen reached through a
 * button that silently does nothing is worse than a button that was never there.
 *
 * These render the toolbar directly, with no ViewModel, so each assertion is about the toolbar's
 * own decision and nothing upstream of it. The admin gate that supplies the callback is pinned
 * separately in [BookSelectionScaffoldTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h2400dp")
class SelectionToolbarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        selectedCount: Int = 3,
        onAddToCollection: (() -> Unit)? = {},
        onEdit: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                SelectionToolbar(
                    selectedCount = selectedCount,
                    onAddToShelf = {},
                    onAddToCollection = onAddToCollection,
                    onEdit = onEdit,
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun `a host that has not wired editing is offered no edit action`() {
        render(onEdit = null)

        // The toolbar itself is up — so the absence below is the rule, not an unrendered screen.
        composeRule.onNodeWithText("Shelf").assertIsDisplayed()
        composeRule.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun `a host that wired editing is offered the edit action`() {
        render(onEdit = {})

        composeRule.onNodeWithText("Edit").assertIsDisplayed()
    }

    @Test
    fun `the edit action reaches the host that wired it`() {
        var edited = false
        render(onEdit = { edited = true })

        composeRule.onNodeWithText("Edit").performClick()

        edited shouldBe true
    }

    @Test
    fun `editing is unavailable while nothing is selected`() {
        // Selection mode can be armed before a single book is tapped; editing nothing is not an
        // offer worth making, exactly as with the shelf and collection actions beside it.
        render(selectedCount = 0, onEdit = {})

        composeRule.onNodeWithText("Edit").assertIsNotEnabled()
    }
}
