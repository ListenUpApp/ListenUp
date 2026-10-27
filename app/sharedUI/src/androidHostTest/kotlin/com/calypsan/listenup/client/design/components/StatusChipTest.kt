package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.features.nowplaying.components.statusText
import com.calypsan.listenup.client.presentation.player.SourceStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The status slot (§3.1, §6): one sentence per status with news, announced politely, never a button. */
@RunWith(RobolectricTestRunner::class)
class StatusChipTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun chip(status: SourceStatus) {
        composeRule.setContent { MaterialTheme { StatusChip(text = status.statusText()) } }
    }

    @Test
    fun `every status with news has its words`() {
        val words =
            mapOf(
                SourceStatus.Offline to "Offline",
                SourceStatus.ServerUnreachable to "Can't reach your server",
                SourceStatus.StreamingTranscoded to "Streaming · transcoded",
                SourceStatus.NotPlayable to "Not playable",
            )
        composeRule.setContent {
            MaterialTheme {
                Column { words.keys.forEach { status -> StatusChip(text = status.statusText()) } }
            }
        }
        words.values.forEach { composeRule.onNodeWithText(it).assertExists() }
    }

    @Test
    fun `the chip is a polite live region and not a button`() {
        chip(SourceStatus.Offline)
        composeRule
            .onNodeWithText("Offline")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
