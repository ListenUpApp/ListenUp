package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.BookVisibilityRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.PermissionsRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.calypsan.listenup.client.domain.model.SeriesHierarchy
import com.calypsan.listenup.client.domain.repository.SeriesRepository

private const val TIMEOUT_MS = 5_000L

/**
 * Releasing a held book asks first (spec §7): the held section's Release opens "Release to
 * everyone?", and only its own Release sends the book out. Rendered through the real
 * [BookDetailScreen] over a real [BookDetailViewModel], so a Release wired straight to the release
 * call — skipping the question — fails here, not just in review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class BookDetailReleaseConfirmationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val inbox = HeldInbox(BookId(BOOK_ID))

    @Before
    fun startKoinForTheScreen() {
        startKoin {
            modules(
                module {
                    single<BookDetailPlatformActions> { mock(MockMode.autoUnit) }
                    single<InstanceRepository> { mock(MockMode.autoUnit) }
                    // The loading frame's cover resolves through these before the book arrives; a
                    // cover already on disk keeps it off the network.
                    single<ImageRepository> {
                        mock(MockMode.autoUnit) {
                            every { getBookCoverPath(any()) } returns "/tmp/cover-$BOOK_ID.webp"
                            every { bookCoverExists(any()) } returns true
                        }
                    }
                    single<ServerConfig> { mock(MockMode.autoUnit) }
                    single<AuthSession> { mock(MockMode.autoUnit) }
                    single<PlaybackManager> {
                        mock(MockMode.autoUnit) { every { preparingBookIdUi } returns flowOf(null) }
                    }
                },
            )
        }
    }

    @After
    fun stopKoinAfterTheScreen() {
        stopKoin()
    }

    @Test
    fun `Release asks before it releases, and only the dialog's Release sends the book`() {
        render()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(HELD_FOR_REVIEW).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(RELEASE).performClick()

        composeRule.onNodeWithText(CONFIRM_TITLE).assertExists()
        composeRule.runOnIdle { inbox.releases shouldBe 0 }

        // The dialog's Release is the second "Release" on screen, after the held section's own.
        composeRule.onAllNodesWithText(RELEASE)[1].performClick()

        composeRule.waitUntil(TIMEOUT_MS) { inbox.releases == 1 }
        composeRule.onNodeWithText(CONFIRM_TITLE).assertDoesNotExist()
    }

    @Test
    fun `Cancel closes the question and releases nothing`() {
        render()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(HELD_FOR_REVIEW).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(RELEASE).performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText(CONFIRM_TITLE).assertDoesNotExist()
        composeRule.runOnIdle { inbox.releases shouldBe 0 }
    }

    private fun render() {
        val viewModel = viewModel()
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSnackbarHostState provides SnackbarHostState()) {
                    BookDetailScreen(
                        bookId = BOOK_ID,
                        onBackClick = {},
                        onEditClick = {},
                        onMatchDetailsClick = {},
                        matchReceipt = {},
                        onEditChaptersClick = {},
                        onSeriesClick = {},
                        onContributorClick = {},
                        onGenreClick = {},
                        onTagClick = { _, _ -> },
                        onMoodClick = { _, _ -> },
                        onUserProfileClick = {},
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    private fun viewModel(): BookDetailViewModel {
        val book =
            BookDetail(
                id = BookId(BOOK_ID),
                libraryId = LibraryId("lib"),
                folderId = FolderId("folder"),
                title = "The Ministry of Time",
                authors = emptyList(),
                narrators = emptyList(),
                duration = 42_720_000L,
                // A local path keeps the cover on its synchronous path, clear of Koin.
                coverPath = "/tmp/cover-$BOOK_ID.webp",
                addedAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            )
        return BookDetailViewModel(
            bookRepository =
                mock<BookRepository> {
                    every { observeBookDetail(BOOK_ID) } returns flowOf(book)
                    everySuspend { getChapters(BOOK_ID) } returns emptyList()
                },
            tagRepository = mock<TagRepository> { every { observeAll() } returns flowOf(emptyList()) },
            playbackPositionRepository =
                mock<PlaybackPositionRepository> {
                    everySuspend { get(any<BookId>()) } returns AppResult.Success(null)
                },
            userRepository =
                mock<UserRepository> {
                    every { observeCurrentUser() } returns flowOf(null)
                    every { observeIsAdmin() } returns flowOf(true)
                },
            permissionsRepository = mock<PermissionsRepository> { every { observeCan(any()) } returns flowOf(true) },
            shelfRepository =
                mock<ShelfRepository> {
                    every { observeMyShelves(any()) } returns flowOf(emptyList())
                    every { observeShelvesContainingBook(any()) } returns flowOf(emptyList())
                },
            collectionRepository =
                mock<CollectionRepository> { every { observeCollections() } returns flowOf(emptyList()) },
            addBooksToShelfUseCase = mock(MockMode.autoUnit),
            createShelfUseCase = mock(MockMode.autoUnit),
            errorBus = ErrorBus(),
            bookAvailability =
                object : BookAvailability {
                    override fun observe(bookId: BookId): Flow<BookAvailability.State> =
                        flowOf(
                            BookAvailability.State(
                                downloadStatus = BookDownloadStatus.NotDownloaded(bookId.value),
                                isPlaybackAvailable = true,
                                canPlay = true,
                                canDownload = true,
                                showServerWarning = false,
                                isWaitingForWifi = false,
                            ),
                        )
                },
            serverReachability =
                object : ServerReachability {
                    override val state = MutableStateFlow<Reachability>(Reachability.Unknown)

                    override suspend fun retry() = Unit
                },
            documentRepository =
                mock<DocumentRepository> { every { observeDocuments(any()) } returns flowOf(emptyList()) },
            inboxRepository = inbox,
            bookVisibilityRepository =
                mock<BookVisibilityRepository> { every { observeBookVisibility(any()) } returns flowOf(null) },
            bookEditRepository = mock<BookEditRepository>(),
            seriesRepository =
                mock<SeriesRepository> {
                    every { observeHierarchy() } returns flowOf(SeriesHierarchy.Empty)
                },
        )
    }

    /** An inbox holding exactly [held], counting the releases it is asked for and finishing none. */
    private class HeldInbox(
        held: BookId,
    ) : InboxRepository {
        private val heldIds = MutableStateFlow(setOf(held))
        var releases = 0
            private set

        override fun observeHeldBookIds(): Flow<Set<BookId>> = heldIds

        override suspend fun releaseBooks(
            libraryId: String,
            assignments: Map<String, List<String>>,
        ): AppResult<Unit> {
            releases++
            // Stays in flight: a finished release turns the page into the ordinary one, whose
            // rating, readers and Hardcover sections need a Koin graph this spec does not build.
            awaitCancellation()
        }

        override suspend fun listScanIssues(): AppResult<List<ScanIssue>> = AppResult.Success(emptyList())

        override suspend fun dismissScanIssue(issueId: String): AppResult<Unit> = AppResult.Success(Unit)
    }

    private companion object {
        const val BOOK_ID = "b1"
        const val HELD_FOR_REVIEW = "Held for review"
        const val RELEASE = "Release"
        const val CONFIRM_TITLE = "Release to everyone?"
    }
}
