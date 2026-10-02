package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.CollectionRef
import com.calypsan.listenup.client.domain.model.HiddenFrom
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsViewModel
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Visibility card sits after About on Book Detail, routes its taps, and never joins triage. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class BookDetailVisibilityPlacementTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private companion object {
        const val TALL_PHONE = "w400dp-h4000dp"
    }

    private var opened: String? = null
    private var restores = 0

    @Test
    fun `a restricted book's detail shows the card, and a name opens its collection`() {
        show(ready(BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Everyone)))
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Kids"))
        composeRule.onNodeWithText("Kids").performClick()
        composeRule.runOnIdle { opened shouldBe "c1" }
    }

    @Test
    fun `a stranded book's fix reaches the ViewModel without a dialog`() {
        show(ready(BookVisibility.Stranded))
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Show to all members"))
        composeRule.onNodeWithText("Show to all members").performClick()
        composeRule.runOnIdle { restores shouldBe 1 }
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `the wide layout carries the card too, and its fix reaches the ViewModel`() {
        show(ready(BookVisibility.Stranded))
        composeRule.onNodeWithText("Hidden from all members").assertExists()
        composeRule.onNodeWithText("Show to all members").performScrollTo().performClick()
        composeRule.runOnIdle { restores shouldBe 1 }
    }

    @Test
    fun `a member's detail — no visibility — has no card`() {
        show(ready(visibility = null))
        composeRule.onNodeWithText("Visibility").assertDoesNotExist()
    }

    @Test
    fun `a held book's triage layout has no Visibility card — the held section says it`() {
        show(ready(BookVisibility.Held).copy(isHeld = true, canPlay = false))
        composeRule.onNodeWithText("Release").assertExists()
        composeRule.onNodeWithText("Visibility").assertDoesNotExist()
    }

    // A phone width with a very tall window, so the lazy column composes every item: an absent node
    // then means "not placed", never "not scrolled to yet".
    @Test
    @Config(qualifiers = TALL_PHONE)
    fun `even for a frame where held and visibility disagree, triage shows no card`() {
        val restricted = BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Everyone)
        show(ready(restricted).copy(isHeld = true, canPlay = false))
        composeRule.onNodeWithText("Release").assertExists()
        composeRule.onNodeWithText("Visibility").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `the wide triage layout shows no card either, even mid-disagreement`() {
        val restricted = BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Everyone)
        show(ready(restricted).copy(isHeld = true, canPlay = false))
        composeRule.onNodeWithText("Release").assertExists()
        composeRule.onNodeWithText("Visibility").assertDoesNotExist()
    }

    private fun ready(visibility: BookVisibility?) =
        BookDetailUiState.Ready(
            book =
                BookDetail(
                    id = BookId("b1"),
                    libraryId = LibraryId("lib"),
                    folderId = FolderId("folder"),
                    title = "Dune",
                    authors = emptyList(),
                    narrators = emptyList(),
                    duration = 1_200_000L,
                    coverPath = "/tmp/cover-b1.webp",
                    addedAt = Timestamp(0L),
                    updatedAt = Timestamp(0L),
                ),
            isAdmin = true,
            visibility = visibility,
        )

    private fun show(state: BookDetailUiState.Ready) {
        val readers =
            mock<BookReadersRepository>(MockMode.autoUnit) {
                every { observeReadersFor(any()) } returns flowOf(BookReaders(readers = emptyList()))
            }
        val ratings =
            mock<BookRatingRepository>(MockMode.autoUnit) {
                every { observeForBook(any()) } returns flowOf(emptyList())
                every { observeExternalForBook(any()) } returns flowOf(emptyList())
                every { observeCombinedScore(any()) } returns flowOf(null)
            }
        val users = mock<UserRepository>(MockMode.autoUnit) { every { observeIsAdmin() } returns flowOf(true) }
        composeRule.setContent {
            KoinApplication(
                application = {
                    modules(
                        module {
                            viewModel { (bookId: String) -> BookReadersViewModel(readers, bookId) }
                            viewModel { (bookId: String) ->
                                BookRatingsViewModel(bookId, ratings, flowOf(null), ErrorBus(), users)
                            }
                        },
                    )
                },
            ) {
                MaterialTheme {
                    BookDetailContent(
                        bookId = "b1",
                        state = state,
                        downloadStatus = BookDownloadStatus.NotDownloaded("b1"),
                        isComplete = false,
                        hasProgress = false,
                        isAdmin = true,
                        isWaitingForWifi = false,
                        showPlaybackActions = !state.isHeld,
                        onBackClick = {},
                        onEditClick = {},
                        onFindMetadataClick = {},
                        onEditChaptersClick = {},
                        onMarkCompleteClick = {},
                        onMarkNotStartedClick = {},
                        onRestartClick = {},
                        onAddToShelfClick = {},
                        onAddToCollectionClick = {},
                        onShareClick = {},
                        onDeleteBookClick = {},
                        onPlayClick = {},
                        canPlay = state.canPlay,
                        canDownload = false,
                        showServerWarning = false,
                        onRetryConnection = {},
                        onPlayDisabledClick = {},
                        onDownloadClick = {},
                        onCancelClick = {},
                        onDeleteClick = {},
                        onSeriesClick = {},
                        onContributorClick = {},
                        onGenreClick = {},
                        onTagClick = { _, _ -> },
                        onMoodClick = { _, _ -> },
                        onUserProfileClick = {},
                        onCollectionClick = { opened = it },
                        onRestoreToAllBooksClick = { restores++ },
                    )
                }
            }
        }
    }
}
