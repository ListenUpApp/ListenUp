package com.calypsan.listenup.client.features.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec §2.5 on Android: one label per card, author always, narrator only on wide windows, no now-playing marker. */
@RunWith(RobolectricTestRunner::class)
class EverythingInProgressSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun babel() =
        ContinueListeningItem.Ready(
            bookId = "babel",
            book =
                ContinueListeningBook(
                    bookId = "babel",
                    title = "Babel",
                    authorNames = "R. F. Kuang",
                    narratorNames = "Chris Lew Kum Hoi",
                    coverPath = "/tmp/cover-babel.webp",
                    progress = 0.11f,
                    currentPositionMs = 8_000_000L,
                    totalDurationMs = 75_200_000L,
                    lastPlayedAtMs = 0L,
                    isDownloaded = false,
                ),
        )

    private fun show(
        width: HomeWidth,
        onOpenBook: (String) -> Unit = {},
        onOpenInProgress: () -> Unit = {},
    ) = composeRule.setContent {
        MaterialTheme {
            EverythingInProgressSection(
                items = listOf(babel(), ContinueListeningItem.Loading("pending")),
                width = width,
                selection = HomeSelection.None,
                onOpenBook = onOpenBook,
                onOpenInProgress = onOpenInProgress,
            )
        }
    }

    @Test
    fun `a phone card is one label with author, percent and time left, and opens its book`() {
        var opened: String? = null
        show(HomeWidth.Compact, onOpenBook = { opened = it })

        composeRule.onNodeWithText("Everything in progress").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Babel, by R. F. Kuang, 11 percent listened, 18h 40m left")
            .performClick()
        opened shouldBe "babel"
    }

    @Test
    fun `a tablet card adds who reads it`() {
        show(HomeWidth.Expanded)
        composeRule
            .onNodeWithContentDescription(
                "Babel, by R. F. Kuang, Read by Chris Lew Kum Hoi, 11 percent listened, 18h 40m left",
            ).assertIsDisplayed()
    }

    @Test
    fun `the header says Everything in progress and In Library opens the In progress Library`() {
        var opened = false
        show(HomeWidth.Compact, onOpenInProgress = { opened = true })
        composeRule.onNodeWithText("Everything in progress").assertIsDisplayed()
        composeRule.onNodeWithText("In Library").performClick()
        opened shouldBe true
    }

    @Test
    fun `no card wears a now-playing marker`() {
        show(HomeWidth.Compact)
        composeRule.onAllNodesWithContentDescription("Now playing", substring = true).assertCountEquals(0)
    }
}
