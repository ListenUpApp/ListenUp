package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.presentation.library.LibraryStatusStrip
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Library's one status strip on Android: what it says for a scan and for a library still arriving. */
@RunWith(RobolectricTestRunner::class)
class LibraryStatusStripSlotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `arriving says so, with what Room holds, as a polite live region`() {
        composeRule.setContent { MaterialTheme { LibraryStatusStripSlot(LibraryStatusStrip.Arriving(booksSoFar = 1286)) } }

        composeRule.onNodeWithText("Building your library", useUnmergedTree = true).assertExists()
        composeRule
            .onNodeWithText(
                "1,286 books so far. Books appear here as they arrive; you can start listening now.",
                useUnmergedTree = true,
            ).assertExists()
        composeRule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assertExists()
    }

    @Test
    fun `one book is one book, and none yet says only that books arrive`() {
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    LibraryStatusStripSlot(LibraryStatusStrip.Arriving(booksSoFar = 1))
                    LibraryStatusStripSlot(LibraryStatusStrip.Arriving(booksSoFar = 0))
                }
            }
        }

        composeRule
            .onNodeWithText(
                "1 book so far. Books appear here as they arrive; you can start listening now.",
                useUnmergedTree = true,
            ).assertExists()
        composeRule.onNodeWithText("Books appear here as they arrive.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a scan names the server, its count and what is new`() {
        val scan =
            ScanProgressState(
                phase = "analyzing",
                current = 412,
                total = 1280,
                added = 3,
                updated = 0,
                removed = 0,
                books = 412,
                booksTotal = 1280,
            )
        composeRule.setContent { MaterialTheme { LibraryStatusStripSlot(LibraryStatusStrip.Scanning(scan)) } }

        composeRule.onNodeWithText("Scanning your server", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("412 of 1,280 · 3 new", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a scan with nothing new says only how far along it is`() {
        val scan =
            ScanProgressState(
                phase = "analyzing",
                current = 2,
                total = 9,
                added = 0,
                updated = 0,
                removed = 0,
                books = 2,
                booksTotal = 9,
            )
        composeRule.setContent { MaterialTheme { LibraryStatusStripSlot(LibraryStatusStrip.Scanning(scan)) } }

        composeRule.onNodeWithText("2 of 9", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `hidden draws nothing`() {
        composeRule.setContent { MaterialTheme { LibraryStatusStripSlot(LibraryStatusStrip.Hidden) } }

        composeRule.onAllNodesWithText("Building your library", useUnmergedTree = true).fetchSemanticsNodes().size shouldBe 0
        composeRule.onAllNodesWithText("Scanning your server", useUnmergedTree = true).fetchSemanticsNodes().size shouldBe 0
    }
}
