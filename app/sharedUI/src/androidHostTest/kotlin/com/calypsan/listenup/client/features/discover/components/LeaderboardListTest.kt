package com.calypsan.listenup.client.features.discover.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.features.discover.entry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The board shows the top five then "Show all N"; your row reads "You"; a row is read rank first and opens the profile. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w412dp-h2000dp")
class LeaderboardListTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private val nine = (1..9).map { entry(rank = it, userId = "u$it", name = "Person $it", seconds = (10 - it) * 3_600L, isYou = it == 2) }

    private fun show(
        entries: List<LeaderboardEntry>,
        onUserClick: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            WithAvatarDependencies { LeaderboardList(entries, LeaderboardCategory.Time, onUserClick) }
        }
    }

    @Test
    fun topFiveThenShowAllNine() {
        show(nine)
        composeRule.onAllNodes(hasText("Person", substring = true) or hasText("You")).assertCountEquals(5)
        composeRule.onNodeWithText("Show all 9").performClick()
        composeRule.onAllNodes(hasText("Person", substring = true) or hasText("You")).assertCountEquals(9)
        composeRule.onNodeWithText("Show less").assertExists()
    }

    @Test
    fun yourRowReadsYou() {
        show(nine)
        composeRule.onNodeWithText("You").assertExists()
        composeRule.onNodeWithText("Person 2").assertDoesNotExist()
    }

    @Test
    fun aRowIsReadRankFirstAndOpensTheProfile() {
        var opened: String? = null
        show(nine, onUserClick = { opened = it })
        val first = composeRule.onNode(hasText("Person 1") and hasClickAction())
        first.assert(
            SemanticsMatcher("rank read first") { node ->
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                val firstRead = description.firstOrNull() ?: text.firstOrNull()?.text
                firstRead.orEmpty().startsWith("Rank 1")
            },
        )
        first.performClick()
        assertEquals("u1", opened)
    }
}
