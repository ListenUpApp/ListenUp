package com.calypsan.listenup.client.features.library

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.core.ShelfId
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The single-book "Add to shelf" picker says which shelves already hold the book, as iOS's does —
 * a check for the eye, "Selected" for TalkBack — and, like iOS, tapping one still picks it.
 */
@RunWith(RobolectricTestRunner::class)
class ShelfPickerSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun shelf(
        id: String,
        name: String,
    ) = Shelf(
        id = ShelfId(id),
        name = name,
        description = null,
        isPrivate = false,
        ownerId = "u1",
        ownerDisplayName = "Simon",
        bookCount = 0,
        totalDurationSeconds = 0L,
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )

    private val bedtime = shelf("s1", "Bedtime")
    private val commute = shelf("s2", "Commute")
    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected")

    @Test
    fun `a shelf that already holds the book is marked selected and the others are not`() {
        composeRule.setContent {
            ShelfPickerSheet(
                shelves = listOf(bedtime, commute),
                selectedBookCount = 1,
                onShelfSelected = {},
                onCreateAndAddToShelf = {},
                onDismiss = {},
                shelvesContainingBook = listOf(bedtime),
            )
        }

        composeRule.onNode(hasClickAction() and hasText("Bedtime")).assert(selected)
        composeRule
            .onNode(hasClickAction() and hasText("Commute"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun `tapping a shelf that already holds the book still picks it`() {
        val picked = mutableListOf<String>()
        composeRule.setContent {
            ShelfPickerSheet(
                shelves = listOf(bedtime),
                selectedBookCount = 1,
                onShelfSelected = { picked += it },
                onCreateAndAddToShelf = {},
                onDismiss = {},
                shelvesContainingBook = listOf(bedtime),
            )
        }

        composeRule.onNode(hasClickAction() and hasText("Bedtime")).performClick()

        picked shouldContainExactly listOf("s1")
    }
}
