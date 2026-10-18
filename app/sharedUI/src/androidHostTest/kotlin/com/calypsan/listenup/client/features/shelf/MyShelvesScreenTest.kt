package com.calypsan.listenup.client.features.shelf

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.presentation.shelf.MyShelvesUiState
import com.calypsan.listenup.core.ShelfId
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The My shelves list on Android (spec §3.2 H24). */
@RunWith(RobolectricTestRunner::class)
class MyShelvesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun shelf(name: String) =
        Shelf(
            id = ShelfId("s-$name"),
            name = name,
            description = null,
            isPrivate = false,
            ownerId = "me",
            ownerDisplayName = "Simon",
            bookCount = 3,
            totalDurationSeconds = 36_000L,
            createdAtMs = 0,
            updatedAtMs = 0,
        )

    private val twoShelves = listOf(shelf("Winter"), shelf("Road trip"))

    private fun show(
        state: MyShelvesUiState,
        onBack: () -> Unit = {},
        onOpenShelf: (String) -> Unit = {},
        onCreateShelf: () -> Unit = {},
    ) = composeRule.setContent {
        MaterialTheme { MyShelvesContent(state, onBack = onBack, onOpenShelf = onOpenShelf, onCreateShelf = onCreateShelf) }
    }

    @Test
    fun `the list draws every shelf as Home's card, under My shelves`() {
        show(MyShelvesUiState.Ready(twoShelves))
        composeRule.onNodeWithText("My shelves").assertIsDisplayed()
        twoShelves.forEach { composeRule.onNodeWithText(it.name).assertIsDisplayed() }
    }

    @Test
    fun `New shelf is in the top bar`() {
        var created = false
        show(MyShelvesUiState.Ready(twoShelves), onCreateShelf = { created = true })
        composeRule.onNodeWithContentDescription("New shelf").performClick()
        created shouldBe true
    }

    @Test
    fun `a shelf opens itself`() {
        var opened: String? = null
        show(MyShelvesUiState.Ready(twoShelves), onOpenShelf = { opened = it })
        composeRule.onNodeWithText("Road trip").performClick()
        opened shouldBe "s-Road trip"
    }

    @Test
    fun `with no shelves the list shows the invitation`() {
        show(MyShelvesUiState.Ready(emptyList()))
        composeRule
            .onNodeWithText("A shelf is your own list of audiobooks — a series, a mood, a plan for the winter.")
            .assertIsDisplayed()
    }

    @Test
    fun `a failed read says so`() {
        show(MyShelvesUiState.Error(UnexpectedClientError()))
        composeRule.onNodeWithText("Couldn't load your shelves.").assertIsDisplayed()
    }
}
