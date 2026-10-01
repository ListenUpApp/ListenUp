package com.calypsan.listenup.client.features.bookedit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.features.bookedit.components.CollectionsSubsection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Choosing collections for a held book releases it (the server treats curation as release); Edit says so. */
@RunWith(RobolectricTestRunner::class)
class CollectionsHeldNoteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a held book's collections field says choosing releases it`() {
        setContent(isHeld = true)

        composeRule.onNodeWithText(NOTE).assertIsDisplayed()
    }

    @Test
    fun `an ordinary book's collections field says nothing extra`() {
        setContent(isHeld = false)

        composeRule.onNodeWithText(NOTE).assertDoesNotExist()
    }

    private fun setContent(isHeld: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                CollectionsSubsection(
                    collections = emptyList(),
                    searchQuery = "",
                    searchResults = emptyList(),
                    onSearchQueryChange = {},
                    onCollectionSelected = {},
                    onRemoveCollection = {},
                    isHeld = isHeld,
                )
            }
        }
    }

    private companion object {
        const val NOTE = "Choosing collections releases this book from the inbox."
    }
}
