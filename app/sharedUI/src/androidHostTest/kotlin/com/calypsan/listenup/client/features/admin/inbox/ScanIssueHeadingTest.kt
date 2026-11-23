package com.calypsan.listenup.client.features.admin.inbox

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.api.dto.scan.ScanIssue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The inbox's "Needs attention" block is a section of its own; TalkBack can jump straight to it. */
@RunWith(RobolectricTestRunner::class)
class ScanIssueHeadingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the needs-attention title is a heading`() {
        composeRule.setContent {
            MaterialTheme {
                LazyVerticalGrid(columns = GridCells.Fixed(1)) {
                    scanIssueItems(
                        issues = listOf(ScanIssue(id = "i1", rootRelPath = "Broken", firstSeenAt = 0L, lastSeenAt = 0L)),
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Needs attention").assert(isHeading())
    }
}
