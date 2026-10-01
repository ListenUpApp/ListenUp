package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem
import com.calypsan.listenup.client.presentation.hardcover.HardcoverBookToMatch
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSyncStatus
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Clock

/** Spec B5's Connected state: last sync, Sync now, a problem in plain words, and the books to match. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HardcoverSyncSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var syncs = 0
    private val opened = mutableListOf<String>()

    private fun render(state: HardcoverSettingsUiState.Connected) {
        composeRule.setContent {
            MaterialTheme {
                HardcoverSettingsContent(
                    state = state,
                    isWide = false,
                    onConnect = {},
                    onOpenHardcover = {},
                    onCancelLinking = {},
                    onDisconnect = {},
                    onSyncNow = { syncs++ },
                    onSetShareMode = {},
                    onFindMatch = { opened += it },
                )
            }
        }
    }

    private fun connected(
        lastSyncedAt: Long? = null,
        sync: HardcoverSyncStatus = HardcoverSyncStatus.Idle,
        books: List<HardcoverBookToMatch> = emptyList(),
        known: Boolean = true,
    ) = HardcoverSettingsUiState.Connected("simon", 1_790_424_000_000L, false, lastSyncedAt, sync, books, known)

    @Test
    fun `a sync this minute reads just now, and Sync now asks for one`() {
        render(connected(lastSyncedAt = Clock.System.now().toEpochMilliseconds()))
        composeRule.onNodeWithText("Last synced just now").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sync now").performScrollTo().performClick()
        syncs shouldBe 1
    }

    @Test
    fun `never synced says so`() {
        render(connected())
        composeRule.onNodeWithText("Not synced yet").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `while syncing, Sync now can't be pressed again`() {
        render(connected(sync = HardcoverSyncStatus.Syncing))
        composeRule.onNodeWithText("Syncing…").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sync now").assertIsNotEnabled()
    }

    private fun assertStalled(
        problem: HardcoverSyncProblem,
        words: String,
    ) {
        render(connected(sync = HardcoverSyncStatus.Problem(problem)))
        composeRule.onNodeWithText(words).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performScrollTo().performClick()
        syncs shouldBe 1
    }

    @Test
    fun `a stalled push is said in plain words, with Try again`() =
        assertStalled(
            HardcoverSyncProblem.PUSH_STALLED,
            "Some of your listening hasn't reached Hardcover yet. ListenUp keeps trying.",
        )

    @Test
    fun `a stalled pull is said in plain words, with Try again`() =
        assertStalled(HardcoverSyncProblem.PULL_STALLED, "ListenUp can't read your Hardcover shelf right now. It keeps trying.")

    @Test
    fun `a failed Sync now leaves the sync line as it was, with Sync now`() {
        render(connected(sync = HardcoverSyncStatus.Problem(HardcoverSyncProblem.SYNC_NOW_FAILED)))
        composeRule.onNodeWithText("Not synced yet").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sync now").performScrollTo().performClick()
        syncs shouldBe 1
    }

    @Test
    fun `a failed Sync now is said once, briefly, with Try again`() {
        composeRule.setContent {
            MaterialTheme {
                val host = remember { SnackbarHostState() }
                SnackbarHost(host)
                HardcoverSyncNowFailedNotice(
                    sync = HardcoverSyncStatus.Problem(HardcoverSyncProblem.SYNC_NOW_FAILED),
                    snackbarHostState = host,
                    onTryAgain = { syncs++ },
                )
            }
        }
        composeRule.onNodeWithText("Couldn't reach Hardcover. Nothing was lost.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.waitForIdle()
        syncs shouldBe 1
    }

    @Test
    fun `the books that need a match are counted and listed, and one opens Find on Hardcover`() {
        render(connected(books = listOf(HardcoverBookToMatch("b1", "Project Hail Mary", "Andy Weir", "/tmp/cover-b1.webp", null))))
        composeRule.onNodeWithText("Needs a match").performScrollTo().assertIsDisplayed()
        // The cover's text fallback names the book too, so the row's title is the first of two.
        composeRule.onAllNodesWithText("Project Hail Mary")[0].assertExists()
        composeRule.onNodeWithText("Find on Hardcover").performScrollTo().performClick()
        opened shouldBe listOf("b1")
    }

    @Test
    fun `with nothing to match, one quiet line says every book is matched`() {
        render(connected())
        composeRule.onNodeWithText("Needs a match").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Every book you've started is matched").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `before the list is known, it claims nothing`() {
        render(connected(known = false))
        composeRule.onNodeWithText("Needs a match").assertDoesNotExist()
        composeRule.onNodeWithText("Every book you've started is matched").assertDoesNotExist()
    }

    @Test
    fun `what is shared names what goes to Hardcover, and what comes back`() {
        render(connected())
        listOf(
            "Books you start, as Currently reading",
            "How far you've listened",
            "Books you finish, marked as read",
            "What comes back",
            "Books you've read elsewhere appear in Readers with a Hardcover label.",
            "They never count as listening.",
        ).forEach { composeRule.onNodeWithText(it, substring = true).performScrollTo().assertIsDisplayed() }
    }
}
