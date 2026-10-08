package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.ListenerRating
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The rate sheet: nothing to save without a star, and nothing to remove before you have rated. */
@RunWith(RobolectricTestRunner::class)
class RateBookSheetRatingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `Save is disabled until a star is chosen, and Remove is absent before you rate`() {
        composeRule.setContent {
            RateBookSheet(current = null, onSave = { _, _ -> }, onClear = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithText("Remove rating").assertDoesNotExist()
    }

    @Test
    fun `opening on your rating enables Save and offers Remove`() {
        val mine = ListenerRating(bookId = "b1", userId = "me", halfStars = 7, note = "Great", ratedAtMs = 1L)
        composeRule.setContent {
            RateBookSheet(current = mine, onSave = { _, _ -> }, onClear = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("Save").assertIsEnabled()
        composeRule.onNodeWithText("Remove rating").assertIsDisplayed()
    }
}
