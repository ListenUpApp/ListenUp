package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a tablet the Hardcover screen uses the width (`app/sharedUI/CLAUDE.md` rule 11): the hero sits
 * beside what's shared, and the code sits beside the waiting status — never one phone column
 * stretched or centred.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class HardcoverSettingsWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(state: HardcoverSettingsUiState) {
        composeRule.setContent {
            MaterialTheme {
                HardcoverSettingsContent(
                    state = state,
                    isWide = true,
                    onConnect = {},
                    onOpenHardcover = {},
                    onCancelLinking = {},
                    onDisconnect = {},
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

    private fun assertSideBySide(
        leading: String,
        trailing: String,
    ) {
        val lead = composeRule.onNodeWithText(leading).getUnclippedBoundsInRoot()
        val trail = composeRule.onNodeWithText(trailing).getUnclippedBoundsInRoot()
        lead.right shouldBeLessThanOrEqualTo trail.left
        // Side by side, not stacked: the trailing region starts above the leading one's bottom.
        lead.bottom shouldBeGreaterThan trail.top
    }

    @Test
    fun `not connected puts the hero beside what is shared`() {
        render(HardcoverSettingsUiState.NotConnected(lastFailure = null, isStarting = false))

        assertSideBySide(
            leading = "Share what you finish",
            trailing = "A book you finish is marked as read on Hardcover.",
        )
    }

    @Test
    fun `linking puts the code beside the waiting status`() {
        render(
            HardcoverSettingsUiState.Linking(
                userCode = "ABCD-1234",
                verificationUri = "https://hardcover.app/link",
                verificationUriComplete = "https://hardcover.app/link?code=ABCD-1234",
                expiresAt = 0L,
            ),
        )

        assertSideBySide(leading = "ABCD-1234", trailing = "Waiting for you to approve")
    }
}
