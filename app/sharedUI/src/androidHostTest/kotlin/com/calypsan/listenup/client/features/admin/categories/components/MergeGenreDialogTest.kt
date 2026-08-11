package com.calypsan.listenup.client.features.admin.categories.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.Genre
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Merging a genre is destructive and cannot be undone, so a single tap on a row in a
 * scrolling list must never commit it. These tests pin the two-step shape.
 *
 * JUnit4 + Robolectric — the canonical shape for Compose UI tests in this module
 * (`createComposeRule()` requires JUnit4); Robolectric supplies the real Android
 * resource environment so `stringResource` resolves the packaged English strings.
 */
@RunWith(RobolectricTestRunner::class)
class MergeGenreDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tapping a candidate does not merge`() {
        var merged: String? = null
        composeRule.setContent {
            MaterialTheme {
                MergeGenreDialog(
                    sourceName = SOURCE_NAME,
                    sourceBookCount = 3,
                    candidates = listOf(TARGET),
                    onConfirm = { merged = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(TARGET_NAME).performClick()

        merged shouldBe null
    }

    @Test
    fun `the confirm step names both genres and the book count`() {
        composeRule.setContent {
            MaterialTheme {
                MergeGenreDialog(
                    sourceName = SOURCE_NAME,
                    sourceBookCount = 3,
                    candidates = listOf(TARGET),
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(TARGET_NAME).performClick()

        composeRule
            .onNodeWithText("Moving “$SOURCE_NAME” into “$TARGET_NAME”. 3 books will move.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("This action cannot be undone.").assertIsDisplayed()
    }

    @Test
    fun `confirming merges into the chosen target`() {
        var merged: String? = null
        composeRule.setContent {
            MaterialTheme {
                MergeGenreDialog(
                    sourceName = SOURCE_NAME,
                    sourceBookCount = 1,
                    candidates = listOf(TARGET),
                    onConfirm = { merged = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(TARGET_NAME).performClick()
        composeRule.onNodeWithText(MERGE_BUTTON).performClick()

        merged shouldBe TARGET_ID
    }

    @Test
    fun `going back returns to the candidate list without merging`() {
        var merged: String? = null
        composeRule.setContent {
            MaterialTheme {
                MergeGenreDialog(
                    sourceName = SOURCE_NAME,
                    sourceBookCount = 3,
                    candidates = listOf(TARGET),
                    onConfirm = { merged = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(TARGET_NAME).performClick()
        composeRule.onNodeWithText(BACK_BUTTON).performClick()

        composeRule.onNodeWithText(TARGET_PATH).assertIsDisplayed()
        merged shouldBe null
    }

    private companion object {
        const val SOURCE_NAME = "Epic Fantasy"
        const val TARGET_ID = "genre-2"
        const val TARGET_NAME = "Fantasy"
        const val TARGET_PATH = "/fiction/fantasy"
        const val MERGE_BUTTON = "Merge"
        const val BACK_BUTTON = "Back"

        val TARGET =
            Genre(
                id = TARGET_ID,
                name = TARGET_NAME,
                slug = "fantasy",
                path = TARGET_PATH,
                bookCount = 12,
            )
    }
}
