package com.calypsan.listenup.client.features.discover

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** See all activity (X33): the whole feed in the section's own row, with the section's loading and failure. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w412dp-h3000dp")
class ActivityFeedScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun row(id: String) =
        ActivityUiModel(
            id = id,
            userId = "maya",
            type = "finished_book",
            occurredAt = NOW_MS,
            userDisplayName = "Maya Chen",
            bookId = "b-$id",
            bookTitle = "Piranesi $id",
            bookAuthorName = "Susanna Clarke",
            bookCoverPath = "/tmp/$id.webp",
            bookCoverHash = null,
            isReread = false,
            durationMs = 0,
            milestoneValue = 0,
            milestoneUnit = null,
            shelfId = null,
            shelfName = null,
            isYou = false,
        )

    private fun show(
        state: ActivityFeedUiState,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            WithAvatarDependencies {
                ActivityFeedContent(
                    state = state,
                    onBack = onBack,
                    onRetry = onRetry,
                    onRefresh = {},
                    onBookClick = {},
                    onShelfClick = {},
                    onUserClick = {},
                    onAddToShelf = {},
                )
            }
        }
    }

    @Test
    fun theScreenShowsEveryRowTitledActivityWithABackButton() {
        var back = 0
        show(ActivityFeedUiState.Ready(List(40) { row("a$it") }), onBack = { back++ })
        composeRule.onNode(hasText("Activity") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithTag("activity-feed").performScrollToIndex(39)
        composeRule.onNodeWithText("Piranesi a39", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    @Test
    fun loadingIsTheSectionsSkeleton() {
        show(ActivityFeedUiState.Loading)
        composeRule.onNodeWithTag("activity-skeleton").assertIsDisplayed()
    }

    @Test
    fun aFailureSaysSoAndTryAgainRetries() {
        var retried = 0
        show(ActivityFeedUiState.Error(TransportError.NetworkUnavailable()), onRetry = { retried++ })
        composeRule.onNodeWithText("Couldn't load activity").assertIsDisplayed()
        composeRule.onNode(hasText("Try again") and hasClickAction()).performClick()
        assertEquals(1, retried)
    }
}
