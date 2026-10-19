package com.calypsan.listenup.client.features.discover.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Rank by's unselected segments sit on the leaderboard card's surfaceContainer. With the group's default
 * colours they were that same surfaceContainer, so only the selected segment drew a shape; the board gives
 * the others a container of their own.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RankByContainerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun anUnselectedRankBySegmentDrawsAContainerDistinctFromTheCard() {
        var card = Color.Unspecified
        var segment = Color.Unspecified
        composeRule.setContent {
            ListenUpTheme(darkTheme = false, dynamicColor = false) {
                card = MaterialTheme.colorScheme.surfaceContainer
                segment = MaterialTheme.colorScheme.surfaceContainerHighest
                DiscoverLeaderboardSection(
                    state = LeaderboardUiState.Loading,
                    selection = LeaderboardSelection(LeaderboardPeriod.Week, LeaderboardCategory.Time),
                    community = CommunitySize.Others,
                    onSelectPeriod = {},
                    onSelectCategory = {},
                    onUserClick = {},
                    onRetry = {},
                )
            }
        }

        val books =
            composeRule
                .onNode(hasContentDescription("Books") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .captureToImage()
                .toPixelMap()
        // Beside the label, clear of the rounded ends: the segment's own container.
        val container = books[books.width / 6, books.height / 2]

        card shouldNotBe segment
        withClue("the unselected Books segment is the card's own colour, so it draws no container") {
            container.isCloseTo(card) shouldBe false
        }
        container.isCloseTo(segment) shouldBe true
    }

    private fun Color.isCloseTo(other: Color) =
        abs(red - other.red) < TOLERANCE && abs(green - other.green) < TOLERANCE && abs(blue - other.blue) < TOLERANCE

    private companion object {
        const val TOLERANCE = 0.02f
    }
}
