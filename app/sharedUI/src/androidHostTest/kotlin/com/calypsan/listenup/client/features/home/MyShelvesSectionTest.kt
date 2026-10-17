package com.calypsan.listenup.client.features.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.ShelfCover
import com.calypsan.listenup.core.ShelfId
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec §2.7 on Android. */
@RunWith(RobolectricTestRunner::class)
class MyShelvesSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun shelf(
        name: String,
        books: Int,
        seconds: Long,
        isPrivate: Boolean = false,
    ) = Shelf(
        id = ShelfId("s-$name"),
        name = name,
        description = null,
        isPrivate = isPrivate,
        ownerId = "me",
        ownerDisplayName = "Simon",
        bookCount = books,
        totalDurationSeconds = seconds,
        createdAtMs = 0,
        updatedAtMs = 0,
        covers = listOf(ShelfCover("b1", coverPath = "/tmp/cover-b1.webp", coverHash = "h1")),
    )

    private fun show(
        shelves: List<Shelf>,
        width: HomeWidth = HomeWidth.Compact,
        onCreateShelf: () -> Unit = {},
        onOpenShelf: (String) -> Unit = {},
    ) = composeRule.setContent {
        MaterialTheme { MyShelvesSection(shelves, width, onOpenShelf = onOpenShelf, onCreateShelf = onCreateShelf) }
    }

    @Test
    fun `a shelf says how many books and how long, and a private one says so`() {
        show(listOf(shelf("Winter reads", 6, 266_400L), shelf("Road trip", 1, 111_600L, isPrivate = true)))
        composeRule.onNodeWithText("6 books · 74h").assertIsDisplayed()
        composeRule.onNodeWithText("1 book · 31h").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Private shelf").assertIsDisplayed()
    }

    @Test
    fun `New shelf is always there`() {
        var created = false
        show(emptyList(), onCreateShelf = { created = true })
        composeRule.onNodeWithText("New shelf").performClick()
        created shouldBe true
    }

    @Test
    fun `no shelves is an invitation, not a gap`() {
        show(emptyList())
        composeRule
            .onNodeWithText("A shelf is your own list of audiobooks — a series, a mood, a plan for the winter.")
            .assertIsDisplayed()
    }

    @Test
    fun `there is no See all until the list screen exists`() {
        show(listOf(shelf("Winter reads", 6, 266_400L)))
        composeRule.onAllNodesWithText("See all", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun `a shelf opens itself`() {
        var opened: String? = null
        show(listOf(shelf("Winter reads", 6, 266_400L)), width = HomeWidth.Expanded, onOpenShelf = { opened = it })
        composeRule.onNodeWithText("Winter reads").performClick()
        opened shouldBe "s-Winter reads"
    }
}
