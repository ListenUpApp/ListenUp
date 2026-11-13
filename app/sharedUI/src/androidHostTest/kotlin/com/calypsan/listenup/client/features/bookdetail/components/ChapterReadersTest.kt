package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.features.bookdetail.bookReadersViewModel
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaderFace
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaders
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReadership
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.GroupedChapters
import com.calypsan.listenup.client.presentation.bookdetail.ReadersPlace
import com.calypsan.listenup.client.presentation.bookdetail.groupedChapters
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SPAN_MS = 600_000L

/**
 * Every other reader sits on the chapter they are in: avatars a screen reader never meets, and one more sentence on
 * the row's description, listening now first. The row stays one "Play from chapter" button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h3000dp")
class ChapterReadersTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The avatar resolves its collaborators through a composition Koin, which registers globally. */
    @Before
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private val book: List<Chapter> =
        (0 until 12).map { i ->
            Chapter(id = "c$i", title = "Chapter title ${i + 1}", duration = SPAN_MS, startTime = i * SPAN_MS)
        }

    private val rows =
        book.mapIndexed { i, chapter ->
            ChapterUiModel(
                id = chapter.id,
                title = chapter.title,
                duration = "10:00",
                imageUrl = null,
                isCurrent = i == 5,
                startMs = chapter.startTime,
                durationMs = chapter.duration,
            )
        }

    private fun face(
        name: String,
        isLive: Boolean = false,
    ) = ChapterReaderFace(userId = name.lowercase(), displayName = "$name Surname", isLive = isLive)

    private fun row(readers: ChapterReaders?) =
        composeRule.setContent {
            WithAvatarDependencies {
                MaterialTheme {
                    ChapterRow(
                        chapter = rows[6],
                        number = 7,
                        current = null,
                        canPlay = true,
                        onPlayFromChapter = {},
                        readers = readers,
                    )
                }
            }
        }

    @Test
    fun `the row says who is listening now, then who else is on the chapter, after what it does`() {
        row(ChapterReaders(ReadersPlace.ThisChapter, listOf(face("Maya", isLive = true), face("Tom"))))
        composeRule
            .onNodeWithContentDescription(
                "Play from chapter 7: Chapter title 7. Maya is listening now. Tom is on this chapter.",
            )
            // The action keeps its own name: the readers are what the row says, not what it does.
            .assert(
                SemanticsMatcher("click label is the action") {
                    it.config[SemanticsActions.OnClick].label == "Play from chapter 7: Chapter title 7"
                },
            )
    }

    @Test
    fun `four readers draw two faces and a +2 disc, which a screen reader never meets`() {
        row(ChapterReaders(ReadersPlace.ThisChapter, listOf(face("Priya"), face("Tom"), face("Sam"), face("Lee"))))
        composeRule
            .onNodeWithContentDescription("Play from chapter 7: Chapter title 7. Priya, Tom and 2 others are on this chapter.")
            .assertExists()
        composeRule.onAllNodesWithText("+2", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("PS", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a row nobody else is on says only what it does`() {
        row(readers = null)
        composeRule.onNodeWithContentDescription("Play from chapter 7: Chapter title 7").assertExists()
    }

    /** Maya, on the chapter at [index] of [chapters], placed by the real ViewModel and projection. */
    private fun renderWithMayaAt(
        index: Int,
        chapters: List<Chapter>,
        rows: List<ChapterUiModel>,
        preview: List<ChapterUiModel>,
        grouped: GroupedChapters? = null,
    ) {
        val maya =
            Reader(
                userId = "maya",
                displayName = "Maya Okafor",
                isYou = false,
                currentProgressPct = 90,
                finishes = emptyList(),
                currentPositionMs = index * SPAN_MS + 1_000L,
            )
        val repo =
            mock<BookReadersRepository>(MockMode.autoUnit) {
                every { observeReadersFor(any()) } returns flowOf(BookReaders(listOf(maya)))
            }
        val viewModel = bookReadersViewModel(readers = repo, bookId = "b1", chapters = chapters)
        composeRule.setContent {
            WithAvatarDependencies {
                MaterialTheme {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val readership = (state as? BookReadersUiState.Data)?.chapterReaders ?: ChapterReadership.None
                    BookChaptersSection(
                        chapters = rows,
                        preview = preview,
                        currentChapter = null,
                        canPlay = true,
                        onPlayFromChapter = {},
                        grouped = grouped,
                        readers = ChapterReadersOnPage(readership, isAnyoneListening = false),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a reader past the collapsed rows rides on All N chapters, then on their own row once it opens`() {
        renderWithMayaAt(index = 10, chapters = book, rows = rows, preview = rows.subList(3, 8))
        composeRule
            .onNodeWithContentDescription("All 12 chapters. Maya is on a chapter further on.")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Fewer chapters").assertExists()
        composeRule
            .onNodeWithContentDescription("Play from chapter 11: Chapter title 11. Maya is on this chapter.")
            .assertExists()
    }

    @Test
    fun `a closed part's header carries the readers inside it`() {
        // Credits, Part I (folded) with 1 to 6, Part II (folded) with 7 to 9; playback in Part I keeps Part II closed.
        val marked =
            listOf("Credits", "Part I", "1", "2", "3", "4", "5", "6", "Part II", "7", "8", "9").mapIndexed { i, title ->
                Chapter(
                    id = "c$i",
                    title = title,
                    duration = SPAN_MS,
                    startTime = i * SPAN_MS,
                    partTitle = title.takeIf { it.startsWith("Part") },
                )
            }
        val markedRows =
            marked.mapIndexed { i, chapter ->
                ChapterUiModel(
                    id = chapter.id,
                    title = chapter.title,
                    duration = "10:00",
                    imageUrl = null,
                    isCurrent = i == 3,
                    startMs = chapter.startTime,
                    durationMs = chapter.duration,
                )
            }
        renderWithMayaAt(
            index = 10,
            chapters = marked,
            rows = markedRows,
            preview = markedRows.subList(1, 6),
            grouped = marked.groupedChapters(3),
        )
        composeRule.onNodeWithText("All 12 chapters").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithContentDescription("Part II, ", substring = true).assert(
            hasContentDescription("Maya is in this part.", substring = true),
        )
    }
}
