package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        onClose: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    SelectionToolbar(
                        selectedCount = selectedCount,
                        onAddToShelf = {},
                        onAddToCollection = onAddToCollection,
                        onEdit = onEdit,
                        onClose = onClose,
                    )
                }
            }
        }
    }

    @Test
    fun `a host that has not wired editing is offered no edit action`() {
        render(onEdit = null)

        // The toolbar itself is up — so the absence below is the rule, not an unrendered screen.
        composeRule.onNodeWithText("Add to shelf").assertIsDisplayed()
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

    @Test
    fun `the top bar names the count and Cancel selection, and the actions float at the bottom with full labels`() {
        render(selectedCount = 2, onEdit = {})

        composeRule.onNodeWithContentDescription("Cancel selection").assertIsDisplayed()
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        val actions = composeRule.onNodeWithTag("selection-actions").fetchSemanticsNode().boundsInRoot
        val header = composeRule.onNodeWithTag("selection-header").fetchSemanticsNode().boundsInRoot
        (actions.top > header.bottom + 200f) shouldBe true
        listOf("Add to shelf", "Add to collection", "Edit").forEach {
            composeRule.onNodeWithText(it).assertHasClickAction()
        }
    }

    @Test
    fun `a member sees Add to shelf alone, and one book reads 1 selected`() {
        render(selectedCount = 1, onAddToCollection = null, onEdit = null)

        composeRule.onNodeWithText("Add to shelf").assertIsDisplayed()
        composeRule.onNodeWithText("Add to collection").assertDoesNotExist()
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test
    fun `Cancel selection ends the selection`() {
        var closed = 0
        render(onClose = { closed++ })

        composeRule.onNodeWithContentDescription("Cancel selection").performClick()

        closed shouldBe 1
    }

    @Test
    fun `every action is at least the 48dp touch target`() {
        render(onEdit = {})

        listOf("Add to shelf", "Add to collection", "Edit").forEach {
            composeRule.onNode(hasText(it) and hasClickAction()).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `a host that adds the toolbar's clearance keeps its last item above the floating toolbar`() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 16.dp).plusSelectionToolbar(isSelecting = true),
                        modifier = Modifier.fillMaxSize().testTag("host-list"),
                    ) {
                        items(60) { index ->
                            Text("Row $index", Modifier.fillMaxWidth().height(60.dp).testTag("row-$index"))
                        }
                    }
                    SelectionToolbar(
                        selectedCount = 2,
                        onAddToShelf = {},
                        onAddToCollection = {},
                        onEdit = {},
                        onClose = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("host-list").performScrollToIndex(59)
        composeRule.waitForIdle()

        val last = composeRule.onNodeWithTag("row-59").fetchSemanticsNode().boundsInRoot
        val toolbar = composeRule.onNodeWithTag("selection-actions").fetchSemanticsNode().boundsInRoot
        (last.bottom <= toolbar.top) shouldBe true
    }

    @Test
    fun `no clearance is added when nothing is being selected`() {
        val padding = PaddingValues(bottom = 16.dp).plusSelectionToolbar(isSelecting = false)
        padding.calculateBottomPadding() shouldBe 16.dp
    }

    @Test
    fun `the header starts below the host's top inset, as Library's does, instead of under the status bar`() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    SelectionToolbar(
                        selectedCount = 2,
                        onAddToShelf = {},
                        onAddToCollection = null,
                        onEdit = null,
                        onClose = {},
                        topClearance = 24.dp,
                    )
                }
            }
        }
        val header = composeRule.onNodeWithTag("selection-header").fetchSemanticsNode().boundsInRoot
        header.top shouldBe with(composeRule.density) { 24.dp.toPx() }
    }
}
