package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertSideBySide
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * K2 (spec §9): a 1280×800dp tablet with a book loaded. The My shelves heading and the first row of shelf cards are
 * above the floating mini player (top at 712dp, board Home-AndroidTablet). The 96dp navigation rail is a start inset.
 */
@Config(qualifiers = Windows.TABLET)
// Native graphics: legacy text shaping inflates every line, which is not the geometry K1/K2 pin.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class HomeTabletGeometryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shelves are on the first screen of a tablet`() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.padding(start = 96.dp)) {
                    HomeBody(
                        state =
                            homeReady(
                                slot = HomeSlot.Absent,
                                everythingInProgress = (1..6).map { readyItem("b$it") },
                                shelves = fourShelves(),
                            ),
                        stats = weekWithStreak(),
                        width = HomeWidth.Expanded,
                        actions = HomeActions.None,
                        selection = HomeSelection.None,
                        appHeader = testAppHeader,
                        contentPadding = PaddingValues(top = 24.dp),
                    )
                }
            }
        }
        val miniPlayerTop = 712.dp
        composeRule.onNodeWithText("My shelves").getUnclippedBoundsInRoot().bottom shouldBeLessThanOrEqualTo miniPlayerTop
        composeRule.onNodeWithText("Shelf 1").getUnclippedBoundsInRoot().bottom shouldBeLessThanOrEqualTo miniPlayerTop
        composeRule.onNodeWithText("Shelf 2").getUnclippedBoundsInRoot().bottom shouldBeLessThanOrEqualTo miniPlayerTop
        // §3.4: stats and shelves share a row.
        assertSideBySide(composeRule.onNodeWithText("Your last 7 days"), composeRule.onNodeWithText("My shelves"))
    }
}
