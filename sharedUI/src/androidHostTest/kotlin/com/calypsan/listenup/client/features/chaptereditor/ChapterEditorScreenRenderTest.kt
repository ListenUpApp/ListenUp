package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorViewModel
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.domain.TierLabels
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Render smoke-test for [ChapterEditorScreen] — proves the Loading and Editing (default Timing
 * lens) states compose without crashing. This is the largest screen in the chapter-editor arc
 * (top bar, lens toggle, [com.calypsan.listenup.client.design.timeline.MarkerLaneTimeline] with
 * two lanes, the flat chapter list, and the width-adaptive detail-panel shell), so a bare compile
 * pass is not enough evidence — nothing else exercises the whole tree composing together.
 *
 * Gesture-driving (drag a marker, reorder a node) is deliberately out of scope — Robolectric can't
 * reliably drive `pointerInput` drag gestures; [com.calypsan.listenup.client.design.timeline.DragNegotiator]
 * and [com.calypsan.listenup.client.design.reorderable.ReorderableList]'s own tests cover that
 * behavior at the seam where the invariant actually lives.
 *
 * [ChapterEditorViewModel] is constructed directly (bypassing Koin) with Mokkery-mocked
 * [BookRepository]/[BookEditRepository] and a real [ErrorBus] — mirrors
 * `ChapterEditorViewModelTest`'s fixture. The screen's own `koinInject<BookRepository>()` /
 * `koinInject<PlaybackManager>()` calls (the Timing lens' file lane + playhead read) still need a
 * live Koin context, so content is wrapped in [KoinApplication] with those two mocked, following
 * `CampfireLobbyScreenRenderTest`'s established recipe.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterEditorScreenRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    private fun chapter(
        id: String,
        startTime: Long,
        duration: Long,
    ) = Chapter(id = id, title = "Chapter $id", duration = duration, startTime = startTime)

    private fun mockedBookRepository(
        chaptersFlow: Flow<List<Chapter>>,
        tierLabelsFlow: Flow<TierLabels>,
    ): BookRepository =
        mock<BookRepository> {
            every { observeChapters(BOOK_ID) } returns chaptersFlow
            every { observeBookTierLabels(BOOK_ID) } returns tierLabelsFlow
            every { observeBookDetail(any()) } returns flowOf(null)
        }

    private fun mockedPlaybackManager(): PlaybackManager =
        mock<PlaybackManager> {
            every { currentTimeline } returns MutableStateFlow<PlaybackTimeline?>(null)
            every { currentPositionMs } returns MutableStateFlow(0L)
        }

    private fun koinTestModule(
        bookRepository: BookRepository,
        playbackManager: PlaybackManager,
    ) = module {
        single { bookRepository }
        single { playbackManager }
    }

    @Test
    fun `Loading state renders without crashing`() {
        // Two flows that never emit — combine() never fires, so the VM's state stays Loading.
        val bookRepository =
            mockedBookRepository(
                chaptersFlow = MutableSharedFlow(),
                tierLabelsFlow = MutableSharedFlow(),
            )
        val playbackManager = mockedPlaybackManager()
        val viewModel =
            ChapterEditorViewModel(
                bookId = BOOK_ID,
                bookRepository = bookRepository,
                bookEditRepository = mock<BookEditRepository>(),
                errorBus = ErrorBus(),
            )

        composeRule.setContent {
            KoinApplication(application = { modules(koinTestModule(bookRepository, playbackManager)) }) {
                val snackbarHostState = remember { SnackbarHostState() }
                CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
                    ChapterEditorScreen(bookId = BOOK_ID, onBackClick = {}, viewModel = viewModel)
                }
            }
        }

        composeRule.waitForIdle()
        // Reaching here without an exception is the assertion — a Loading spinner has no stable text.
    }

    @Test
    fun `Editing state renders the Timing lens without crashing`() {
        val chaptersFlow = MutableStateFlow(listOf(chapter("c1", 0L, 100_000L), chapter("c2", 100_000L, 100_000L)))
        val tierLabelsFlow = MutableStateFlow(TierLabels(bookTierLabel = null, partTierLabel = null))
        val bookRepository = mockedBookRepository(chaptersFlow, tierLabelsFlow)
        val playbackManager = mockedPlaybackManager()
        val viewModel =
            ChapterEditorViewModel(
                bookId = BOOK_ID,
                bookRepository = bookRepository,
                bookEditRepository = mock<BookEditRepository>(),
                errorBus = ErrorBus(),
            )

        composeRule.setContent {
            KoinApplication(application = { modules(koinTestModule(bookRepository, playbackManager)) }) {
                val snackbarHostState = remember { SnackbarHostState() }
                CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
                    ChapterEditorScreen(bookId = BOOK_ID, onBackClick = {}, viewModel = viewModel)
                }
            }
        }

        composeRule.waitForIdle()

        // The flat chapter-list row for "Chapter c1" proves the Timing lens (timeline + list) composed.
        composeRule.onNodeWithText("1. Chapter c1").assertExists()
    }

    private companion object {
        const val BOOK_ID = "book-1"
    }
}
