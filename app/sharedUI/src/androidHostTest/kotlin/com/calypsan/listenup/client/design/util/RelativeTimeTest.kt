package com.calypsan.listenup.client.design.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** One minute, in milliseconds — the unit the bucket fixtures are built from. */
private const val MINUTE_MS = 60_000L

/** One hour, in milliseconds. */
private const val HOUR_MS = 60 * MINUTE_MS

/** One day, in milliseconds. */
private const val DAY_MS = 24 * HOUR_MS

/**
 * Bucket pins for the shared [relativeTime] util. Every fixture sits mid-bucket (5.5 minutes,
 * 3.5 hours, 2.5 days) so a slow test run cannot tip one over a boundary and go flaky.
 *
 * JUnit4 + Robolectric — the canonical Compose UI test shape in this module: `createComposeRule()`
 * needs JUnit4, and Robolectric supplies the resource environment `stringResource` resolves in.
 */
@RunWith(RobolectricTestRunner::class)
class RelativeTimeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun assertRendersAgo(
        millisAgo: Long,
        expected: String,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Text(relativeTime(System.currentTimeMillis() - millisAgo))
            }
        }
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun `an instant under a minute old reads as just now`() {
        assertRendersAgo(millisAgo = 0L, expected = "Just now")
    }

    @Test
    fun `an instant minutes old reads in minutes`() {
        assertRendersAgo(millisAgo = 5 * MINUTE_MS + MINUTE_MS / 2, expected = "5m ago")
    }

    @Test
    fun `an instant hours old reads in hours`() {
        assertRendersAgo(millisAgo = 3 * HOUR_MS + HOUR_MS / 2, expected = "3h ago")
    }

    @Test
    fun `an instant days old reads in days`() {
        assertRendersAgo(millisAgo = 2 * DAY_MS + DAY_MS / 2, expected = "2d ago")
    }

    @Test
    fun `a future instant clamps to just now rather than reading negative`() {
        assertRendersAgo(millisAgo = -(2 * DAY_MS), expected = "Just now")
    }
}
