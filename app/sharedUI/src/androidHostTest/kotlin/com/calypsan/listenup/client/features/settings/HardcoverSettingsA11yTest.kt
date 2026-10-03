package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.calypsan.listenup.client.presentation.hardcover.HardcoverBookToMatch
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSyncStatus
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.client.testing.AtFontScale
import com.calypsan.listenup.client.testing.assertNoMidWordBreaks
import com.calypsan.listenup.client.testing.isLiveRegion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.unit.height

private const val MATCH_COUNT = 28

private fun book(index: Int) = HardcoverBookToMatch("b$index", "Book number $index", "Author $index", "/tmp/cover-b$index.webp", null)

/**
 * Settings → Hardcover with TalkBack and at the largest font (#1562): every control says which book it is for,
 * the sync line stops announcing the minute, and the screen keeps its words and its room.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HardcoverSettingsA11yTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun connected(
        books: List<HardcoverBookToMatch> = List(MATCH_COUNT) { book(it + 1) },
        sync: HardcoverSyncStatus = HardcoverSyncStatus.Idle,
        lastSyncedAt: Long? = 1_790_424_000_000L,
    ) = HardcoverSettingsUiState.Connected(
        username = "simonhull",
        since = 1_790_424_000_000L,
        isDisconnecting = false,
        lastSyncedAt = lastSyncedAt,
        sync = sync,
        booksToMatch = books,
        isMatchListKnown = true,
    )

    private fun render(
        state: () -> HardcoverSettingsUiState,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            AtFontScale(fontScale) {
                MaterialTheme {
                    HardcoverSettingsContent(
                        state = state(),
                        isWide = false,
                        onConnect = {},
                        onOpenHardcover = {},
                        onCancelLinking = {},
                        onDisconnect = {},
                        onSyncNow = {},
                        onSetShareMode = {},
                        onSendHistory = {},
                        onDismissHistory = {},
                        onFindMatch = { opened += it },
                        onOpenKeptOff = {},
                    )
                }
            }
        }
    }

    @Test
    fun `each Find on Hardcover names its book`() {
        render({ connected(books = listOf(book(1), book(2))) })

        composeRule.onNodeWithContentDescription("Find Book number 2 on Hardcover").performScrollTo().performClick()
        opened shouldBe listOf("b2")
        composeRule.onNodeWithContentDescription("Find Book number 1 on Hardcover").assertExists()
    }

    @Test
    fun `the Needs a match heading carries its count`() {
        render({ connected() })

        composeRule
            .onAllNodes(isHeading())
            .filter(hasText("Needs a match") and hasContentDescription("$MATCH_COUNT books need a match", substring = true))
            .assertCountEquals(1)
    }

    @Test
    fun `a long list shows its first few books, and the rest on request`() {
        render({ connected() })

        composeRule.onNodeWithContentDescription("Find Book number 5 on Hardcover").assertExists()
        composeRule.onNodeWithContentDescription("Find Book number 6 on Hardcover").assertDoesNotExist()
        composeRule.onNodeWithText("Show all $MATCH_COUNT books").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Find Book number $MATCH_COUNT on Hardcover").assertExists()
    }

    @Test
    fun `the minute ticking past is never a live region - only a sync starting or finishing is`() {
        var state by mutableStateOf(connected(sync = HardcoverSyncStatus.Syncing))
        render({ state })

        composeRule.onNode(isLiveRegion() and hasContentDescription("Syncing…")).assertExists()

        state = connected(sync = HardcoverSyncStatus.Idle, lastSyncedAt = 1_790_424_060_000L)

        composeRule.onNode(isLiveRegion() and hasContentDescription("Synced with Hardcover")).assertExists()
        composeRule.onNodeWithText("Last synced", substring = true).assertExists()
        // A live region speaks its whole subtree, so no live region may hold the minute-ticking line anywhere in it.
        composeRule.onAllNodes(isLiveRegion(), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            node.spokenSubtree().shouldNotContain("Last synced")
        }
    }

    @Test
    fun `the connected avatar's initial is not read apart from the name`() {
        render({ connected(books = emptyList()) })

        composeRule.onNodeWithText("S").assertDoesNotExist()
    }

    @Test
    fun `at the largest font Find on Hardcover keeps its words`() {
        render({ connected(books = listOf(book(1))) }, fontScale = 2f)

        composeRule
            .onAllNodesWithText("Find on Hardcover", useUnmergedTree = true)
            .onFirst()
            .performScrollTo()
            .assertNoMidWordBreaks()
    }

    @Test
    fun `at the largest font the sync line keeps its words`() {
        render({ connected(books = emptyList()) }, fontScale = 2f)

        composeRule.onNodeWithText("Last synced", substring = true, useUnmergedTree = true).performScrollTo().assertNoMidWordBreaks()
    }

    @Test
    fun `at the largest font the two share modes are the same height`() {
        render({ connected(books = emptyList()) }, fontScale = 2f)

        val asIListen = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and hasText("As I listen"))
        asIListen.performScrollTo()
        val first = asIListen.getUnclippedBoundsInRoot()
        val second =
            composeRule
                .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and hasText("Only when I finish"))
                .getUnclippedBoundsInRoot()
        first.height shouldBe second.height
    }

    @Test
    fun `at the default font Disconnect is held at the bottom edge`() {
        render({ connected() })

        composeRule.onNodeWithText("Disconnect").assertIsDisplayed()
    }

    @Test
    fun `at the largest font Disconnect scrolls with the content instead of holding the room`() {
        render({ connected() }, fontScale = 2f)

        composeRule.onNodeWithText("Disconnect").assertIsNotDisplayed()
        composeRule.onNodeWithText("Disconnect").performScrollTo().assertIsDisplayed()
    }
}

/** Every text and description in this node and below it — what TalkBack reads when a live region changes. */
private fun SemanticsNode.spokenSubtree(): String =
    (
        config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
            config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } +
            children.map { it.spokenSubtree() }
    ).joinToString(" ")
