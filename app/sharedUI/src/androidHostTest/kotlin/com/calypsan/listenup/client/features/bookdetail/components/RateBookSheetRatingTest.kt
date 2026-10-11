package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.performTextInput
import com.calypsan.listenup.client.design.util.LocalUnsavedWorkHolds
import com.calypsan.listenup.client.design.util.UnsavedWorkHolds
import io.kotest.matchers.shouldBe
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
    fun `a draft that differs from your rating holds unsaved work and an untouched one does not`() {
        val holds = UnsavedWorkHolds()
        val mine = ListenerRating(bookId = "b1", userId = "me", halfStars = 7, note = "Great", ratedAtMs = 1L)
        composeRule.setContent {
            CompositionLocalProvider(LocalUnsavedWorkHolds provides holds) {
                RateBookSheet(current = mine, onSave = { _, _ -> }, onClear = {}, onDismiss = {})
            }
        }
        composeRule.waitForIdle()
        holds.isHeld shouldBe false

        composeRule.onNodeWithText("Great").performTextInput("!")
        composeRule.waitForIdle()

        holds.isHeld shouldBe true
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
