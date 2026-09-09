package com.calypsan.listenup.gradle

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The discovered-test-count floor decides whether a lane collapsed, and until now that decision
 * lived inside a Gradle `afterSuite` closure with no test of its own — the guard that catches
 * every other lane going quiet was itself unguarded.
 *
 * These cases pin all three reasons the floor stands down (filtered run, non-root suite, count at
 * or above the bar) plus the one case it must fire on. Getting any of them backwards is silent in
 * the worst direction: a floor that never fires looks exactly like a floor that never needed to.
 */
class TestDiscoveryFloorTest {
    @Test
    fun `fires on an unfiltered root suite below the floor`() {
        val message =
            discoveredCountFailure(
                taskLabel = ":app:sharedUI:desktopTest",
                floor = 40,
                testCount = 3,
                isRootSuite = true,
                isFiltered = false,
            )

        assertNotNull(message)
        // The message is the operator's only context, so it must name the lane, what was found,
        // and what was expected — not merely "below the floor".
        assertTrue(message.contains(":app:sharedUI:desktopTest"), "names the task: $message")
        assertTrue(message.contains("only 3 tests"), "names the observed count: $message")
        assertTrue(message.contains("floor of 40"), "names the floor: $message")
    }

    @Test
    fun `stands down on a filtered run`() {
        assertNull(
            discoveredCountFailure(
                taskLabel = ":app:sharedUI:desktopTest",
                floor = 40,
                testCount = 3,
                isRootSuite = true,
                isFiltered = true,
            ),
        )
    }

    @Test
    fun `ignores per-class suites, which are not the aggregate`() {
        assertNull(
            discoveredCountFailure(
                taskLabel = ":app:sharedUI:desktopTest",
                floor = 40,
                testCount = 3,
                isRootSuite = false,
                isFiltered = false,
            ),
        )
    }

    @Test
    fun `accepts a count exactly at the floor`() {
        assertNull(
            discoveredCountFailure(
                taskLabel = ":app:sharedUI:desktopTest",
                floor = 40,
                testCount = 40,
                isRootSuite = true,
                isFiltered = false,
            ),
        )
    }

    @Test
    fun `accepts a count above the floor`() {
        assertNull(
            discoveredCountFailure(
                taskLabel = ":app:sharedUI:desktopTest",
                floor = 40,
                testCount = 62,
                isRootSuite = true,
                isFiltered = false,
            ),
        )
    }
}
