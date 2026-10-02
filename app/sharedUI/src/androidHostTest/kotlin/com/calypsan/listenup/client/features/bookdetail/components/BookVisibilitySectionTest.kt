package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.CollectionRef
import com.calypsan.listenup.client.domain.model.HiddenFrom
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The book-detail Visibility card, against the canvas copy for every state. */
@RunWith(RobolectricTestRunner::class)
class BookVisibilitySectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var opened: String? = null
    private var restores = 0
    private var pickers = 0

    private fun show(
        visibility: BookVisibility,
        isRestoring: Boolean = false,
        canOpen: Boolean = true,
    ) {
        composeRule.setContent {
            MaterialTheme {
                BookVisibilitySection(
                    visibility = visibility,
                    isRestoring = isRestoring,
                    onCollectionClick = if (canOpen) ({ opened = it }) else null,
                    onShowToAllMembers = { restores++ },
                    onAddToCollection = { pickers++ },
                )
            }
        }
    }

    private val sciFi = listOf(CollectionRef("c1", "Sci-Fi Club"))
    private val three =
        listOf(CollectionRef("c1", "Bedtime Stories"), CollectionRef("c2", "Classics Shelf"), CollectionRef("c3", "Family"))

    @Test
    fun `named members lead, with the one collection as the reason`() {
        show(BookVisibility.Restricted(sciFi, HiddenFrom.Members(listOf("Alice", "Ben"))))
        composeRule.onNodeWithText("Visibility").assertExists()
        composeRule.onNodeWithText("Only admins see this").assertExists()
        composeRule.onNodeWithText("Hidden from Alice and Ben").assertExists()
        composeRule.onNodeWithText("Only people in Sci-Fi Club can see it.").assertExists()
        composeRule.onNodeWithText("In 1 collection").assertExists()
    }

    @Test
    fun `a long list stops after three names, and Show all reveals the rest`() {
        show(BookVisibility.Restricted(three, HiddenFrom.Members(listOf("Alice", "Dev", "Hana", "Lee", "Zoe"))))
        composeRule.onNodeWithText("Hidden from Alice, Dev, Hana and 2 others").assertExists()
        composeRule.onNodeWithText("Anyone in at least one of these collections can see it.").assertExists()
        composeRule.onNodeWithText("In 3 collections").assertExists()

        composeRule.onNodeWithText("Show all 5").performClick()

        composeRule.onNodeWithText("Hidden from Alice, Dev, Hana, Lee and Zoe").assertExists()
        composeRule.onNodeWithText("Show all 5").assertDoesNotExist()
    }

    @Test
    fun `every member can see it`() {
        show(BookVisibility.Restricted(listOf(CollectionRef("c1", "Family")), HiddenFrom.Nobody))
        composeRule.onNodeWithText("Every member can see it").assertExists()
        composeRule.onNodeWithText("Every member is in Family.").assertExists()
    }

    @Test
    fun `hidden from all members, for a collection no one is in`() {
        show(BookVisibility.Restricted(listOf(CollectionRef("c1", "Drafts")), HiddenFrom.Everyone))
        composeRule.onNodeWithText("Hidden from all members").assertExists()
        composeRule.onNodeWithText("No member is in Drafts yet, so only admins can see it.").assertExists()
    }

    @Test
    fun `a collection name opens that collection`() {
        show(BookVisibility.Restricted(three, HiddenFrom.Nobody))
        composeRule.onNodeWithText("Classics Shelf").performClick()
        composeRule.runOnIdle { opened shouldBe "c2" }
    }

    @Test
    fun `with nowhere to open, the names are still shown`() {
        show(BookVisibility.Restricted(sciFi, HiddenFrom.Nobody), canOpen = false)
        composeRule.onNodeWithText("Sci-Fi Club").assertExists()
    }

    @Test
    fun `a stranded book offers both fixes, and Show to all members does not ask first`() {
        show(BookVisibility.Stranded)
        composeRule.onNodeWithText("Hidden from all members").assertExists()
        composeRule.onNodeWithText("It isn’t in any collection, so only admins can see it.").assertExists()

        composeRule.onNodeWithText("Show to all members").performClick()
        composeRule.onNodeWithText("Add to a collection").performClick()

        composeRule.runOnIdle {
            restores shouldBe 1
            pickers shouldBe 1
        }
    }

    @Test
    fun `while restoring, the fix says so`() {
        show(BookVisibility.Stranded, isRestoring = true)
        composeRule.onNodeWithText("Showing to all members…").assertExists()
    }

    @Test
    fun `public books render nothing`() {
        show(BookVisibility.Public)
        composeRule.onNodeWithText("Visibility").assertDoesNotExist()
    }

    @Test
    fun `held books render nothing — the inbox's held section owns them`() {
        show(BookVisibility.Held)
        composeRule.onNodeWithText("Visibility").assertDoesNotExist()
    }

    @Test
    fun `one name stands alone`() {
        show(BookVisibility.Restricted(sciFi, HiddenFrom.Members(listOf("Alice"))))
        composeRule.onNodeWithText("Hidden from Alice").assertExists()
    }

    @Test
    fun `three names are joined with a final and`() {
        show(BookVisibility.Restricted(sciFi, HiddenFrom.Members(listOf("Alice", "Ben", "Cy"))))
        composeRule.onNodeWithText("Hidden from Alice, Ben and Cy").assertExists()
        composeRule.onNodeWithText("Show all 3").assertDoesNotExist()
    }

    @Test
    fun `four names count the one other in the singular`() {
        show(BookVisibility.Restricted(sciFi, HiddenFrom.Members(listOf("Alice", "Ben", "Cy", "Dev"))))
        composeRule.onNodeWithText("Hidden from Alice, Ben, Cy and 1 other").assertExists()
        composeRule.onNodeWithText("Show all 4").assertExists()
    }

    @Test
    fun `every member can see it, across several collections`() {
        show(BookVisibility.Restricted(three, HiddenFrom.Nobody))
        composeRule.onNodeWithText("Every member can see it").assertExists()
        composeRule.onNodeWithText("Every member is in at least one of these collections.").assertExists()
    }

    @Test
    fun `hidden from all members, across several collections no one is in`() {
        show(BookVisibility.Restricted(three, HiddenFrom.Everyone))
        composeRule.onNodeWithText("Hidden from all members").assertExists()
        composeRule
            .onNodeWithText("No member is in any of these collections yet, so only admins can see it.")
            .assertExists()
    }
}
