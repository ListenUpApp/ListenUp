package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditPreviewRow
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Render tests for [BulkEditContent] — the screen shell around the form and the preview.
 *
 * The thing under test is the screen's honesty. A bulk edit has no undo, so every number it shows
 * has to be the number it will act on: the button promises the books that will actually *change*,
 * not the size of the selection, and a selected book that could not be loaded is stated rather than
 * silently dropped. Those two are the failures this screen exists to not commit.
 *
 * JUnit4 + Robolectric is this module's canonical shape for Compose render tests (see
 * [BulkEditPreviewTest]); Robolectric supplies the resource environment so `stringResource` resolves
 * the packaged English strings. The device is sized generously so the whole scrolling column
 * composes — at the default height these assertions would be measuring the viewport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h2400dp")
class BulkEditScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: BulkEditUiState,
        onBack: () -> Unit = {},
        onApply: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                BulkEditContent(
                    state = state,
                    onBack = onBack,
                    onApply = onApply,
                )
            }
        }
    }

    private fun editing(
        bookCount: Int = 40,
        requestedCount: Int = bookCount,
        edits: List<BulkEdit> = emptyList(),
        preview: List<BulkEditPreviewRow> = emptyList(),
        changedBookCount: Int = 0,
        isApplying: Boolean = false,
        selectionSample: List<BookListItem> = emptyList(),
    ) = BulkEditUiState.Editing(
        bookCount = bookCount,
        requestedCount = requestedCount,
        edits = edits,
        preview = preview,
        changedBookCount = changedBookCount,
        isApplying = isApplying,
        selectionSample = selectionSample,
    )

    @Test
    fun `while the selection loads the screen claims nothing about it, but still lets you leave`() {
        // The clock is held so the loading indicator's animation cannot outrun the assertions.
        composeRule.mainClock.autoAdvance = false
        var back = false
        render(state = BulkEditUiState.Loading, onBack = { back = true })

        // No count is known yet, so none is stated — and no field is offered for a set of books
        // nobody has read.
        composeRule.onNodeWithText("Publisher").assertDoesNotExist()
        composeRule.onNodeWithText("Edit 0 books").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Back").performClick()
        back shouldBe true
    }

    @Test
    fun `one selected book is titled in the singular`() {
        render(state = editing(bookCount = 1))

        composeRule.onNodeWithText("Edit 1 book").assertIsDisplayed()
    }

    @Test
    fun `a larger selection is titled by its size`() {
        render(state = editing(bookCount = 12))

        composeRule.onNodeWithText("Edit 12 books").assertIsDisplayed()
    }

    @Test
    fun `apply promises the books that will change, not the books that were selected`() {
        render(
            state =
                editing(
                    bookCount = 40,
                    edits = listOf(BulkEdit.SetPublisher("Tor")),
                    preview = listOf(BulkEditPreviewRow(BulkEdit.SetPublisher("Tor"), affectedCount = 12)),
                    changedBookCount = 12,
                ),
        )

        composeRule.onNodeWithText("Change 12 books").assertIsDisplayed()
        composeRule.onNodeWithText("Change 40 books").assertDoesNotExist()
    }

    @Test
    fun `a single changing book is promised in the singular`() {
        render(
            state =
                editing(
                    bookCount = 40,
                    edits = listOf(BulkEdit.SetPublisher("Tor")),
                    changedBookCount = 1,
                ),
        )

        composeRule.onNodeWithText("Change 1 book").assertIsDisplayed()
    }

    @Test
    fun `apply is unavailable while nothing would change`() {
        render(state = editing(bookCount = 40, changedBookCount = 0))

        composeRule.onNodeWithText("Change").assertIsNotEnabled()
    }

    /**
     * The resting state of the screen, before a single field is touched — so "Change 0 books"
     * would be the first thing every user reads here. It is true, and it reads like a bug. The
     * count appears once there is a count worth stating.
     */
    @Test
    fun `an untouched selection is not promised as zero books`() {
        render(state = editing(bookCount = 40, changedBookCount = 0))

        composeRule.onNodeWithText("Change 0 books").assertDoesNotExist()
    }

    @Test
    fun `apply is offered once something would change`() {
        var applied = false
        render(
            state =
                editing(
                    bookCount = 40,
                    edits = listOf(BulkEdit.SetPublisher("Tor")),
                    changedBookCount = 12,
                ),
            onApply = { applied = true },
        )

        composeRule.onNodeWithText("Change 12 books").performClick()

        applied shouldBe true
    }

    @Test
    fun `an apply in flight says so and cannot be fired twice`() {
        render(
            state =
                editing(
                    bookCount = 40,
                    edits = listOf(BulkEdit.SetPublisher("Tor")),
                    changedBookCount = 12,
                    isApplying = true,
                ),
        )

        composeRule.onNodeWithText("Applying…").assertIsNotEnabled()
    }

    @Test
    fun `one book that could not be loaded is owned up to`() {
        render(state = editing(bookCount = 11, requestedCount = 12))

        composeRule
            .onNodeWithText("1 of the 12 books you selected couldn’t be loaded, so it will not be changed.")
            .assertIsDisplayed()
    }

    @Test
    fun `several books that could not be loaded are counted`() {
        render(state = editing(bookCount = 9, requestedCount = 12))

        composeRule
            .onNodeWithText("3 of the 12 books you selected couldn’t be loaded, so they will not be changed.")
            .assertIsDisplayed()
    }

    @Test
    fun `a selection that loaded whole says nothing about loading`() {
        render(state = editing(bookCount = 12, requestedCount = 12))

        composeRule.onNodeWithText("couldn’t be loaded", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an untouched form says there is nothing to do yet`() {
        render(state = editing(bookCount = 40))

        composeRule.onNodeWithText("Nothing to change yet").assertIsDisplayed()
    }

    @Test
    fun `the first instruction replaces the nothing-to-do note with the preview`() {
        render(
            state =
                editing(
                    bookCount = 40,
                    edits = listOf(BulkEdit.SetPublisher("Tor")),
                    preview = listOf(BulkEditPreviewRow(BulkEdit.SetPublisher("Tor"), affectedCount = 12)),
                    changedBookCount = 12,
                ),
        )

        composeRule.onNodeWithText("Nothing to change yet").assertDoesNotExist()
        composeRule.onNodeWithText("12 of 40 books change", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the editing form offers the three fields it can write`() {
        render(state = editing(bookCount = 40))

        composeRule.onNodeWithText("Publisher").assertIsDisplayed()
        composeRule.onNodeWithText("Publication year").assertIsDisplayed()
        composeRule.onNodeWithText("Language").assertIsDisplayed()
    }

    // ── The hero ────────────────────────────────────────────────────────────────────────────────
    //
    // "Edit 37 books" is a number. The books are what is about to be changed, and this is the last
    // screen on which noticing you picked the wrong ones is free. So the header shows them: the
    // covers it was handed, and an honest count of the ones it had no room for.

    @Test
    fun `the hero shows the selection, and counts the covers it had no room for`() {
        render(state = editing(bookCount = 40, selectionSample = sample(3)))

        composeRule.onNodeWithText("Edit 40 books").assertIsDisplayed()
        // Three covers shown, thirty-seven not — stated, never rounded away.
        composeRule.onNodeWithText("+37").assertIsDisplayed()
    }

    @Test
    fun `a selection the hero can show whole says nothing about a remainder`() {
        render(state = editing(bookCount = 3, selectionSample = sample(3)))

        composeRule.onNodeWithText("+", substring = true).assertDoesNotExist()
    }

    /**
     * The eyebrow counts what was *selected* while the title counts what *loaded*. When those two
     * differ, the header is the first place the shortfall shows — before the notice, before the
     * button, before anything is written.
     */
    @Test
    fun `the hero counts the selection while the title counts what loaded`() {
        render(state = editing(bookCount = 37, requestedCount = 40, selectionSample = sample(2)))

        composeRule.onNodeWithText("LIBRARY · 40 SELECTED").assertIsDisplayed()
        composeRule.onNodeWithText("Edit 37 books").assertIsDisplayed()
    }

    /**
     * A local cover path keeps [com.calypsan.listenup.client.design.components.BookCoverImage] on
     * its synchronous fast path; the async fallback reaches for the global Koin context, which this
     * spec has no reason to stand up. The files need not exist — what is under test is the cluster,
     * not image decoding.
     */
    private fun sample(count: Int): List<BookListItem> =
        (1..count).map { index ->
            BookListItem(
                id = BookId("book-$index"),
                libraryId = LibraryId("library"),
                folderId = FolderId("folder"),
                title = "Book $index",
                authors = emptyList(),
                narrators = emptyList(),
                duration = 0L,
                coverPath = "/tmp/bulk-edit-cover-$index.webp",
                addedAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            )
        }
}
