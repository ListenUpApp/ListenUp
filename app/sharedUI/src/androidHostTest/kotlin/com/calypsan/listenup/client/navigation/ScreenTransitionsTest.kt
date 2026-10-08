package com.calypsan.listenup.client.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigationevent.NavigationEvent
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the screen-change grammar: a push or pop moves screens a short shared-axis step, never the
 * full-width slide that read as an iOS push, and predictive back drifts the leaving screen away from
 * the edge the swipe came from.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
class ScreenTransitionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a push brings the new screen in from one short step ahead`() {
        val left = incomingLeftEdgeEarlyIn { motion, travelPx -> sharedAxisXPush(motion, travelPx) }
        left shouldBeGreaterThan 0f
        left shouldBeLessThan SharedAxisTravel.value + 1f
    }

    @Test
    fun `a pop brings the previous screen in from one short step behind`() {
        val left = incomingLeftEdgeEarlyIn { motion, travelPx -> sharedAxisXPop(motion, travelPx) }
        left shouldBeLessThan 0f
        left shouldBeGreaterThan -(SharedAxisTravel.value + 1f)
    }

    @Test
    fun `predictive back drifts away from the swipe's edge`() {
        predictiveBackDirection(NavigationEvent.EDGE_LEFT) shouldBe 1
        predictiveBackDirection(NavigationEvent.EDGE_RIGHT) shouldBe -1
        predictiveBackDirection(NavigationEvent.EDGE_NONE) shouldBe 0
    }

    /** The incoming screen's left edge, in dp, early in the transition. */
    private fun incomingLeftEdgeEarlyIn(transform: (MotionScheme, Int) -> ContentTransform): Float {
        var page by mutableIntStateOf(0)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val travelPx = with(LocalDensity.current) { SharedAxisTravel.roundToPx() }
            AnimatedContent(
                targetState = page,
                transitionSpec = { transform(MotionScheme.expressive(), travelPx) },
                label = "test",
            ) { shown ->
                Box(Modifier.fillMaxSize().testTag("page-$shown"))
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnUiThread {
            page = 1
            Snapshot.sendApplyNotifications()
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(EARLY_IN_TRANSITION_MS)
        composeRule.waitForIdle()
        // positionInRoot, not boundsInRoot: bounds are clipped to the root, which pins an edge that
        // starts off-screen to zero.
        val leftPx =
            composeRule
                .onNodeWithTag("page-1")
                .fetchSemanticsNode()
                .positionInRoot.x
        return leftPx / composeRule.density.density
    }

    private companion object {
        const val EARLY_IN_TRANSITION_MS = 48L
    }
}
