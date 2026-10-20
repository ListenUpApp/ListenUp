package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.library.BookMenuPrimary
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A Library book's menu: what it leads with, the board's order, and who sees the Admin group. */
@RunWith(RobolectricTestRunner::class)
class LibraryBookMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(
        primary: BookMenuPrimary,
        isAdmin: Boolean = false,
        canShare: Boolean = true,
    ) = composeRule.setContent {
        MaterialTheme {
            Box {
                LibraryBookMenu(
                    expanded = true,
                    onDismiss = { calls += "dismiss" },
                    primary = primary,
                    actions =
                        LibraryBookMenuActions(
                            onPrimary = { calls += "play" },
                            onAddToShelf = { calls += "shelf" },
                            onShare = if (canShare) ({ calls += "share" }) else null,
                            onSelect = { calls += "select" },
                            onAddToCollection = if (isAdmin) ({ calls += "collection" }) else null,
                            onEditDetails = if (isAdmin) ({ calls += "edit" }) else null,
                        ),
                )
            }
        }
    }

    private fun itemLabels(): List<String> =
        composeRule
            .onAllNodes(hasClickAction())
            .fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } }

    @Test
    fun `a started book leads with Resume and its time left`() {
        show(BookMenuPrimary.Resume(timeLeftMs = 21_720_000L))

        composeRule.onNodeWithText("Resume · 6h 2m").assertExists()
    }

    @Test
    fun `a finished book is listened to again`() {
        show(BookMenuPrimary.ListenAgain)

        composeRule.onNodeWithText("Listen again").assertExists()
    }

    @Test
    fun `the board's order, and Select is one tap that closes the menu`() {
        show(BookMenuPrimary.Play)

        itemLabels() shouldContainExactly listOf("Play", "Add to shelf…", "Share…", "Select")
        composeRule.onNodeWithText("Select").performClick()
        composeRule.runOnIdle { calls shouldBe listOf("select", "dismiss") }
    }

    @Test
    fun `an admin also sees the Admin group, last`() {
        show(BookMenuPrimary.Play, isAdmin = true)

        composeRule.onNodeWithText("Admin").assertExists()
        itemLabels() shouldContainExactly
            listOf("Play", "Add to shelf…", "Share…", "Select", "Add to collection…", "Edit details")
    }

    @Test
    fun `a member never sees it, and Share hides without a link`() {
        show(BookMenuPrimary.Play, isAdmin = false, canShare = false)

        composeRule.onNodeWithText("Admin").assertDoesNotExist()
        composeRule.onNodeWithText("Share…").assertDoesNotExist()
        composeRule.onNodeWithText("Add to collection…").assertDoesNotExist()
    }
}
