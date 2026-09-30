package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.UtcOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * "Mark as finished" asks for the days, and confirming without touching them sends exactly what the
 * one-tap finish on iOS and web used to: the recorded start (or now), and now.
 *
 * JUnit4 + Robolectric, consistent with the other Book Detail component tests in this lane.
 */
@RunWith(RobolectricTestRunner::class)
class MarkCompleteDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pacific = FixedOffsetTimeZone(UtcOffset(hours = -7))
    private val nowMs = Instant.parse("2026-09-30T03:00:00Z").toEpochMilliseconds()

    @Test
    fun `confirming untouched dates sends the recorded start and now`() {
        val startedAtMs = Instant.parse("2026-09-02T18:00:00Z").toEpochMilliseconds()
        var confirmed: Pair<Long, Long>? = null
        composeRule.setContent {
            MarkCompleteDialog(
                startedAtMs = startedAtMs,
                onConfirm = { start, finish -> confirmed = start to finish },
                onDismiss = {},
                nowMs = nowMs,
                timeZone = pacific,
            )
        }

        composeRule.onNode(hasText("Mark as finished") and hasClickAction()).performClick()

        assertEquals(startedAtMs to nowMs, confirmed)
    }

    @Test
    fun `the form names both days it is asking for`() {
        composeRule.setContent {
            MarkCompleteDialog(
                startedAtMs = null,
                onConfirm = { _, _ -> },
                onDismiss = {},
                nowMs = nowMs,
                timeZone = pacific,
            )
        }

        composeRule.onNodeWithText("Started").assertExists()
        composeRule.onNodeWithText("Finished").assertExists()
    }
}
