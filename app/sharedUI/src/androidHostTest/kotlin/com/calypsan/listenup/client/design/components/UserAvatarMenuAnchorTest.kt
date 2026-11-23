package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.core.context.stopKoin
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * The app bar's avatar circle opens the profile menu. On its own it reads as the user's initials;
 * TalkBack hears "Profile, button" instead, as iOS's VoiceOver does for its avatar link.
 */
@RunWith(RobolectricTestRunner::class)
class UserAvatarMenuAnchorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var opened = false

    /** The composable KoinApplication registers a global context; cleared on both sides of each test. */
    @Before
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    @Test
    fun `the avatar anchor is a profile button that opens the menu`() {
        composeRule.setContent {
            WithAvatarDependencies {
                UserAvatarMenu(
                    user = USER,
                    expanded = false,
                    onExpandedChange = { opened = it },
                    onMyProfileClick = {},
                    onSignOutClick = {},
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Profile")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        opened shouldBe true
    }

    /** The avatar resolves its collaborators through Koin; stubbed to the "no profile yet" path. */
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
            configuration =
                koinConfiguration {
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

    private companion object {
        val USER =
            User(
                id = UserId("u1"),
                email = "simone@example.com",
                displayName = "Simone Hart",
                isAdmin = false,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
    }
}
