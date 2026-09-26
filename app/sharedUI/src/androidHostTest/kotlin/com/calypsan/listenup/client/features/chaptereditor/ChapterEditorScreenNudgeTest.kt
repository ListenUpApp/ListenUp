package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorViewModel
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

/**
 * The screen is the only place a row's step meets the ViewModel, so it is the only place the unit
 * can be doubled. On 2026-09-15 one tap on "Nudge forward" moved a chapter by 16 min 40 s on a
 * real build: the row already emits milliseconds and the screen multiplied by 1 000 again. The row
 * test and the ViewModel test were both green, because each was right about its own half.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterEditorScreenNudgeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `one nudge from the screen moves the boundary by exactly one coarse step`() {
        val mirror = MutableStateFlow(chapters(0L, 300_000L, 900_000L))
        val books = mock<BookRepository>(MockMode.autoUnit)
        every { books.observeChapters(BOOK_ID) } returns mirror
        every { books.observeBookDetail(BOOK_ID) } returns MutableStateFlow(bookDetail())
        val edits = mock<BookEditRepository>(MockMode.autoUnit)
        val playback = mock<PlaybackManager>(MockMode.autoUnit)
        every { playback.currentTimeline } returns MutableStateFlow<PlaybackTimeline?>(null)
        every { playback.currentPositionMs } returns MutableStateFlow(0L)
        val viewModel =
            ChapterEditorViewModel(
                bookId = BOOK_ID,
                bookRepository = books,
                bookEditRepository = edits,
                errorBus = ErrorBus(),
                playbackManager = playback,
                playbackController = mock<PlaybackController>(MockMode.autoUnit),
            )

        composeRule.setContent {
            MaterialTheme {
                ChapterEditorScreen(bookId = BOOK_ID, onBack = {}, viewModel = viewModel, playbackManager = playback)
            }
        }
        composeRule.waitForIdle()
        // Robolectric composes one row of the lazy list; the first chapter is enough to catch a scaled
        // step. The semantics action invokes the button's own onClick, which is the wiring under test;
        // injected touch is swallowed by the list's scrub gesture in this headless rig.
        composeRule.onAllNodesWithContentDescription("Nudge forward")[0].performSemanticsAction(SemanticsActions.OnClick)
        // The ViewModel projects its draft through a combined flow; wait for the projection to move.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            firstStart(viewModel) != 0L
        }

        assert(firstStart(viewModel) == COARSE_NUDGE_MS) {
            "expected the first chapter at $COARSE_NUDGE_MS ms, got ${firstStart(viewModel)} ms"
        }
    }

    @Test
    fun `the screen renders the mirror over a real ViewModel and reflects its edits`() {
        val mirror = MutableStateFlow(chapters(0L, 300_000L, 900_000L))
        val books = mock<BookRepository>(MockMode.autoUnit)
        every { books.observeChapters(BOOK_ID) } returns mirror
        every { books.observeBookDetail(BOOK_ID) } returns MutableStateFlow(bookDetail())
        val edits = mock<BookEditRepository>(MockMode.autoUnit)
        val playback = mock<PlaybackManager>(MockMode.autoUnit)
        every { playback.currentTimeline } returns MutableStateFlow<PlaybackTimeline?>(null)
        every { playback.currentPositionMs } returns MutableStateFlow(0L)
        val viewModel =
            ChapterEditorViewModel(
                bookId = BOOK_ID,
                bookRepository = books,
                bookEditRepository = edits,
                errorBus = ErrorBus(),
                playbackManager = playback,
                playbackController = mock<PlaybackController>(MockMode.autoUnit),
            )
        composeRule.setContent {
            MaterialTheme { ChapterEditorScreen(bookId = BOOK_ID, onBack = {}, viewModel = viewModel, playbackManager = playback) }
        }
        composeRule.waitForIdle()
        viewModel.nudge("c0", COARSE_NUDGE_MS)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            firstStart(viewModel) != 0L
        }
        assert(firstStart(viewModel) == COARSE_NUDGE_MS) { "direct nudge gave ${firstStart(viewModel)}" }
    }

    private fun firstStart(viewModel: ChapterEditorViewModel): Long =
        (viewModel.state.value as? ChapterEditorUiState.Editing)?.chapters?.firstOrNull()?.startTime ?: -1L

    private fun chapters(vararg starts: Long): List<Chapter> =
        starts.mapIndexed { i, start ->
            val end = starts.getOrNull(i + 1) ?: BOOK_MS
            Chapter(id = "c$i", title = "Chapter $i", duration = end - start, startTime = start)
        }

    private fun bookDetail(): BookDetail =
        BookDetail(
            id = BookId(BOOK_ID),
            libraryId = LibraryId("lib"),
            folderId = FolderId("folder"),
            title = "Wind and Truth",
            authors = emptyList(),
            narrators = emptyList(),
            duration = BOOK_MS,
            coverPath = null,
            addedAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
        )
}

private const val BOOK_ID = "book-1"
private const val BOOK_MS = 1_200_000L
