package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.features.bookdetail.components.DetailsSection
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailRow
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.bookDetailRows
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Book Detail's Details rows (spec §3.2.1 D31): the facts in order, then three links. */
@RunWith(RobolectricTestRunner::class)
class DetailsSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var credits = 0
    private var files = 0
    private val maps = mutableListOf<BookDetailRow.MapsAndIllustrations>()

    private val file =
        AudioFile(
            id = "a1",
            index = 0,
            filename = "disc1/01.m4b",
            format = "m4b",
            codec = "aac",
            duration = 163_800_000L,
            size = 1_310_400_000L,
            bitrate = 64_000,
            sampleRate = 44_100,
            channels = 2,
        )

    private val full =
        BookDetailUiState.Ready(
            book =
                BookDetail(
                    id = BookId("b1"),
                    libraryId = LibraryId("l"),
                    folderId = FolderId("f"),
                    title = "The Way of Kings",
                    authors = emptyList(),
                    narrators = emptyList(),
                    duration = 163_800_000L,
                    coverPath = null,
                    addedAt = Timestamp(0L),
                    updatedAt = Timestamp(0L),
                    publisher = "Macmillan Audio",
                    language = "en",
                    isbn = "9781427209979",
                    asin = "B003ZWFO7E",
                    genres =
                        listOf(
                            Genre(id = "g1", name = "Fantasy", slug = "fantasy", path = "/fiction/fantasy"),
                            Genre(id = "g2", name = "Epic fantasy", slug = "epic", path = "/fiction/fantasy/epic"),
                        ),
                    allContributors = List(6) { BookContributor(id = "c$it", name = "P$it") },
                    audioFiles = listOf(file),
                ),
            year = 2010,
            addedAt = 1_770_854_400_000L,
            chapters = List(75) { ChapterUiModel(id = "ch$it", title = "", duration = "", imageUrl = null) },
        )

    private fun doc(
        id: String,
        format: String = "pdf",
    ) = BookDocument(id = id, index = 0, filename = "extras/$id.$format", format = format, size = 18L * 1024 * 1024, hash = "h")

    private fun show(rows: List<BookDetailRow>) {
        composeRule.setContent {
            MaterialTheme {
                // Scrollable, as the page is: the last rows fall below the test window.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DetailsSection(
                        rows = rows,
                        onAllCredits = { credits++ },
                        onFilesAndAudio = { files++ },
                        onMaps = { maps += it },
                    )
                }
            }
        }
    }

    @Test
    fun `every fact the book carries, in the spec's order`() {
        show(bookDetailRows(full, listOf(doc("map"))))
        val labels =
            listOf(
                "Publisher",
                "Released",
                "Genre",
                "Language",
                "Length",
                "Added",
                "ISBN",
                "ASIN",
                "All credits (6)",
                "Files & audio (AAC · 64 kbps)",
                "Maps & illustrations (PDF · 18 MB)",
            )
        val tops = labels.map { composeRule.onNodeWithText(it, useUnmergedTree = true).getUnclippedBoundsInRoot().top }
        tops shouldBe tops.sorted()
        composeRule.onNodeWithText("Macmillan Audio").assertExists()
        composeRule.onNodeWithText("2010").assertExists()
        composeRule.onNodeWithText("Epic fantasy · Unabridged").assertExists()
        composeRule.onNodeWithText("English").assertExists()
        composeRule.onNodeWithText("45h 30m · 75 chapters").assertExists()
        composeRule.onNodeWithText("9781427209979").assertExists()
        composeRule.onNodeWithText("B003ZWFO7E").assertExists()
    }

    // Spec D31: a link row reads label first, its count or format in brackets — "All credits (6) ›" — never the value
    // ahead of what it is ("6 … All credits").
    @Test
    fun `the link rows read label first, as the spec writes them`() {
        show(bookDetailRows(full, listOf(doc("map"))))
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        listOf("All credits (6)", "Files & audio (AAC · 64 kbps)", "Maps & illustrations (PDF · 18 MB)").forEach {
            composeRule.onNode(button and hasText(it)).assertExists()
        }
        composeRule.onNodeWithText("6").assertDoesNotExist()
    }

    @Test
    fun `the format rows left Details for the Files and audio sheet, and credits are one row`() {
        show(bookDetailRows(full, emptyList()))
        listOf("Format", "Bitrate", "Sample rate", "Channels", "Narrators", "Authors").forEach {
            composeRule.onNodeWithText(it).assertDoesNotExist()
        }
        composeRule.onNodeWithText("Maps & illustrations", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the three links are buttons and report their taps`() {
        show(bookDetailRows(full, listOf(doc("map"), doc("notes", "epub"))))
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        composeRule.onNode(button and hasText("All credits", substring = true)).performScrollTo().performClick()
        composeRule.onNode(button and hasText("Files & audio", substring = true)).performScrollTo().performClick()
        composeRule.onNode(button and hasText("Maps & illustrations", substring = true)).performScrollTo().performClick()
        composeRule.onAllNodesWithText("Publisher").fetchSemanticsNodes().size shouldBe 1
        composeRule.onNode(button and hasText("Publisher")).assertDoesNotExist()
        composeRule.runOnIdle {
            credits shouldBe 1
            files shouldBe 1
            maps.single().onlyDocumentId shouldBe null
            maps.single().count shouldBe 2
        }
    }

    @Test
    fun `a bare book draws only the rows it has`() {
        val bare =
            full.copy(
                book = full.book.copy(publisher = null, isbn = null, asin = null, genres = emptyList(), language = null),
                year = null,
            )
        show(bookDetailRows(bare, emptyList()))
        listOf("Publisher", "Released", "Genre", "Language", "ISBN", "ASIN").forEach {
            composeRule.onNodeWithText(it).assertDoesNotExist()
        }
        composeRule.onNodeWithText("Length").assertExists()
    }
}
