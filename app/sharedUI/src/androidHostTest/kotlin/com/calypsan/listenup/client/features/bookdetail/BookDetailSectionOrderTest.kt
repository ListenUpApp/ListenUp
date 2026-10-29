package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.WindowInsets
import com.calypsan.listenup.client.design.components.LocalNowPlayingInsets
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.navigation.PaneSized
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailSection
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsViewModel
import com.calypsan.listenup.client.presentation.bookdetail.BookSeriesPath
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.CurrentChapter
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val inProgressOrder =
    listOf(
        BookDetailSection.Chapters,
        BookDetailSection.About,
        BookDetailSection.Readers,
        BookDetailSection.Ratings,
        BookDetailSection.Hardcover,
        BookDetailSection.Details,
    )

private val notStartedOrder =
    listOf(
        BookDetailSection.About,
        BookDetailSection.Ratings,
        BookDetailSection.Hardcover,
        BookDetailSection.Readers,
        BookDetailSection.Chapters,
        BookDetailSection.Details,
    )

/**
 * Book Detail's sections follow the visit's stage order (spec §2.4) on a phone, split into two columns on a wide
 * layout (§3.2.4); a chapter row plays from its chapter; the social line brings Readers into view (§2.10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class BookDetailSectionOrderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var started: Long? = null

    private companion object {
        const val TALL_PHONE = "w400dp-h4000dp"
        const val TALL_TABLET = "w1280dp-h4000dp"
    }

    /** The composable KoinApplication registers its container globally; cleared on both sides. */
    @Before
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    // A very tall window, so the lazy column composes every item: an absent node means "not placed".
    @Test
    @Config(qualifiers = TALL_PHONE)
    fun `in progress on a phone puts Chapters before About`() {
        setScreen(ready(ReadingStage.InProgress, inProgressOrder))
        val chapters = composeRule.onNode(isHeading() and hasText("Chapters")).getUnclippedBoundsInRoot()
        val about = composeRule.onNodeWithText("About this book").getUnclippedBoundsInRoot()
        (chapters.top < about.top) shouldBe true
    }

    @Test
    @Config(qualifiers = TALL_PHONE)
    fun `not started on a phone leads with About`() {
        setScreen(ready(ReadingStage.NotStarted, notStartedOrder))
        val chapters = composeRule.onNode(isHeading() and hasText("Chapters")).getUnclippedBoundsInRoot()
        val about = composeRule.onNodeWithText("About this book").getUnclippedBoundsInRoot()
        (about.top < chapters.top) shouldBe true
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet Chapters is in the main column and Details in the side column`() {
        setScreen(ready(ReadingStage.InProgress, inProgressOrder))
        val chapters = composeRule.onNode(isHeading() and hasText("Chapters")).getUnclippedBoundsInRoot()
        val details = composeRule.onNode(isHeading() and hasText("Details")).getUnclippedBoundsInRoot()
        (chapters.right < details.left) shouldBe true
    }

    @Test
    @Config(qualifiers = TALL_TABLET)
    fun `in a pane narrower than the wide layout, the sections are one column`() {
        setScreen(ready(ReadingStage.InProgress, inProgressOrder)) { content ->
            PaneSized(modifier = Modifier.width(768.dp)) { content() }
        }
        val chapters = composeRule.onNode(isHeading() and hasText("Chapters")).getUnclippedBoundsInRoot()
        val about = composeRule.onNodeWithText("About this book").getUnclippedBoundsInRoot()
        // One column: the two headings share the column's span, Chapters above About.
        (chapters.left < about.right && about.left < chapters.right) shouldBe true
        (chapters.top < about.top) shouldBe true
    }

    @Test
    fun `a chapter tap asks the player to start at that chapter`() {
        setScreen(ready(ReadingStage.InProgress, inProgressOrder))
        composeRule
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasContentDescription("Play from chapter 7", substring = true))
        composeRule.onNodeWithContentDescription("Play from chapter 7", substring = true).performClick()
        started shouldBe 3_600_000L
    }

    @Test
    fun `the social line brings Readers into view`() {
        setScreen(ready(ReadingStage.InProgress, inProgressOrder), readers = listOf(other("Maya", 40)))
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Maya is listening"))
        composeRule.onNodeWithContentDescription("Maya is listening").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(isHeading() and hasText("Readers")).assertIsDisplayed()
    }

    @Test
    fun `the social line moves focus to the Readers heading on a phone`() {
        setScreen(ready(ReadingStage.InProgress, inProgressOrder), readers = listOf(other("Maya", 40)))
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Maya is listening"))
        composeRule.onNodeWithContentDescription("Maya is listening").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(isHeading() and hasText("Readers", substring = true)).assertIsFocused()
    }

    // On a tablet the card is often on screen already: the tap still has somewhere to go, the heading.
    @Test
    @Config(qualifiers = TALL_TABLET)
    fun `the social line moves focus to the Readers heading on a tablet, even with the card already in view`() {
        setScreen(ready(ReadingStage.InProgress, inProgressOrder), readers = listOf(other("Maya", 40)))
        composeRule.onNode(isHeading() and hasText("Readers", substring = true)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Maya is listening").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(isHeading() and hasText("Readers", substring = true)).assertIsFocused()
    }

    @Test
    fun `arriving from Home's finishers line lands on Readers on a phone`() {
        setScreen(ready(ReadingStage.InProgress, inProgressOrder), readers = listOf(other("Maya", 40)), landOnReaders = true)
        composeRule.waitForIdle()
        composeRule.onNode(isHeading() and hasText("Readers")).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `arriving from Home's finishers line lands on Readers on a tablet`() {
        setScreen(ready(ReadingStage.InProgress, inProgressOrder), readers = listOf(other("Maya", 40)), landOnReaders = true)
        composeRule.waitForIdle()
        composeRule.onNode(isHeading() and hasText("Readers")).assertIsDisplayed()
    }

    // The readers arrive after the page has drawn, as they do from the repository: the hero's social line appears above
    // the columns and pushes Readers down, so the landing has to wait for them, as the phone's does.
    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet, the landing waits for the readers, so Readers is still in view once they arrive`() {
        val arriving = MutableStateFlow<BookReaders?>(null)
        setScreen(ready(ReadingStage.NotStarted, notStartedOrder), readersFlow = arriving.filterNotNull(), landOnReaders = true)
        composeRule.waitForIdle()
        arriving.value = BookReaders(readers = listOf(other("Maya", 40)))
        composeRule.waitForIdle()
        val heading = composeRule.onNode(isHeading() and hasText("Readers")).getUnclippedBoundsInRoot()
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        (heading.top >= root.top && heading.bottom <= root.bottom) shouldBe true
        composeRule.onNodeWithText("Listening").assertIsDisplayed()
    }

    // The mini player floats over the page: at the end of the scroll the last section must clear it, not sit under it.
    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the end of the page clears the mini player`() {
        val player = 200.dp
        setScreen(ready(ReadingStage.InProgress, inProgressOrder)) { content ->
            CompositionLocalProvider(LocalNowPlayingInsets provides WindowInsets(bottom = player)) { content() }
        }
        composeRule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        composeRule.waitForIdle()
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        // The last words of each column: the synopsis ends the main one, the publisher the side one.
        val ends = listOf("A storm is coming.", "Tor").map { composeRule.onNodeWithText(it).getUnclippedBoundsInRoot().bottom }
        (ends.max() <= root.bottom - player) shouldBe true
    }

    @Test
    @Config(qualifiers = TALL_PHONE)
    fun `a book in a series has no series shelf, and the path line still opens the series`() {
        val opened = mutableListOf<String>()
        val inSeries =
            ready(ReadingStage.InProgress, inProgressOrder)
                .copy(seriesPaths = listOf(BookSeriesPath("s1", "Mistborn", "1", emptyList())))
        setScreen(inSeries, actions = noActions().copy(onSeriesClick = { opened += it }))
        composeRule.onNodeWithText("Series ·", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Mistborn #1").performClick()
        opened shouldBe listOf("s1")
    }

    @Test
    fun `nobody else reading draws no social line`() {
        setScreen(ready(ReadingStage.InProgress, inProgressOrder))
        composeRule.onNodeWithContentDescription("is listening", substring = true).assertDoesNotExist()
    }

    private fun other(
        name: String,
        pct: Int,
    ) = Reader(userId = name.lowercase(), displayName = name, isYou = false, currentProgressPct = pct, finishes = emptyList())

    private fun ready(
        stage: ReadingStage,
        order: List<BookDetailSection>,
    ): BookDetailUiState.Ready {
        val chapters =
            List(12) { i ->
                ChapterUiModel(
                    id = "c$i",
                    title = "Chapter title ${i + 1}",
                    duration = "10:00",
                    imageUrl = null,
                    isCurrent = stage == ReadingStage.InProgress && i == 5,
                    startMs = i * 600_000L,
                    durationMs = 600_000L,
                )
            }
        return BookDetailUiState.Ready(
            book =
                BookDetail(
                    id = BookId("b1"),
                    libraryId = LibraryId("lib"),
                    folderId = FolderId("folder"),
                    title = "Words of Radiance",
                    authors = emptyList(),
                    narrators = emptyList(),
                    duration = 7_200_000L,
                    // A local path keeps the cover on its synchronous path, clear of global Koin.
                    coverPath = "/tmp/cover-b1.webp",
                    addedAt = Timestamp(0L),
                    updatedAt = Timestamp(0L),
                    publisher = "Tor",
                ),
            descriptionText = "A storm is coming.",
            downloadStatus = BookDownloadStatus.NotDownloaded("b1"),
            chapters = chapters,
            readingStage = stage,
            sectionOrder = order,
            chapterPreview = if (stage == ReadingStage.InProgress) chapters.subList(3, 8) else chapters.take(5),
            currentChapter =
                if (stage == ReadingStage.InProgress) {
                    CurrentChapter(index = 5, number = 6, title = "Chapter title 6", startMs = 3_000_000L, elapsedInChapterMs = 60_000L)
                } else {
                    null
                },
        )
    }

    private fun setScreen(
        state: BookDetailUiState.Ready,
        readers: List<Reader> = emptyList(),
        readersFlow: Flow<BookReaders> = flowOf(BookReaders(readers = readers)),
        landOnReaders: Boolean = false,
        actions: BookDetailActions = noActions().copy(onPlayFromChapter = { started = it }),
        frame: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ) {
        val readersRepo =
            mock<BookReadersRepository>(MockMode.autoUnit) {
                every { observeReadersFor(any()) } returns readersFlow
            }
        val ratings =
            mock<BookRatingRepository>(MockMode.autoUnit) {
                every { observeForBook(any()) } returns flowOf(emptyList())
                every { observeExternalForBook(any()) } returns flowOf(emptyList())
                every { observeCombinedScore(any()) } returns flowOf(null)
                every { observeExternalCheck(any()) } returns flowOf()
            }
        val users = mock<UserRepository>(MockMode.autoUnit) { every { observeIsAdmin() } returns flowOf(false) }
        // The avatars resolve their collaborators through Koin: the "no profile yet" path draws initials.
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
        composeRule.setContent {
            KoinApplication(
                configuration =
                    koinConfiguration {
                        modules(
                            module {
                                single { profiles }
                                single { storage }
                                single { images }
                                viewModel { (bookId: String) -> BookReadersViewModel(readersRepo, bookId, FakeLoadedBooks()) }
                                viewModel { (bookId: String) ->
                                    BookRatingsViewModel(bookId, ratings, flowOf(null), ErrorBus(), users)
                                }
                            },
                        )
                    },
            ) {
                MaterialTheme {
                    CompositionLocalProvider(LocalSnackbarHostState provides SnackbarHostState()) {
                        frame {
                            BookDetailContent(
                                bookId = "b1",
                                state = state,
                                actions = actions,
                                landOnReaders = landOnReaders,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Every way out of Book Detail, doing nothing. */
internal fun noActions() =
    BookDetailActions(
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
        onRetryConnection = {},
        onDownloadClick = {},
        onCancelClick = {},
        onDeleteClick = {},
        onSeriesClick = {},
        onContributorClick = {},
        onGenreClick = {},
        onTagClick = { _, _ -> },
        onMoodClick = { _, _ -> },
        onUserProfileClick = {},
    )
