package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * A read logged on Hardcover never reads as a ListenUp finish: its row says "Read {date}" and carries
 * a "Hardcover" label (text — never Hardcover's logo), and a ListenUp finish carries neither.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderRowHardcoverTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The composable KoinApplication registers its container as the global Koin context, and adopts one
     * already running. Cleared on both sides: a spec that ran before this one must not hand the avatar
     * its container, and this one must not leave its container to the next.
     */
    @Before
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun row(
        isOnHardcover: Boolean,
        isAlsoOnHardcover: Boolean = false,
    ) = ReaderRowUi(
        userId = "u1",
        name = "Ada",
        isReading = false,
        progressPct = null,
        finishedWhen = "Mar 2017",
        isOnHardcover = isOnHardcover,
        isAlsoOnHardcover = isAlsoOnHardcover,
    )

    /**
     * The row's avatar resolves its collaborators through Koin; stubbed to the "no profile yet" path so
     * the avatar is a placeholder and this test stays about the finished line.
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
    fun `a Hardcover read says Read with its date and wears the Hardcover label`() {
        composeRule.setContent { WithAvatarDependencies { ReaderRow(reader = row(isOnHardcover = true), onUserClick = {}) } }

        // Drawn as the date beside a "Hardcover" label, read as one phrase (#1562).
        composeRule.onNodeWithContentDescription("Read Mar 2017 on Hardcover", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Finished Mar 2017").assertDoesNotExist()
    }

    @Test
    fun `a ListenUp finish says Finished and wears no label`() {
        composeRule.setContent { WithAvatarDependencies { ReaderRow(reader = row(isOnHardcover = false), onUserClick = {}) } }

        composeRule.onNodeWithText("Finished Mar 2017").assertIsDisplayed()
        composeRule.onNodeWithText("Hardcover").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Hardcover", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a finish also logged on Hardcover is one row that says so, read as part of the row`() {
        composeRule.setContent {
            WithAvatarDependencies {
                ReaderRow(reader = row(isOnHardcover = false, isAlsoOnHardcover = true), onUserClick = {})
            }
        }

        // The row is one accessible element: its name, then the date and where else it was logged.
        composeRule
            .onNode(hasClickAction())
            .assertIsDisplayed()
            .assert(hasText("Ada"))
            .assert(hasContentDescription("Finished Mar 2017, also on Hardcover"))
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        composeRule.onNodeWithText("Read Mar 2017").assertDoesNotExist()
    }
}
