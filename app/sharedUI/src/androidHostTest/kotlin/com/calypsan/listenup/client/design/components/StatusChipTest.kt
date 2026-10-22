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
import com.calypsan.listenup.client.presentation.player.AudioSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The status slot (§3.1, §6): one sentence per source, announced politely, never a button. */
@RunWith(RobolectricTestRunner::class)
class StatusChipTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun chip(source: AudioSource) {
        composeRule.setContent { MaterialTheme { StatusChip(text = source.statusText(), emphasized = source is AudioSource.Casting) } }
    }

    @Test
    fun `every source has its words`() {
        val words =
            mapOf(
                AudioSource.Downloaded to "Downloaded",
                AudioSource.DownloadedOffline to "Downloaded · offline",
                AudioSource.Streaming to "Streaming",
                AudioSource.StreamingTranscoded to "Streaming · transcoded",
                AudioSource.Offline to "Offline",
                AudioSource.ServerUnreachable to "Can't reach your server",
                AudioSource.Casting("Living Room") to "Playing on Living Room",
                AudioSource.NotPlayable to "Not playable",
            )
        composeRule.setContent {
            MaterialTheme {
                Column { words.keys.forEach { source -> StatusChip(text = source.statusText()) } }
            }
        }
        words.values.forEach { composeRule.onNodeWithText(it).assertExists() }
    }

    @Test
    fun `the chip is a polite live region and not a button`() {
        chip(AudioSource.Streaming)
        composeRule
            .onNodeWithText("Streaming")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
