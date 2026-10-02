package com.calypsan.listenup.client.features.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Hardcover settings screen renders each phase of the connection with the words the approved
 * design gives it, copies the sign-in code, and never disconnects without asking first.
 *
 * Drives the stateless [HardcoverSettingsContent] directly — the ViewModel's mapping from the
 * server's stream is covered by `HardcoverSettingsViewModelTest` in sharedLogic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HardcoverSettingsContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var connects = 0
    private var opens = 0
    private var cancels = 0
    private var disconnects = 0

    private fun render(
        state: HardcoverSettingsUiState,
        isWide: Boolean = false,
    ) {
        composeRule.setContent {
            MaterialTheme {
                HardcoverSettingsContent(
                    state = state,
                    isWide = isWide,
                    onConnect = { connects++ },
                    onOpenHardcover = { opens++ },
                    onCancelLinking = { cancels++ },
                    onDisconnect = { disconnects++ },
                    onSyncNow = {},
                    onSetShareMode = {},
                    onSendHistory = {},
                    onDismissHistory = {},
                    onFindMatch = {},
                    onOpenKeptOff = {},
                )
            }
        }
    }

    @Test
    fun `loading says it is checking the connection`() {
        render(HardcoverSettingsUiState.Loading)

        composeRule.onNodeWithText("Checking your Hardcover connection…").assertIsDisplayed()
    }

    @Test
    fun `not offered says the server has no Hardcover`() {
        render(HardcoverSettingsUiState.NotOffered)

        composeRule.onNodeWithText("Hardcover isn't set up on this server.").assertIsDisplayed()
    }

    @Test
    fun `not connected invites a connection and says what is shared`() {
        render(HardcoverSettingsUiState.NotConnected(lastFailure = null, isStarting = false))

        composeRule.onNodeWithText("Share what you finish").assertIsDisplayed()
        composeRule
            .onNodeWithText("Connect your Hardcover account and ListenUp will mark what you listen to as read there.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("A book you finish is marked as read on Hardcover.").assertIsDisplayed()
        composeRule
            .onNodeWithText("You sign in on Hardcover itself. ListenUp never sees your password.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Connect Hardcover").performClick()

        connects shouldBe 1
    }

    @Test
    fun `not connected explains why the last attempt ended`() {
        render(HardcoverSettingsUiState.NotConnected(lastFailure = HardcoverLinkFailure.EXPIRED, isStarting = false))

        composeRule.onNodeWithText("The code expired before it was approved. Try again.").assertIsDisplayed()
    }

    @Test
    fun `linking shows the code, the address without its scheme, and the waiting status`() {
        render(linking)

        composeRule.onNodeWithText("Approve ListenUp on Hardcover").assertIsDisplayed()
        composeRule
            .onNodeWithText("We opened the page for you. On another device, go to hardcover.app/link and enter this code.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("ABCD-1234").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for you to approve").assertExists()
        composeRule.onNodeWithText("Open Hardcover").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        opens shouldBe 1
        cancels shouldBe 1
    }

    @Test
    fun `copying the code puts it on the clipboard and says Copied`() {
        render(linking)

        composeRule.onNodeWithText("Copy code").performClick()

        composeRule.onNodeWithText("Copied").assertIsDisplayed()
        val clipboard =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip
            ?.getItemAt(0)
            ?.text
            .toString() shouldBe "ABCD-1234"
    }

    @Test
    fun `connected shows who and since when, and what is shared`() {
        render(HardcoverSettingsUiState.Connected(username = "simon", since = SINCE, isDisconnecting = false))

        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        composeRule.onNodeWithText("simon").assertIsDisplayed()
        composeRule.onNodeWithText("Since", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("What ListenUp shares", ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText("Books you finish, marked as read").assertIsDisplayed()
    }

    @Test
    fun `disconnect asks first and only disconnects once confirmed`() {
        render(HardcoverSettingsUiState.Connected(username = "simon", since = SINCE, isDisconnecting = false))

        composeRule.onNodeWithText("Disconnect").performClick()

        disconnects shouldBe 0
        composeRule.onNodeWithText("Disconnect Hardcover?").assertIsDisplayed()
        composeRule
            .onNode(hasText("Disconnect") and hasAnyAncestor(isDialog()))
            .performClick()

        disconnects shouldBe 1
    }

    @Test
    fun `dismissing the confirmation keeps the connection`() {
        render(HardcoverSettingsUiState.Connected(username = "simon", since = SINCE, isDisconnecting = false))

        composeRule.onNodeWithText("Disconnect").performClick()
        composeRule.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()

        disconnects shouldBe 0
        composeRule.onNodeWithText("Disconnect Hardcover?").assertDoesNotExist()
    }

    @Test
    fun `broken explains the reason and offers a reconnect and a confirmed disconnect`() {
        render(
            HardcoverSettingsUiState.Broken(
                reason = HardcoverBrokenReason.CANNOT_DECRYPT,
                username = "simon",
                isStarting = false,
            ),
        )

        composeRule.onNodeWithText("Reconnect to keep sharing").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "This server can't read its saved Hardcover sign-in, which usually means it was restored " +
                    "from a backup. Reconnect to continue.",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Was connected as simon.").assertIsDisplayed()
        composeRule.onNodeWithText("Reconnect").performClick()
        connects shouldBe 1

        composeRule.onNodeWithText("Disconnect").performClick()
        disconnects shouldBe 0
        composeRule.onNode(hasText("Disconnect") and hasAnyAncestor(isDialog())).performClick()
        disconnects shouldBe 1
    }

    @Test
    fun `broken with no known username leaves out the was-connected-as line`() {
        render(HardcoverSettingsUiState.Broken(reason = HardcoverBrokenReason.REVOKED, username = null, isStarting = false))

        composeRule.onNodeWithText("Reconnect to keep sharing").assertIsDisplayed()
        composeRule.onNodeWithText("Was connected as", substring = true).assertDoesNotExist()
    }

    private companion object {
        /** 26 September 2026, midday UTC. */
        const val SINCE = 1_790_424_000_000L

        val linking =
            HardcoverSettingsUiState.Linking(
                userCode = "ABCD-1234",
                verificationUri = "https://hardcover.app/link",
                verificationUriComplete = "https://hardcover.app/link?code=ABCD-1234",
                expiresAt = SINCE,
            )
    }
}
