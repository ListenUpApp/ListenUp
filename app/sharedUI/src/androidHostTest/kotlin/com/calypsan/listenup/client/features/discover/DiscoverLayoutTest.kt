package com.calypsan.listenup.client.features.discover

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Section headings top to bottom; each section's task adds its own as it takes the spec's wording. */
private val DISCOVER_HEADINGS_IN_ORDER =
    listOf(
        "Listening now",
        "Something new",
        "Leaderboard",
        "Activity",
        "Shelves by others",
        "Recently added",
    )

/** Discover is one stateless layout: every section draws from [DiscoverState] alone, in reading order. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w412dp-h4000dp")
class DiscoverLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun render(state: DiscoverState) {
        composeRule.setContent {
            WithAvatarDependencies {
                DiscoverLayout(
                    state,
                    noActions(),
                    appHeader = { it() },
                    contentPadding = PaddingValues(),
                    isInSelectionMode = false,
                    selectedBookIds = emptySet(),
                )
            }
        }
    }

    @Test
    fun discoverLeadsWithPeopleAndEndsWithRecentlyAdded() {
        render(discoverState(shelves = DiscoverShelvesUiState.Ready(listOf(shelfOwner("maya", "Maya Chen", shelf("sh1", "Space"))))))
        val tops =
            DISCOVER_HEADINGS_IN_ORDER
                .map { composeRule.onNode(hasText(it) and isHeading()).getUnclippedBoundsInRoot().top }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun everySectionRendersFromStateAlone() {
        render(discoverState())
        composeRule.onNodeWithText("Leaderboard").assertExists()
        composeRule.onAllNodesWithText("Orbital", useUnmergedTree = true).onFirst().assertExists()
        composeRule.onAllNodesWithText("Mistborn", useUnmergedTree = true).onFirst().assertExists()
    }
}
