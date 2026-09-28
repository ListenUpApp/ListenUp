package com.calypsan.listenup.client.features.admin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A user's detail on a tablet puts who they are beside what they may do; on a phone the two
 * sections stack.
 */
@RunWith(RobolectricTestRunner::class)
class UserDetailWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet information and permissions sit side by side`() {
        setContent()

        assertSideBySide(composeRule.onNodeWithText("User information"), composeRule.onNodeWithText("Permissions"))
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone information and permissions stack`() {
        setContent()

        assertStacked(composeRule.onNodeWithText("User information"), composeRule.onNodeWithText("Permissions"))
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                UserDetailContent(state = STATE, onToggleCanEdit = {}, onToggleCanShare = {})
            }
        }
    }

    private companion object {
        val STATE =
            UserDetailUiState.Ready(
                user =
                    AdminUserInfo(
                        id = "u1",
                        email = "kaladin@example.com",
                        displayName = "Kaladin",
                        firstName = null,
                        lastName = null,
                        isRoot = false,
                        role = "member",
                        status = "active",
                        createdAt = "2026-01-01",
                    ),
                canEdit = true,
                canShare = false,
                isProtected = false,
            )
    }
}
