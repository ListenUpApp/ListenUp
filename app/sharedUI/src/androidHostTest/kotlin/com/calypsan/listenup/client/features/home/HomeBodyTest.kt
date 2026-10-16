package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec §2.1–§2.2 on screen: a loaded book is never drawn; nothing loaded leads with Resume. */
@RunWith(RobolectricTestRunner::class)
class HomeBodyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: HomeUiState.Ready,
        actions: HomeActions = HomeActions.None,
    ) = composeRule.setContent {
        MaterialTheme {
            HomeBody(
                state = state,
                stats = HomeStatsUiState.Loading,
                width = HomeWidth.Compact,
                actions = actions,
                selection = HomeSelection.None,
                appHeader = testAppHeader,
                contentPadding = PaddingValues(),
            )
        }
    }

    @Test
    fun `a loaded book leaves no Resume and Home starts with Also in progress`() {
        render(homeReady(slot = HomeSlot.Absent, alsoInProgress = listOf(readyItem("babel"))))
        composeRule.onAllNodesWithTag(HOME_RESUME_BUTTON_TAG).assertCountEquals(0)
        composeRule.onNodeWithText("Also in progress").assertIsDisplayed()
    }

    @Test
    fun `one book in progress and loaded leaves no Also in progress section`() {
        render(homeReady(slot = HomeSlot.Absent, alsoInProgress = emptyList()))
        composeRule.onAllNodesWithText("Also in progress").assertCountEquals(0)
        composeRule.onAllNodesWithText("Nothing playing yet").assertCountEquals(0)
        composeRule.onNodeWithText("Your last 7 days").assertExists()
    }

    @Test
    fun `nothing loaded leads with Resume, and Resume calls the play entry`() {
        var played: String? = null
        render(
            homeReady(slot = resumeSlot("wok"), alsoInProgress = listOf(readyItem("babel"))),
            actions = HomeActions.None.copy(onPlay = { played = it }),
        )
        val resume = composeRule.onNodeWithTag(HOME_RESUME_BUTTON_TAG).getUnclippedBoundsInRoot()
        val also = composeRule.onNodeWithText("Also in progress").getUnclippedBoundsInRoot()
        resume.top shouldBeLessThan also.top
        composeRule.onNodeWithTag(HOME_RESUME_BUTTON_TAG).performScrollTo().performClick()
        played shouldBe "wok"
    }

    @Test
    fun `until PR 4, nothing in progress shows the interim empty state with Browse the Library`() {
        var opened = false
        render(
            homeReady(slot = HomeSlot.NothingInProgress(candidate = null), alsoInProgress = emptyList()),
            actions = HomeActions.None.copy(onOpenLibrary = { opened = true }),
        )
        composeRule.onNodeWithText("Nothing playing yet").assertIsDisplayed()
        composeRule.onNodeWithText("Browse the Library").performClick()
        opened shouldBe true
    }
}
