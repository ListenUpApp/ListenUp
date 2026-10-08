package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsViewModel
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Remove shows "Rating removed" with Undo, and Undo puts back the stars and the note. */
@RunWith(RobolectricTestRunner::class)
class BookRatingBlockUndoTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `Remove offers Undo, and Undo puts back the stars and the note`() {
        val mine = ListenerRating(bookId = "b1", userId = "me", halfStars = 8, note = "Loved it", ratedAtMs = 1L)
        val ratings =
            mock<BookRatingRepository>(MockMode.autoUnit) {
                every { observeForBook(any()) } returns flowOf(listOf(mine))
                every { observeExternalForBook(any()) } returns flowOf(emptyList())
                every { observeCombinedScore(any()) } returns flowOf(null)
                every { observeExternalCheck(any()) } returns flowOf()
                everySuspend { clear(any()) } returns AppResult.Success(Unit)
                everySuspend { rate(any(), any(), any()) } returns AppResult.Success(Unit)
            }
        val users = mock<UserRepository>(MockMode.autoUnit) { every { observeIsAdmin() } returns flowOf(false) }
        val viewModel =
            BookRatingsViewModel(
                bookId = "b1",
                repository = ratings,
                currentUserId = flowOf("me"),
                errorBus = ErrorBus(),
                userRepository = users,
            )
        val snackbars = SnackbarHostState()

        composeRule.setContent {
            CompositionLocalProvider(LocalSnackbarHostState provides snackbars) {
                Column {
                    BookRatingBlock(bookId = "b1", viewModel = viewModel)
                    SnackbarHost(snackbars)
                }
            }
        }

        composeRule.onNodeWithText("Remove").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Rating removed").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()

        verifySuspend { ratings.clear("b1") }
        verifySuspend { ratings.rate("b1", 8, "Loved it") }
    }
}
