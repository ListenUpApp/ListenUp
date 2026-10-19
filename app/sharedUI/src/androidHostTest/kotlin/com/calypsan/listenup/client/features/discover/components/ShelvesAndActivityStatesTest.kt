package com.calypsan.listenup.client.features.discover.components

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec §3.8 for Shelves by others and Activity: each keeps its heading while loading or failed, says
 * "need a connection" only when the failure is connectivity (plan Q3), and has its own Try again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w412dp-h3000dp")
class ShelvesAndActivityStatesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun showShelves(
        state: DiscoverShelvesUiState,
        onRetry: () -> Unit = {},
    ) = composeRule.setContent {
        WithAvatarDependencies {
            ShelvesByOthersSection(state = state, onShelfClick = {}, onUserClick = {}, onRetry = onRetry)
        }
    }

    private fun showActivity(
        state: ActivityFeedUiState,
        onRetry: () -> Unit = {},
    ) = composeRule.setContent {
        WithAvatarDependencies {
            ActivityFeedSection(
                state = state,
                onBookClick = {},
                onShelfClick = {},
                onUserClick = {},
                onRetry = onRetry,
            )
        }
    }

    @Test
    fun `shelves loading keeps the heading and shows a skeleton`() {
        showShelves(DiscoverShelvesUiState.Loading)
        composeRule.onNode(hasText("Shelves by others") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithTag("shelves-skeleton").assertIsDisplayed()
    }

    @Test
    fun `shelves loading stands in for an owner's header above the shelf rail`() {
        showShelves(DiscoverShelvesUiState.Loading)
        val header = composeRule.onNodeWithTag("shelves-owner-skeleton").assertHeightIsEqualTo(48.dp)
        val rail = composeRule.onNodeWithTag("shelves-skeleton")
        assertTrue(header.getUnclippedBoundsInRoot().bottom <= rail.getUnclippedBoundsInRoot().top)
    }

    @Test
    fun `offline says shelves need a connection, with Try again`() {
        var retried = 0
        showShelves(DiscoverShelvesUiState.Error(TransportError.NetworkUnavailable()), onRetry = { retried++ })
        composeRule.onNodeWithText("Shelves by others need a connection").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `any other failure does not blame the connection (Q3)`() {
        showShelves(DiscoverShelvesUiState.Error(UnexpectedClientError()))
        composeRule.onNodeWithText("Couldn't load shelves by others").assertIsDisplayed()
        composeRule.onNodeWithText("Shelves by others need a connection").assertDoesNotExist()
    }

    @Test
    fun `no public shelves says where they will appear, and keeps its heading`() {
        showShelves(DiscoverShelvesUiState.Ready(emptyList()))
        composeRule.onNode(hasText("Shelves by others") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Shelves people make public show up here.").assertIsDisplayed()
    }

    @Test
    fun `activity loading is a skeleton of rows under its heading`() {
        showActivity(ActivityFeedUiState.Loading)
        composeRule.onNode(hasText("Activity") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithTag("activity-skeleton").assertIsDisplayed()
    }

    @Test
    fun `an activity failure is a line and its own Try again`() {
        var retried = 0
        showActivity(ActivityFeedUiState.Error(UnexpectedClientError()), onRetry = { retried++ })
        composeRule.onNodeWithText("Couldn't load activity").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }
}
