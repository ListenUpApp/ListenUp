package com.calypsan.listenup.client.features.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A link elsewhere can open a tab somewhere other than its top, once: the shell switches the tab and
 * holds the landing until the screen it is meant for takes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ShellLandingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aRequestSelectsTheLandingsTab() {
        var selected: ShellDestination? = null
        val landings = ShellLandings { selected = it }

        landings.request(ShellLanding.DiscoverLeaderboard)

        assertEquals(ShellDestination.Discover, selected)
        assertEquals(ShellLanding.DiscoverLeaderboard, landings.pending)
    }

    @Test
    fun aLandingIsConsumedOnceAndOnlyByItsOwnScreen() {
        val landings = ShellLandings {}
        landings.request(ShellLanding.LibraryNewestFirst)

        assertFalse(landings.consume(ShellLanding.DiscoverLeaderboard))
        assertTrue(landings.consume(ShellLanding.LibraryNewestFirst))
        assertFalse(landings.consume(ShellLanding.LibraryNewestFirst))
    }

    @Test
    fun theEffectRunsOnceWhenItsLandingArrives() {
        val landings = ShellLandings {}
        var landed = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalShellLandings provides landings) {
                LandingEffect(ShellLanding.DiscoverLeaderboard) { landed++ }
            }
        }
        composeRule.runOnIdle { landings.request(ShellLanding.DiscoverLeaderboard) }
        composeRule.waitForIdle()

        assertEquals(1, landed)
        assertNull(landings.pending)
    }

    @Test
    fun aSuspendingLandingRunsToTheEndAfterItIsConsumed() {
        // Consuming clears the pending landing; that must not cancel the landing's own work (a scroll).
        val landings = ShellLandings {}
        var finished = false
        var started = false
        var frames = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalShellLandings provides landings) {
                LandingEffect(ShellLanding.DiscoverLeaderboard) {
                    // Several frames, as a scroll animation takes.
                    started = true
                    repeat(5) {
                        withFrameNanos { }
                        frames++
                    }
                    finished = true
                }
            }
        }
        composeRule.runOnIdle { landings.request(ShellLanding.DiscoverLeaderboard) }

        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.waitForIdle()
        assertTrue("started=$started frames=$frames", finished)
    }
}
