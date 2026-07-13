package com.calypsan.listenup.client.presentation.chaptereditor

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.chapter.ChapterAnchor
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.domain.TierLabels
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [ChapterEditorViewModel].
 *
 * Covers seeding from the two source flows, every draft-mutating operation's undo-frame and
 * dirty-flag bookkeeping, the drift dry-run/commit split, the save round-trip (success and
 * failure), and the never-stranded "changed elsewhere while dirty" behavior.
 *
 * Uses Mokkery for repository mocks and Turbine for the one-shot [ChapterEditorViewModel.events] flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChapterEditorViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()
        val bookId = "book-1"

        fun chapter(
            id: String,
            startTime: Long,
            duration: Long,
            title: String = "Chapter $id",
            partTitle: String? = null,
            bookTitle: String? = null,
        ): Chapter =
            Chapter(
                id = id,
                title = title,
                duration = duration,
                startTime = startTime,
                partTitle = partTitle,
                bookTitle = bookTitle,
            )

        // Three contiguous 100s chapters spanning a 300s book.
        fun threeChapters(): List<Chapter> =
            listOf(
                chapter("c1", startTime = 0L, duration = 100_000L),
                chapter("c2", startTime = 100_000L, duration = 100_000L),
                chapter("c3", startTime = 200_000L, duration = 100_000L),
            )

        class TestFixture {
            val bookRepository: BookRepository = mock()
            val bookEditRepository: BookEditRepository = mock()
            val errorBus = ErrorBus()
            val chaptersFlow = MutableStateFlow<List<Chapter>>(emptyList())
            val tierLabelsFlow = MutableStateFlow(TierLabels(bookTierLabel = null, partTierLabel = null))

            fun setup() {
                every { bookRepository.observeChapters(bookId) } returns chaptersFlow
                every { bookRepository.observeBookTierLabels(bookId) } returns tierLabelsFlow
            }

            fun build(): ChapterEditorViewModel =
                ChapterEditorViewModel(
                    bookId = bookId,
                    bookRepository = bookRepository,
                    bookEditRepository = bookEditRepository,
                    errorBus = errorBus,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()
            fixture.setup()
            return fixture
        }

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        // ========== Seeding ==========

        test("seeds Editing from observeChapters + observeBookTierLabels") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                fixture.tierLabelsFlow.value = TierLabels(bookTierLabel = "Book", partTierLabel = "Part")
                val vm = fixture.build()

                advanceUntilIdle()

                val state = vm.state.value
                state.shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                state as ChapterEditorUiState.Editing
                state.draft shouldHaveSize 3
                state.tierLabels shouldBe TierLabels(bookTierLabel = "Book", partTierLabel = "Part")
                state.isDirty shouldBe false
                state.canUndo shouldBe false
                state.changedElsewhere shouldBe false
            }
        }

        // ========== retime ==========

        test("retime mutates the draft, marks dirty, pushes one undo frame") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                vm.retime("c2", 150_000L)
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.isDirty shouldBe true
                state.canUndo shouldBe true
                val c1 = state.draft.first { it.id == "c1" }
                val c2 = state.draft.first { it.id == "c2" }
                val c3 = state.draft.first { it.id == "c3" }
                c2.startTime shouldBe 150_000L
                (c1.startTime + c1.duration) shouldBe c2.startTime // contiguity re-derived
                (c2.startTime + c2.duration) shouldBe c3.startTime
                c3.startTime shouldBe 200_000L // unaffected neighbor untouched

                // exactly one undo frame was pushed
                vm.undo()
                advanceUntilIdle()
                val undone = vm.state.value as ChapterEditorUiState.Editing
                undone.draft shouldBe threeChapters()
                undone.isDirty shouldBe false
            }
        }

        test("retime clamps to the open interval between neighbors") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                // Try to push c2 past c3's start (200_000) — must clamp below it.
                vm.retime("c2", 500_000L)
                advanceUntilIdle()

                val c2 = (vm.state.value as ChapterEditorUiState.Editing).draft.first { it.id == "c2" }
                (c2.startTime < 200_000L) shouldBe true
                (c2.startTime > 0L) shouldBe true
            }
        }

        // ========== rename ==========

        test("rename rejects a blank title silently") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                vm.rename("c1", "   ")
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.isDirty shouldBe false
                state.draft.first { it.id == "c1" }.title shouldBe "Chapter c1"
            }
        }

        // ========== undo / resetToSource ==========

        test("undo restores the prior draft and clears dirty when the stack empties") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                vm.rename("c1", "Renamed")
                advanceUntilIdle()
                (vm.state.value as ChapterEditorUiState.Editing).isDirty shouldBe true

                vm.undo()
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.isDirty shouldBe false
                state.canUndo shouldBe false
                state.draft.first { it.id == "c1" }.title shouldBe "Chapter c1"
            }
        }

        test("undo is a no-op when the stack is empty") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                vm.undo() // nothing to undo yet
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.draft shouldBe threeChapters()
                state.isDirty shouldBe false
            }
        }

        test("resetToSource discards all local edits and clears the undo stack") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                vm.rename("c1", "Renamed")
                vm.rename("c2", "Also renamed")
                advanceUntilIdle()
                (vm.state.value as ChapterEditorUiState.Editing).isDirty shouldBe true

                vm.resetToSource()
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.isDirty shouldBe false
                state.canUndo shouldBe false
                state.changedElsewhere shouldBe false
                state.draft shouldBe threeChapters()

                // the undo stack was cleared, not just unwound — undo() is now a no-op
                vm.undo()
                advanceUntilIdle()
                (vm.state.value as ChapterEditorUiState.Editing).draft shouldBe threeChapters()
            }
        }

        // ========== add ==========

        test("add mints a fresh id, keeps the list sorted and contiguous") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                vm.add(150_000L)
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.draft shouldHaveSize 4
                val sorted = state.draft.sortedBy { it.startTime }
                state.draft shouldBe sorted
                for (i in 0 until sorted.lastIndex) {
                    (sorted[i].startTime + sorted[i].duration) shouldBe sorted[i + 1].startTime
                }
                (sorted.last().startTime + sorted.last().duration) shouldBe 300_000L
                val originalIds = setOf("c1", "c2", "c3")
                val freshIds = sorted.map { it.id }.filterNot { it in originalIds }
                freshIds shouldHaveSize 1
            }
        }

        test("add rejects an insertion too close to an existing boundary") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                vm.add(100_010L) // within MIN_CHAPTER_DURATION_MS (50s) of c2's boundary at 100_000
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.draft shouldHaveSize 3
                state.isDirty shouldBe false
            }
        }

        // ========== remove ==========

        test("remove merges the gap into the preceding chapter") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                vm.remove("c2")
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.draft shouldHaveSize 2
                val c1 = state.draft.first { it.id == "c1" }
                val c3 = state.draft.first { it.id == "c3" }
                c1.startTime shouldBe 0L
                c1.duration shouldBe 200_000L // absorbed c2's span
                c3.startTime shouldBe 200_000L
                c3.duration shouldBe 100_000L
            }
        }

        test("removing the first chapter lets the new first chapter absorb the gap down to 0") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                vm.remove("c1")
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                val newFirst = state.draft.minByOrNull { it.startTime }
                newFirst?.id shouldBe "c2"
                newFirst?.startTime shouldBe 0L
            }
        }

        test("remove is a no-op on the last remaining chapter") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = listOf(chapter("only", startTime = 0L, duration = 300_000L))
                val vm = fixture.build()
                advanceUntilIdle()

                vm.remove("only")
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.draft shouldHaveSize 1
                state.isDirty shouldBe false
            }
        }

        // ========== setTierLabel ==========

        test("setTierLabel renames a tier; blank clears it to null") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                vm.setTierLabel(TierKind.BOOK, "Volume")
                advanceUntilIdle()
                (vm.state.value as ChapterEditorUiState.Editing).tierLabels.bookTierLabel shouldBe "Volume"

                vm.setTierLabel(TierKind.BOOK, "   ")
                advanceUntilIdle()
                (vm.state.value as ChapterEditorUiState.Editing).tierLabels.bookTierLabel.shouldBeNull()
            }
        }

        // ========== setSectionLabel ==========

        test("setSectionLabel never invents a value - null clears, blank normalizes to null") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value =
                    listOf(
                        chapter("c1", startTime = 0L, duration = 300_000L, partTitle = "Part One", bookTitle = "Book One"),
                    )
                val vm = fixture.build()
                advanceUntilIdle()

                // blank normalizes to null
                vm.setSectionLabel("c1", partTitle = "   ", bookTitle = "Volume Two")
                advanceUntilIdle()
                var c1 = (vm.state.value as ChapterEditorUiState.Editing).draft.first { it.id == "c1" }
                c1.partTitle.shouldBeNull()
                c1.bookTitle shouldBe "Volume Two"

                // explicit null clears
                vm.setSectionLabel("c1", partTitle = null, bookTitle = null)
                advanceUntilIdle()
                c1 = (vm.state.value as ChapterEditorUiState.Editing).draft.first { it.id == "c1" }
                c1.partTitle.shouldBeNull()
                c1.bookTitle.shouldBeNull()
            }
        }

        // ========== reparent ==========

        test("reparent repositions the chapter and adopts the new parent's existing labels") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value =
                    listOf(
                        chapter("c1", startTime = 0L, duration = 100_000L, bookTitle = "Book One"),
                        chapter("c2", startTime = 100_000L, duration = 100_000L),
                        chapter("c3", startTime = 200_000L, duration = 100_000L),
                    )
                val vm = fixture.build()
                advanceUntilIdle()

                vm.reparent(movedId = "c3", newParentId = "c1", newIndex = 1)
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.draft.map { it.id } shouldBe listOf("c1", "c3", "c2")
                state.draft.first { it.id == "c3" }.bookTitle shouldBe "Book One"
                state.isDirty shouldBe true
                state.canUndo shouldBe true
            }
        }

        // ========== drift ==========

        test("applyDrift dry-run returns ghosts without mutating the draft or pushing undo") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                val preview = vm.applyDrift(listOf(ChapterAnchor("c1", trueStartMs = 10_000L)), emptySet())

                preview.shouldBeInstanceOf<DriftPreview.Ghosts>()
                (preview as DriftPreview.Ghosts).chapters.first { it.id == "c1" }.startTime shouldBe 10_000L

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.draft shouldBe threeChapters()
                state.isDirty shouldBe false
                state.canUndo shouldBe false
            }
        }

        test("applyDrift dry-run surfaces a typed rejection for bad anchors") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                val preview = vm.applyDrift(emptyList(), emptySet())

                preview.shouldBeInstanceOf<DriftPreview.Rejected>()
            }
        }

        test("commitDrift applies the corrected set as exactly one undo frame") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                vm.commitDrift(listOf(ChapterAnchor("c1", trueStartMs = 10_000L)), emptySet())
                advanceUntilIdle()

                val committed = vm.state.value as ChapterEditorUiState.Editing
                committed.isDirty shouldBe true
                committed.canUndo shouldBe true
                committed.draft.first { it.id == "c1" }.startTime shouldBe 10_000L

                vm.undo()
                advanceUntilIdle()
                val undone = vm.state.value as ChapterEditorUiState.Editing
                undone.isDirty shouldBe false
                undone.canUndo shouldBe false
                undone.draft shouldBe threeChapters()
            }
        }

        // ========== save ==========

        test("save calls setBookChapters and setBookTierLabels with the mapped draft, id preserved") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                fixture.tierLabelsFlow.value = TierLabels(bookTierLabel = "Book", partTierLabel = "Part")
                everySuspend { fixture.bookEditRepository.setBookChapters(any(), any()) } returns AppResult.Success(Unit)
                everySuspend { fixture.bookEditRepository.setBookTierLabels(any(), any(), any()) } returns AppResult.Success(Unit)
                val vm = fixture.build()
                advanceUntilIdle()

                vm.rename("c1", "Renamed")
                advanceUntilIdle()

                vm.save()
                advanceUntilIdle()

                verifySuspend {
                    fixture.bookEditRepository.setBookChapters(
                        BookId(bookId),
                        listOf(
                            ChapterInput(id = "c1", title = "Renamed", startTime = 0L, duration = 100_000L),
                            ChapterInput(id = "c2", title = "Chapter c2", startTime = 100_000L, duration = 100_000L),
                            ChapterInput(id = "c3", title = "Chapter c3", startTime = 200_000L, duration = 100_000L),
                        ),
                    )
                }
                verifySuspend {
                    fixture.bookEditRepository.setBookTierLabels(BookId(bookId), "Book", "Part")
                }
            }
        }

        test("save success clears isDirty and the undo stack, emits SavedSuccessfully") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                everySuspend { fixture.bookEditRepository.setBookChapters(any(), any()) } returns AppResult.Success(Unit)
                everySuspend { fixture.bookEditRepository.setBookTierLabels(any(), any(), any()) } returns AppResult.Success(Unit)
                val vm = fixture.build()
                advanceUntilIdle()
                vm.rename("c1", "Renamed")
                advanceUntilIdle()

                vm.events.test {
                    vm.save()
                    advanceUntilIdle()
                    awaitItem() shouldBe ChapterEditorEvent.SavedSuccessfully
                }

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.isDirty shouldBe false
                state.canUndo shouldBe false
                state.isSaving shouldBe false

                // undo stack was cleared by the successful save — undo() is now a no-op
                vm.undo()
                advanceUntilIdle()
                (vm.state.value as ChapterEditorUiState.Editing).draft.first { it.id == "c1" }.title shouldBe "Renamed"
            }
        }

        test("save failure retains the draft and emits SaveFailed with the typed AppError") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val error = InternalError(debugInfo = "boom")
                everySuspend { fixture.bookEditRepository.setBookChapters(any(), any()) } returns AppResult.Failure(error)
                everySuspend { fixture.bookEditRepository.setBookTierLabels(any(), any(), any()) } returns AppResult.Success(Unit)
                val vm = fixture.build()
                advanceUntilIdle()
                vm.rename("c1", "Renamed")
                advanceUntilIdle()

                vm.events.test {
                    vm.save()
                    advanceUntilIdle()
                    val event = awaitItem()
                    event.shouldBeInstanceOf<ChapterEditorEvent.SaveFailed>()
                    (event as ChapterEditorEvent.SaveFailed).error shouldBe error
                }

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.isSaving shouldBe false
                state.isDirty shouldBe true // draft retained, still dirty — never stranded
                state.draft.first { it.id == "c1" }.title shouldBe "Renamed" // untouched by the failure
            }
        }

        test("save failure emits the typed AppError to the global ErrorBus") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val error = InternalError(debugInfo = "boom")
                everySuspend { fixture.bookEditRepository.setBookChapters(any(), any()) } returns AppResult.Failure(error)
                everySuspend { fixture.bookEditRepository.setBookTierLabels(any(), any(), any()) } returns AppResult.Success(Unit)
                val vm = fixture.build()
                advanceUntilIdle()
                vm.rename("c1", "Renamed")
                advanceUntilIdle()

                fixture.errorBus.errors.test {
                    vm.save()
                    advanceUntilIdle()
                    awaitItem() shouldBe error
                }
            }
        }

        // ========== changed elsewhere ==========

        test("a books-domain update while dirty sets changedElsewhere and does NOT overwrite the draft") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()

                vm.rename("c1", "Local Edit")
                advanceUntilIdle()
                (vm.state.value as ChapterEditorUiState.Editing).isDirty shouldBe true

                // A sync frame lands (e.g. another device's edit) while this draft is dirty.
                fixture.chaptersFlow.value =
                    listOf(chapter("c1", title = "Server Edit", startTime = 0L, duration = 300_000L))
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.changedElsewhere shouldBe true
                state.isDirty shouldBe true
                state.draft.first { it.id == "c1" }.title shouldBe "Local Edit" // NOT overwritten
            }
        }

        test("a books-domain update while NOT dirty reseeds the draft silently") {
            runTest {
                val fixture = createFixture()
                fixture.chaptersFlow.value = threeChapters()
                val vm = fixture.build()
                advanceUntilIdle()
                (vm.state.value as ChapterEditorUiState.Editing).isDirty shouldBe false

                fixture.chaptersFlow.value =
                    listOf(chapter("c9", title = "Fresh From Server", startTime = 0L, duration = 300_000L))
                advanceUntilIdle()

                val state = vm.state.value as ChapterEditorUiState.Editing
                state.isDirty shouldBe false
                state.changedElsewhere shouldBe false
                state.draft shouldHaveSize 1
                state.draft.first().id shouldBe "c9"
            }
        }
    })
