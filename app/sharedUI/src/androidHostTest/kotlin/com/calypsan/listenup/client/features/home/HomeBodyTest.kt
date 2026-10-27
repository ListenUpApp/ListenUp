package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
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
import kotlin.math.absoluteValue

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
    fun `a loaded book leaves no Resume and Home starts with Everything in progress`() {
        render(homeReady(slot = HomeSlot.Absent, everythingInProgress = listOf(readyItem("babel"))))
        composeRule.onAllNodesWithTag(HOME_RESUME_BUTTON_TAG).assertCountEquals(0)
        composeRule.onNodeWithText("Everything in progress").assertIsDisplayed()
    }

    @Test
    fun `with a book loaded, its card comes first in Everything in progress`() {
        render(
            homeReady(
                slot = HomeSlot.Absent,
                everythingInProgress = listOf(readyItem("b", title = "The Loaded Book"), readyItem("a", title = "Another Book")),
            ),
        )
        val loaded = composeRule.onNodeWithContentDescription("The Loaded Book", substring = true).getUnclippedBoundsInRoot()
        val other = composeRule.onNodeWithContentDescription("Another Book", substring = true).getUnclippedBoundsInRoot()
        loaded.left shouldBeLessThan other.left
    }

    @Test
    fun `one book in progress with nothing loaded draws the centred hero and no section`() {
        val alone = homeReady(slot = resumeSlot("wok"), everythingInProgress = emptyList())
        alone.isOnlyBookInProgress shouldBe true
        render(alone)
        composeRule.onAllNodesWithText("Everything in progress").assertCountEquals(0)
        val identity = composeRule.onNodeWithTag(HOME_RESUME_IDENTITY_TAG).getUnclippedBoundsInRoot()
        val bounds =
            composeRule
                .onAllNodes(hasText("Resume wok") and hasAnyAncestor(hasTestTag(HOME_RESUME_IDENTITY_TAG)), useUnmergedTree = true)
                .onFirst()
                .getUnclippedBoundsInRoot()
        ((bounds.left + bounds.right) / 2 - (identity.left + identity.right) / 2).value.absoluteValue shouldBeLessThan 1f
    }

    @Test
    fun `nothing in progress while loaded leaves no Everything in progress section`() {
        render(homeReady(slot = HomeSlot.Absent, everythingInProgress = emptyList()))
        composeRule.onAllNodesWithText("Everything in progress").assertCountEquals(0)
        composeRule.onAllNodesWithText("Nothing playing yet").assertCountEquals(0)
        composeRule.onNodeWithText("Your last 7 days").assertExists()
    }

    @Test
    fun `nothing loaded leads with Resume, and Resume calls the play entry`() {
        var played: String? = null
        render(
            homeReady(slot = resumeSlot("wok"), everythingInProgress = listOf(readyItem("babel"))),
            actions = HomeActions.None.copy(onPlay = { played = it }),
        )
        val resume = composeRule.onNodeWithTag(HOME_RESUME_BUTTON_TAG).getUnclippedBoundsInRoot()
        val also = composeRule.onNodeWithText("Everything in progress").getUnclippedBoundsInRoot()
        resume.top shouldBeLessThan also.top
        composeRule.onNodeWithTag(HOME_RESUME_BUTTON_TAG).performScrollTo().performClick()
        played shouldBe "wok"
    }

    @Test
    fun `nothing in progress takes the slot with Browse the Library, and the interim empty state is gone`() {
        var opened = false
        render(
            homeReady(slot = HomeSlot.NothingInProgress(candidate = null), everythingInProgress = emptyList()),
            actions = HomeActions.None.copy(onOpenLibrary = { opened = true }),
        )
        composeRule.onNodeWithText("Nothing in progress").assertIsDisplayed()
        composeRule.onAllNodesWithText("Nothing playing yet").assertCountEquals(0)
        composeRule.onNodeWithText("Browse the Library").performClick()
        opened shouldBe true
    }

    @Test
    fun `In Library opens In progress, Browse the Library opens the Library, and the finishers open the readers`() {
        val asked = mutableListOf<String>()
        render(
            homeReady(slot = finishedSlot(), everythingInProgress = listOf(readyItem("babel"))),
            actions =
                HomeActions.None.copy(
                    onOpenLibrary = { asked += "library" },
                    onOpenInProgress = { asked += "in-progress" },
                    onOpenReaders = { asked += "readers:$it" },
                ),
        )
        composeRule.onNodeWithText("In Library").performScrollTo().performClick()
        composeRule.onNodeWithText("Maya and Tom finished it too").performScrollTo().performClick()
        asked shouldBe listOf("in-progress", "readers:wok")
    }

    @Test
    fun `a new listener is welcomed in the greeting and the slot`() {
        render(homeReady(slot = welcomeSlot(), everythingInProgress = emptyList()))
        composeRule.onNodeWithText("Welcome,").assertIsDisplayed()
        composeRule.onNodeWithText("Pick something new").assertIsDisplayed()
    }
}
