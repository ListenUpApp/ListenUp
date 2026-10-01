package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.client.presentation.hardcover.HardcoverBookToMatch
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A cover path keeps BookCoverImage off its async path, which needs Koin.
private val FOUR_BOOKS = (1..4).map { HardcoverBookToMatch("b$it", "Book $it", "Author $it", "/tmp/cover-b$it.webp", null) }

private const val OFFER_BODY =
    "You finished 74 books in ListenUp before connecting. " +
        "Send them to Hardcover as read, with when you started and finished."
private const val OFFER_BODY_ONE =
    "You finished 1 book in ListenUp before connecting. " +
        "Send it to Hardcover as read, with when you started and finished."
private const val SENDING_NOTE = "You can leave this screen — it keeps going. Your new listening is sent first."

/** Spec #1540 on Android, to the approved canvas: the offer, Sending, Done, and the quiet row in Sync. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HardcoverHistoryCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var sends = 0
    private var dismissals = 0

    private fun render(
        history: HardcoverHistory,
        books: List<HardcoverBookToMatch> = emptyList(),
    ) {
        composeRule.setContent {
            MaterialTheme {
                HardcoverSettingsContent(
                    state =
                        HardcoverSettingsUiState.Connected(
                            "simon",
                            1_790_424_000_000L,
                            false,
                            booksToMatch = books,
                            isMatchListKnown = true,
                            history = history,
                        ),
                    isWide = false,
                    onConnect = {},
                    onOpenHardcover = {},
                    onCancelLinking = {},
                    onDisconnect = {},
                    onSyncNow = {},
                    onSetShareMode = {},
                    onSendHistory = { sends++ },
                    onDismissHistory = { dismissals++ },
                    onFindMatch = {},
                )
            }
        }
    }

    private fun button(label: String) = composeRule.onNode(hasText(label) and hasClickAction())

    @Test
    fun `the offer counts the books, and Send and Not now each ask once`() {
        render(HardcoverHistory.Offer(74))

        composeRule.onNodeWithText("Send your earlier listening?").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(OFFER_BODY).assertIsDisplayed()
        button("Send 74 books").performScrollTo().performClick()
        button("Not now").performClick()

        sends shouldBe 1
        dismissals shouldBe 1
    }

    @Test
    fun `one book is said in the singular`() {
        render(HardcoverHistory.Offer(1))

        button("Send 1 book").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(OFFER_BODY_ONE).assertIsDisplayed()
    }

    @Test
    fun `on a phone Not now and Send sit side by side, Send last`() {
        render(HardcoverHistory.Offer(74))

        val send = button("Send 74 books").performScrollTo().getUnclippedBoundsInRoot()
        val notNow = button("Not now").getUnclippedBoundsInRoot()
        notNow.top shouldBe send.top
        (notNow.right <= send.left) shouldBe true
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `at 360dp the buttons stack at full width, Send first`() {
        render(HardcoverHistory.Offer(74))

        val send = button("Send 74 books").performScrollTo().getUnclippedBoundsInRoot()
        val notNow = button("Not now").performScrollTo().getUnclippedBoundsInRoot()
        (notNow.top >= send.bottom) shouldBe true
        val sendWidth = send.right - send.left
        sendWidth shouldBe notNow.right - notNow.left
    }

    @Test
    fun `sending says how far it has got, is read out as 23 of 74, and says it keeps going`() {
        render(HardcoverHistory.Sending(sentBooks = 23, totalBooks = 74))

        composeRule.onNodeWithText("Sending 23 of 74 books…").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(SENDING_NOTE).assertIsDisplayed()
        composeRule
            .onNode(hasContentDescription("Sending earlier books"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "23 of 74"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        composeRule.onNodeWithText("Send your earlier listening?").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun `done says what was sent, and 4 need a match brings the Needs a match section into view`() {
        render(HardcoverHistory.Done(sentBooks = 70, needsMatchBooks = 4), books = FOUR_BOOKS)

        composeRule.onNodeWithText("Sent 70 books to Hardcover").performScrollTo().assertIsDisplayed()
        button("4 need a match").performScrollTo()
        composeRule.onNodeWithText("Needs a match").assertIsNotDisplayed()

        button("4 need a match").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Needs a match").assertIsDisplayed()
    }

    @Test
    fun `done with every book matched says all were sent, and Dismiss asks once`() {
        render(HardcoverHistory.Done(sentBooks = 74, needsMatchBooks = 0))

        composeRule.onNodeWithText("Sent all 74 books to Hardcover").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("need a match", substring = true).assertDoesNotExist()
        composeRule.onNode(hasContentDescription("Dismiss") and hasClickAction()).performClick()

        dismissals shouldBe 1
    }

    @Test
    fun `done with nothing sent yet says the books need a match first, never Sent 0`() {
        render(HardcoverHistory.Done(sentBooks = 0, needsMatchBooks = 4))

        composeRule
            .onNodeWithText("4 books need a match before they can be sent")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Sent 0", substring = true).assertDoesNotExist()
    }

    @Test
    fun `done with one book waiting and nothing sent says it in the singular`() {
        render(HardcoverHistory.Done(sentBooks = 0, needsMatchBooks = 1))

        composeRule
            .onNodeWithText("1 book needs a match before it can be sent")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `after Not now the quiet row in Sync sends in place, with no card`() {
        render(HardcoverHistory.Available(74))

        composeRule.onNodeWithText("Send earlier books").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("74 finished before you connected").assertIsDisplayed()
        composeRule
            .onNode(hasContentDescription("Send earlier books to Hardcover") and hasClickAction())
            .performClick()

        sends shouldBe 1
        composeRule.onNodeWithText("Send your earlier listening?").assertDoesNotExist()
    }

    @Test
    fun `with no history there is no card and no row`() {
        render(HardcoverHistory.None)

        composeRule.onNodeWithText("Send your earlier listening?").assertDoesNotExist()
        composeRule.onNodeWithText("Send earlier books").assertDoesNotExist()
    }

    @Test
    fun `every history action is at least 48dp tall`() {
        render(HardcoverHistory.Offer(74))
        listOf("Send 74 books", "Not now").forEach { button(it).performScrollTo().assertHeightIsAtLeast(48.dp) }
    }
}
