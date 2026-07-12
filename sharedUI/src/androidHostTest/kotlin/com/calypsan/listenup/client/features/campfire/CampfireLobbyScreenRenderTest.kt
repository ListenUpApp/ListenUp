package com.calypsan.listenup.client.features.campfire

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.api.dto.campfire.CampfireMember
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * Render smoke-test for the Campfire lobby screen — the coverage gap that let a
 * crash-on-first-render ship through every green gate (the host-badge `Box` was measured
 * with an illegal negative `padding`, throwing `IllegalArgumentException` the instant a host
 * entered the lobby). Nothing had ever composed the lobby, so no gate saw it.
 *
 * The host variant ([isHost] = `true`) with a member whose id matches [hostUserId] is the one
 * path that renders the host badge — the exact geometry that crashed. Reaching the assertion
 * proves the lobby measures without throwing.
 *
 * The lobby's roster avatars are the design-system [com.calypsan.listenup.client.design.components.UserAvatar],
 * which resolves its image via Koin, so the content is wrapped in a [KoinApplication] whose mocked
 * repositories steer every avatar down the no-image "initials" path (no disk, no network).
 *
 * `mainClock.autoAdvance = false` because [CampfireBackdrop]'s breathing-glow runs an infinite
 * transition even under `reducedMotion`; the initial layout pass (where the bug surfaced) still
 * runs, but the never-idle animation can't hang `waitForIdle`.
 *
 * JUnit4 + Robolectric, consistent with the other `features/` render tests
 * (e.g. [com.calypsan.listenup.client.features.bookdetail.BookDetailScanWarningTest]).
 */
@RunWith(RobolectricTestRunner::class)
class CampfireLobbyScreenRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        // KoinApplication { } starts the global Koin context on composition and clears it in an
        // onDispose; with the clock paused (below) that teardown may not fire, so clear it here to
        // keep the global context from leaking into sibling tests in the same JVM.
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test
    fun `host lobby renders the host badge without crashing`() {
        val userProfileRepository =
            mock<UserProfileRepository> {
                every { observeProfile(any()) } returns flowOf(null)
            }
        val imageStorage =
            mock<ImageStorage> {
                every { userAvatarExists(any()) } returns false
                every { getUserAvatarPath(any()) } returns ""
            }
        val imageRepository = mock<ImageRepository>()

        val avatarModule =
            module {
                single { userProfileRepository }
                single { imageStorage }
                single { imageRepository }
            }

        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            KoinApplication(application = { modules(avatarModule) }) {
                CampfireLobbyScreen(
                    campfireName = CAMPFIRE_NAME,
                    bookTitle = "The Way of Kings",
                    members =
                        listOf(
                            CampfireMember(
                                userId = HOST_ID,
                                displayName = "Simon",
                                joinedAtEpochMs = 0L,
                                isAway = false,
                                invited = false,
                            ),
                        ),
                    invitedPending = emptyList(),
                    hostUserId = HOST_ID,
                    hostDisplayName = "Simon",
                    isHost = true,
                    onStart = {},
                    reducedMotion = true,
                )
            }
        }

        composeRule.waitForIdle()

        // If layout threw (the negative-padding bug), we never reach here.
        composeRule.onNodeWithText(HOST_BADGE_TEXT).assertExists()
    }

    private companion object {
        const val CAMPFIRE_NAME = "Simon's Campfire"
        const val HOST_ID = "host-1"
        const val HOST_BADGE_TEXT = "HOST"
    }
}
