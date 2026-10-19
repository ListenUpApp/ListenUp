package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.result.AppResult
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
import org.koin.compose.KoinApplication
import org.koin.core.context.stopKoin
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Home's finishers line lands on Book Detail's Readers (spec §6.4): focus — and TalkBack's with it — moves to the
 * Readers heading, on the phone column (frameless) and the wide card alike.
 */
@RunWith(RobolectricTestRunner::class)
class BookReadersHeadingFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The avatar's composable KoinApplication registers itself globally; cleared on both sides. */
    @Before
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private val readers =
        listOf(ReaderRowUi(userId = "u1", name = "Maya", isReading = false, progressPct = null, finishedWhen = "Yesterday"))

    private fun show(
        isCard: Boolean,
        focusHeadingOnArrival: Boolean,
        onHeadingFocused: () -> Unit = {},
    ) = composeRule.setContent {
        WithAvatarDependencies {
            BookReadersContent(
                readers = readers,
                listeningNowCount = 0,
                totalCount = 1,
                isCard = isCard,
                onUserClick = {},
                focusHeadingOnArrival = focusHeadingOnArrival,
                onHeadingFocused = onHeadingFocused,
            )
        }
    }

    @Test
    fun `on the phone column, landing focuses the Readers heading and says so`() {
        var focused = 0
        show(isCard = false, focusHeadingOnArrival = true, onHeadingFocused = { focused++ })
        composeRule.onNode(isHeading()).assertIsFocused()
        composeRule.runOnIdle { focused shouldBe 1 }
    }

    @Test
    fun `on the wide card, landing focuses the Readers heading`() {
        show(isCard = true, focusHeadingOnArrival = true)
        composeRule.onNode(isHeading()).assertIsFocused()
    }

    @Test
    fun `without a landing, focus stays where it was`() {
        show(isCard = false, focusHeadingOnArrival = false)
        composeRule.onNode(isHeading()).assertIsNotFocused()
    }

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
}
