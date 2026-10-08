package com.calypsan.listenup.client.features.admin

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AccessLabel
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * The user list names each person by their access label — Owner, Admin, or a member's preset — never by
 * the raw role string, which the roster spells in upper case ("ADMIN" once read as a member here).
 */
@RunWith(RobolectricTestRunner::class)
class AdminUserRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The composable KoinApplication registers a global context; cleared on both sides of each test. */
    @Before
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun show(user: AdminUserInfo) {
        composeRule.setContent {
            WithAvatarDependencies { UserRow(user = user, isDeleting = false, onClick = {}, onDeleteClick = {}) }
        }
    }

    /** The row's avatar resolves its collaborators through Koin; stubbed to the "no profile yet" path. */
    @Composable
    private fun WithAvatarDependencies(content: @Composable () -> Unit) {
        val profiles =
            mock<UserProfileRepository>(MockMode.autoUnit) {
                every { observeProfile(any()) } returns flowOf<CachedUserProfile?>(null)
            }
        val storage =
            mock<ImageStorage>(MockMode.autoUnit) {
                every { userAvatarExists(any()) } returns false
                every { getUserAvatarPath(any()) } returns "/tmp/avatar"
            }
        val images =
            mock<ImageRepository>(MockMode.autoUnit) {
                everySuspend { downloadUserAvatar(any(), any()) } returns AppResult.Success(false)
            }
        KoinApplication(
            application = {
                modules(
                    module {
                        single { profiles }
                        single { storage }
                        single { images }
                    },
                )
            },
        ) {
            MaterialTheme { content() }
        }
    }

    private fun user(
        role: String,
        access: AccessLabel,
    ) = AdminUserInfo(
        id = "u1",
        email = "mustang@example.com",
        displayName = "Mustang",
        firstName = null,
        lastName = null,
        isRoot = false,
        role = role,
        status = "ACTIVE",
        createdAt = "0",
        access = access,
    )

    @Test
    fun `an upper-case ADMIN role reads Admin`() {
        show(user(role = "ADMIN", access = AccessLabel.ADMIN))
        composeRule.onNodeWithText("Admin").assertIsDisplayed()
        composeRule.onNodeWithText("ADMIN").assertDoesNotExist()
    }

    @Test
    fun `a member reads as the preset their flags match`() {
        show(user(role = "MEMBER", access = AccessLabel.LIBRARIAN))
        composeRule.onNodeWithText("Librarian").assertIsDisplayed()
        composeRule.onNodeWithText("MEMBER").assertDoesNotExist()
    }
}
