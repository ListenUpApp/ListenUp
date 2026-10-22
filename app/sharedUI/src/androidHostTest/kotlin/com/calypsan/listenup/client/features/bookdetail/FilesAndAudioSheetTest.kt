package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.features.bookdetail.components.FilesAndAudioContent
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Files & audio (spec §3.2.1 D32): each file by name, length and size, then the four format rows. */
@RunWith(RobolectricTestRunner::class)
class FilesAndAudioSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the sheet lists each file, then Format, Bitrate, Sample rate and Channels`() {
        composeRule.setContent {
            MaterialTheme {
                FilesAndAudioContent(
                    audioFiles =
                        listOf(
                            AudioFile(
                                id = "a1",
                                index = 0,
                                filename = "disc1/01.m4b",
                                format = "m4b",
                                codec = "aac",
                                duration = 5_400_000L,
                                size = 43_200_000L,
                                bitrate = 64_000,
                                sampleRate = 44_100,
                                channels = 2,
                            ),
                        ),
                )
            }
        }
        composeRule.onNodeWithText("Files & audio").assertExists()
        composeRule.onNodeWithText("01.m4b").assertExists()
        composeRule.onNodeWithText("disc1/01.m4b").assertDoesNotExist()
        composeRule.onNodeWithText("1h 30m").assertExists()
        composeRule.onNodeWithText("41.2 MB").assertExists()
        listOf("Format", "Bitrate", "Sample rate", "Channels").forEach { composeRule.onNodeWithText(it).assertExists() }
        composeRule.onNodeWithText("64 kbps").assertExists()
        val file =
            composeRule
                .onNodeWithText("01.m4b")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val format =
            composeRule
                .onNodeWithText("Format")
                .fetchSemanticsNode()
                .boundsInRoot.top
        (file < format) shouldBe true
    }
}
