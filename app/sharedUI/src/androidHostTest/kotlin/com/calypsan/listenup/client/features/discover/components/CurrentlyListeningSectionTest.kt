package com.calypsan.listenup.client.features.discover.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "What Others Are Listening To" renders two kinds of row, and the difference has to be visible.
 *
 * The section used to show live presence only and hide itself whenever nobody was listening — which,
 * on a server with three people, is nearly always. A section that silently disappears is
 * indistinguishable from one that is broken, so it now fills with each other person's most recently
 * played book. That only helps if a reader can tell "right now" from "two days ago" at a glance:
 * hence the per-card marker asserted here.
 *
 * The section still hides on a genuinely empty roster — nobody has ever listened — because inventing
 * a row for an empty server would be the opposite failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CurrentlyListeningSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val nowMs = 1_000_000_000L

    private fun session(
        userId: String,
        bookTitle: String,
        isLive: Boolean,
        lastActiveAt: Long,
    ) = CurrentlyListeningUiSession(
        sessionId = "$userId-session",
        userId = userId,
        bookId = "book-$userId",
        bookTitle = bookTitle,
        authorName = "An Author",
        // A local path (the file need not exist) keeps BookCoverImage on its synchronous fast path;
        // the async fallback reaches for the GLOBAL Koin context, which a composition-scoped
        // KoinApplication does not satisfy. Cover loading is not what this spec is about.
        coverPath = "/tmp/cover-$userId.webp",
        coverHash = null,
        displayName = "Display $userId",
        lastActiveAt = lastActiveAt,
        isLive = isLive,
    )

    /**
     * The avatar badge on each card resolves its three collaborators through Koin, so the section
     * cannot compose without a container. Stubbed to the "no profile yet" path — the avatar renders
     * a placeholder and never reaches image loading, which keeps this test about the marker text.
     */
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

    @Test
    fun `an empty roster renders nothing`() {
        composeRule.setContent {
            WithAvatarDependencies {
                CurrentlyListeningSection(
                    state = CurrentlyListeningUiState.Ready(sessions = emptyList()),
                    onBookClick = {},
                    nowMs = nowMs,
                )
            }
        }
        composeRule.onNodeWithText("What Others Are Listening To").assertDoesNotExist()
    }

    @Test
    fun `a live listener is marked listening now`() {
        composeRule.setContent {
            WithAvatarDependencies {
                CurrentlyListeningSection(
                    state =
                        CurrentlyListeningUiState.Ready(
                            sessions = listOf(session("live", "Wind and Truth", isLive = true, lastActiveAt = nowMs - 60_000L)),
                        ),
                    onBookClick = {},
                    nowMs = nowMs,
                )
            }
        }
        composeRule.onNodeWithText("What Others Are Listening To").assertIsDisplayed()
        composeRule.onNodeWithText("Wind and Truth").assertIsDisplayed()
        composeRule.onNodeWithText("Listening now").assertIsDisplayed()
    }

    @Test
    fun `a non-live row is marked with how long ago they listened`() {
        composeRule.setContent {
            WithAvatarDependencies {
                CurrentlyListeningSection(
                    state =
                        CurrentlyListeningUiState.Ready(
                            sessions =
                                listOf(
                                    session(
                                        "recent",
                                        "The Way of Kings",
                                        isLive = false,
                                        lastActiveAt = nowMs - 3 * 24 * 60 * 60 * 1000L,
                                    ),
                                ),
                        ),
                    onBookClick = {},
                    nowMs = nowMs,
                )
            }
        }
        composeRule.onNodeWithText("The Way of Kings").assertIsDisplayed()
        // Reuses the shared relativeLastActive helper — no second relative-time vocabulary.
        composeRule.onNodeWithText("3 days ago").assertIsDisplayed()
        composeRule.onNodeWithText("Listening now").assertDoesNotExist()
    }
}
