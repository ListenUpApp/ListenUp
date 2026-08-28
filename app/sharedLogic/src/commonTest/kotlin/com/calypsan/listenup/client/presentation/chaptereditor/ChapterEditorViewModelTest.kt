package com.calypsan.listenup.client.presentation.chaptereditor

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest

private const val BOOK_ID = "book-1"
private const val BOOK_MS = 1_200_000L

/**
 * [ChapterEditorViewModel] — the draft-over-mirror projection every client drives.
 *
 * The state is a projection rather than an accumulation, and the tests that matter are the ones
 * about what happens when the two disagree: a sync frame arriving mid-edit must update what the
 * draft is *compared against* without touching the draft itself, and a failed save must cost the
 * user nothing. Those are the cases a hand-managed dirty flag gets wrong.
 */
class ChapterEditorViewModelTest :
    FunSpec({

        // viewModelScope runs on Dispatchers.Main; without this the save coroutine never starts
        // and the failure looks like a state bug rather than an unset dispatcher.
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }

        afterTest { Dispatchers.resetMain() }

        fun bookDetail(): BookDetail =
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

        fun chapters(vararg starts: Long): List<Chapter> =
            starts
                .mapIndexed { i, s ->
                    Chapter(id = "c$i", title = "Chapter $i", duration = 0L, startTime = s)
                }.withDerivedDurations(BOOK_MS)

        fun rig(
            initial: List<Chapter> = chapters(0L, 300_000L, 900_000L),
            saveResult: AppResult<Unit> = AppResult.Success(Unit),
        ): Triple<ChapterEditorViewModel, MutableStateFlow<List<Chapter>>, MutableList<List<ChapterInput>>> {
            val mirror = MutableStateFlow(initial)
            val saved = mutableListOf<List<ChapterInput>>()

            val books = mock<BookRepository>(MockMode.autoUnit)
            every { books.observeChapters(BOOK_ID) } returns mirror
            every { books.observeBookDetail(BOOK_ID) } returns MutableStateFlow(bookDetail())

            val edits = mock<BookEditRepository>(MockMode.autoUnit)
            everySuspend { edits.setBookChapters(any(), any()) } calls { (_: BookId, c: List<ChapterInput>) ->
                saved += c
                saveResult
            }

            val vm =
                ChapterEditorViewModel(
                    bookId = BOOK_ID,
                    bookRepository = books,
                    bookEditRepository = edits,
                    errorBus = ErrorBus(),
                )
            return Triple(vm, mirror, saved)
        }

        test("state follows the local mirror until the first edit") {
            val (vm, mirror, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem() shouldBe ChapterEditorUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                    ready.chapters.size shouldBe 3
                    ready.isDirty shouldBe false

                    mirror.value = chapters(0L, 400_000L, 900_000L)

                    withClue("with no draft open, an incoming change is simply the new truth") {
                        awaitItem()
                            .shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                            .chapters[1]
                            .startTime shouldBe 400_000L
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("the book's own duration is used, not the last chapter's end") {
            // These differ whenever a chapter set does not reach the end of the audio. Deriving the
            // duration from the chapters would quietly shorten the book every time that happened,
            // and every clamp downstream would inherit the error.
            val (vm, _, _) = rig(initial = chapters(0L, 100_000L))
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem()
                        .shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                        .bookDurationMs shouldBe BOOK_MS
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("an edit forks a draft and marks it dirty") {
            val (vm, _, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem()

                    vm.retitle("c1", "Renamed")

                    val edited = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                    edited.chapters[1].title shouldBe "Renamed"
                    edited.isDirty shouldBe true
                    edited.canUndo shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("A SYNC FRAME MID-EDIT NEVER OVERWRITES THE DRAFT — it reports the disagreement") {
            val (vm, mirror, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem()
                    vm.retitle("c1", "Mine")
                    awaitItem()

                    mirror.value = chapters(0L, 555_000L, 900_000L)

                    val after = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                    withClue("the user's work survives untouched") {
                        after.chapters[1].title shouldBe "Mine"
                        after.chapters[1].startTime shouldBe 300_000L
                    }
                    withClue("and they are told, rather than finding out by losing something") {
                        after.changedElsewhere shouldBe true
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("changedElsewhere clears itself when the draft is dropped — it is derived, not latched") {
            val (vm, mirror, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem()
                    vm.retitle("c1", "Mine")
                    awaitItem()
                    mirror.value = chapters(0L, 555_000L, 900_000L)
                    awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().changedElsewhere shouldBe true

                    vm.resetToSource()

                    val after = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                    after.changedElsewhere shouldBe false
                    after.isDirty shouldBe false
                    withClue("resetting adopts what the mirror now holds, not the stale fork point") {
                        after.chapters[1].startTime shouldBe 555_000L
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("undo steps back one edit at a time") {
            val (vm, _, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem()
                    vm.retitle("c1", "First")
                    awaitItem()
                    vm.retitle("c1", "Second")
                    awaitItem()

                    vm.undo()

                    awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().chapters[1].title shouldBe "First"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("an edit that cannot apply spends no undo frame") {
            // A nudge clamped at a neighbour is not an edit. Recording one would bury the user's
            // real change under a dead step and make the undo button lie about what it does.
            val (vm, _, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem()
                    vm.retitle("c1", "Real edit")
                    awaitItem()

                    vm.retitle("c1", "   ")

                    withClue("a blank title is refused, so state does not move at all") {
                        expectNoEvents()
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("saving sends the working set and drops the draft") {
            val (vm, _, saved) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem()
                    vm.retitle("c1", "Saved title")
                    awaitItem()

                    vm.save()

                    // isSaving true, then the post-save state.
                    var last = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                    while (last.isSaving) last = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()

                    saved.single().single { it.id == "c1" }.title shouldBe "Saved title"
                    withClue("the draft is dropped: the repository already wrote it to Room") {
                        last.isDirty shouldBe false
                        last.canUndo shouldBe false
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("A FAILED SAVE COSTS THE USER NOTHING") {
            val (vm, _, _) = rig(saveResult = AppResult.Failure(BookError.InvalidInput(debugInfo = "nope")))
            runTest {
                // Asserted on the settled state rather than by awaiting an emission, because the
                // point of this test is that NOTHING changes — and a StateFlow does not re-emit an
                // identical value. Awaiting here would hang, and the hang would be the correct
                // behaviour wearing a failure's clothes.
                backgroundScope.launch { vm.state.collect {} }
                advanceUntilIdle()
                vm.retitle("c1", "Precious")
                advanceUntilIdle()

                vm.save()
                advanceUntilIdle()

                val settled = vm.state.value.shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                withClue("the edit is still there, still dirty, still undoable") {
                    settled.chapters[1].title shouldBe "Precious"
                    settled.isDirty shouldBe true
                    settled.canUndo shouldBe true
                }
                withClue("and the editor is no longer pretending to save") {
                    settled.isSaving shouldBe false
                }
            }
        }

        test("a failed save is announced once, as an event") {
            val (vm, _, _) = rig(saveResult = AppResult.Failure(BookError.InvalidInput(debugInfo = "nope")))
            runTest {
                backgroundScope.launch { vm.state.collect {} }
                advanceUntilIdle()

                vm.events.test {
                    vm.save()
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<ChapterEditorEvent.SaveFailed>().error.shouldNotBeNull()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("AN INVALID SET IS REFUSED LOCALLY AND NEVER REACHES THE WIRE") {
            // The realistic route here is a drift apply with mis-set anchors rewriting every
            // boundary at once. Without this check the set would be mapped onto ChapterInput,
            // whose `init` throws — inside the save coroutine, surfacing as a save that vanished.
            val (vm, _, saved) = rig()
            runTest {
                backgroundScope.launch { vm.state.collect {} }
                advanceUntilIdle()

                vm.replaceAll(
                    listOf(
                        Chapter(id = "c0", title = "Fine", duration = 0L, startTime = 900_000L),
                        Chapter(id = "c1", title = "Out of order", duration = 0L, startTime = 300_000L),
                    ),
                )
                advanceUntilIdle()

                vm.events.test {
                    vm.save()
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<ChapterEditorEvent.Invalid>().problems.shouldNotBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }

                withClue("nothing left the device") { saved.shouldBeEmpty() }
                withClue("and the user's work is still there to fix") {
                    vm.state.value
                        .shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                        .isDirty shouldBe true
                }
            }
        }

        test("a valid set still saves — the guard refuses the broken, not the ordinary") {
            val (vm, _, saved) = rig()
            runTest {
                backgroundScope.launch { vm.state.collect {} }
                advanceUntilIdle()
                vm.retitle("c1", "Perfectly fine")
                advanceUntilIdle()

                vm.save()
                advanceUntilIdle()

                saved.single().single { it.id == "c1" }.title shouldBe "Perfectly fine"
            }
        }

        test("a book with no chapters is the empty state, and still opens") {
            val (vm, _, _) = rig(initial = emptyList())
            runTest {
                vm.state.test {
                    awaitItem()
                    val ready = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                    withClue("never a dead end — the editor opens so the first boundary can be added") {
                        ready.isEmpty shouldBe true
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("close is idempotent, because three platforms all call it their own way") {
            val (vm, _, _) = rig()
            vm.close()
            vm.close()
        }

        test("locking a boundary pins it, and locking it again lets it go") {
            val (vm, _, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().lockedChapterIds.shouldBeEmpty()

                    vm.toggleLock("c1")
                    awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().lockedChapterIds shouldBe setOf("c1")

                    vm.toggleLock("c1")
                    awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().lockedChapterIds.shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a lock outlives an edit, because it is a decision about the boundary, not about the draft") {
            val (vm, _, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem()

                    vm.toggleLock("c1")
                    awaitItem()

                    vm.retitle("c2", "Renamed")
                    val edited = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                    withClue("editing a different chapter must not quietly release the pin") {
                        edited.lockedChapterIds shouldBe setOf("c1")
                    }

                    vm.undo()
                    withClue("undo steps back an edit; it does not un-decide a lock") {
                        awaitItem()
                            .shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                            .lockedChapterIds shouldBe setOf("c1")
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a lock on a chapter that no longer exists is dropped, not carried") {
            // Derived by intersection rather than removed by hand, so there is no path where a
            // stale id survives — it would reach previewDrift naming a boundary that isn't there,
            // and quietly exempt nothing while claiming to exempt something.
            val (vm, _, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem()

                    vm.toggleLock("c2")
                    awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().lockedChapterIds shouldBe setOf("c2")

                    vm.remove("c2")
                    awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().lockedChapterIds.shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
