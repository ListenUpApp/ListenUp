package com.calypsan.listenup.client.features.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.calypsan.listenup.client.design.components.LocalRestrictedBookIds
import com.calypsan.listenup.client.domain.model.ProfileRecentBook
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import dev.mokkery.MockMode
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
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A profile's recently-finished cards wear the lock on a restricted book, and only on it. The view
 * model does not fill the recent list yet, so the card is driven from a hand-built Ready state — the
 * lock is pinned for the day it does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class UserProfileRestrictedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    @Test
    fun `a recent book card locks the restricted book only`() {
        // The hero's avatar reads the profile cache and the image store, and each card resolves its
        // cover by book id through the server config and session; none has anything here.
        val profiles = mock<UserProfileRepository> { every { observeProfile(any()) } returns flowOf(null) }
        val avatars =
            module {
                single { profiles }
                single { mock<ImageStorage>(MockMode.autofill) }
                single { mock<ImageRepository>(MockMode.autofill) }
                single { mock<ServerConfig>(MockMode.autofill) }
                single { mock<AuthSession>(MockMode.autofill) }
            }
        composeRule.setContent {
            KoinApplication(application = { modules(avatars) }) {
                MaterialTheme {
                    CompositionLocalProvider(LocalRestrictedBookIds provides setOf("restricted")) {
                        ProfileContent(
                            state = READY,
                            onBack = {},
                            onEditClick = {},
                            onBookClick = {},
                            onShelfClick = {},
                            onCreateShelfClick = {},
                        )
                    }
                }
            }
        }
        composeRule.onAllNodesWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertCountEquals(1)
    }

    private companion object {
        const val RESTRICTED_A11Y = "In a collection, so only people it is shared with can see it."

        val READY =
            UserProfileUiState.Ready(
                userId = "u1",
                isOwnProfile = false,
                displayName = "Alex",
                avatarColor = "#6750A4",
                tagline = null,
                totalListenTimeMs = 0L,
                booksFinished = 2,
                currentStreak = 0,
                longestStreak = 0,
                recentBooks =
                    listOf(
                        ProfileRecentBook(bookId = "restricted", title = "Dune", coverHash = null),
                        ProfileRecentBook(bookId = "open", title = "Emma", coverHash = null),
                    ),
                publicShelves = emptyList(),
            )
    }
}
