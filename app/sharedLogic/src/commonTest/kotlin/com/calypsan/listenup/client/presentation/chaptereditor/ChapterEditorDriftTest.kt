package com.calypsan.listenup.client.presentation.chaptereditor

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.ChapterInput
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private const val BOOK_ID = "book-1"
private const val BOOK_MS = 1_200_000L

/**
 * Guided drift correction, as the editor drives it.
 *
 * The engine itself is covered in `ChapterDriftTest`; what these pin down is the flow around it —
 * that a proposal is genuinely a *proposal* (nothing moves until it is applied, abandoning it
 * costs nothing), that the preview and the applied set come from the same computation so the
 * ghosts cannot lie, and that a locked chapter is actually exempted rather than merely counted.
 *
 * That last one is the whole reason lock state exists: before this, `lockedIds` had no source.
 */
class ChapterEditorDriftTest :
    FunSpec({

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

        // Evenly spaced, so a constant shift and a scaling correction are easy to tell apart.
        fun chapters(): List<Chapter> =
            listOf(0L, 300_000L, 600_000L, 900_000L)
                .mapIndexed { i, s -> Chapter(id = "c$i", title = "Chapter ${i + 1}", duration = 0L, startTime = s) }
                .withDerivedDurations(BOOK_MS)

        fun rig(): Pair<ChapterEditorViewModel, MutableList<List<ChapterInput>>> {
            val saved = mutableListOf<List<ChapterInput>>()
            val books = mock<BookRepository>(MockMode.autoUnit)
            every { books.observeChapters(BOOK_ID) } returns MutableStateFlow(chapters())
            every { books.observeBookDetail(BOOK_ID) } returns MutableStateFlow(bookDetail())

            val edits = mock<BookEditRepository>(MockMode.autoUnit)
            everySuspend { edits.setBookChapters(any(), any()) } calls { (_: BookId, c: List<ChapterInput>) ->
                saved += c
                AppResult.Success(Unit)
            }

            return ChapterEditorViewModel(
                bookId = BOOK_ID,
                bookRepository = books,
                bookEditRepository = edits,
                errorBus = ErrorBus(),
            ) to saved
        }

        suspend fun editing(vm: ChapterEditorViewModel): ChapterEditorUiState.Editing =
            vm.state.value as? ChapterEditorUiState.Editing
                ?: error("expected Editing")

        test("there is no drift proposal until the flow is started") {
            val (vm, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().drift.shouldBeNull()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a proposal previews without moving a single boundary") {
            val (vm, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    val before = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().chapters

                    vm.beginDrift()
                    awaitItem()
                    // Chapter 2 really starts 10s later than the scrape claims.
                    vm.pinAnchor("c1", 310_000L)

                    val proposing = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                    val preview = proposing.drift?.preview.shouldBeInstanceOf<DriftPreview.Ready>()

                    withClue("a preview is a proposal — the working set must be untouched") {
                        proposing.chapters shouldBe before
                        proposing.isDirty shouldBe false
                    }
                    withClue("one anchor is a constant shift, so every boundary moves — including 0:00") {
                        preview.affectedCount shouldBe 4
                        preview.corrected.single { it.id == "c1" }.startTime shouldBe 310_000L
                        preview.corrected.single { it.id == "c0" }.startTime shouldBe 10_000L
                    }
                    withClue("and the proposal must differ from what is on screen, or it proposes nothing") {
                        preview.corrected.map { it.startTime } shouldNotBe before.map { it.startTime }
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("applying commits exactly the set that was previewed") {
            // The ghosts and the applied positions come from one pure computation, so there is no
            // second code path that could show one thing and write another.
            val (vm, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem()
                    vm.beginDrift()
                    awaitItem()
                    vm.pinAnchor("c1", 310_000L)

                    val previewed =
                        awaitItem()
                            .shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                            .drift
                            ?.preview
                            .shouldBeInstanceOf<DriftPreview.Ready>()
                            .corrected

                    vm.applyDrift()

                    val applied = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                    applied.chapters.map { it.startTime } shouldBe previewed.map { it.startTime }
                    withClue("applying is an edit, so it becomes undoable work") {
                        applied.isDirty shouldBe true
                        applied.canUndo shouldBe true
                    }
                    withClue("and the flow closes once it has been applied") {
                        applied.drift.shouldBeNull()
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("abandoning a proposal leaves the chapters exactly as they were") {
            val (vm, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    val before = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().chapters

                    vm.beginDrift()
                    awaitItem()
                    vm.pinAnchor("c1", 310_000L)
                    awaitItem()
                    vm.cancelDrift()

                    val after = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                    after.drift.shouldBeNull()
                    after.chapters shouldBe before
                    withClue("abandoning must not leave the editor looking edited") {
                        after.isDirty shouldBe false
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a locked chapter keeps its start when the correction is applied") {
            // The payoff for lock state existing at all: before this, DriftProposal.lockedIds had
            // no source and a pinned boundary was moved along with everything else.
            val (vm, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    val before = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().chapters
                    val lockedStart = before.single { it.id == "c2" }.startTime

                    vm.toggleLock("c2")
                    awaitItem()
                    vm.beginDrift()
                    awaitItem()
                    vm.pinAnchor("c1", 310_000L)
                    awaitItem()
                    vm.applyDrift()

                    val applied = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                    applied.chapters.single { it.id == "c2" }.startTime shouldBe lockedStart
                    withClue("the moved count must exclude what it did not move") {
                        applied.chapters.single { it.id == "c1" }.startTime shouldBe 310_000L
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("anchors that would reverse the book are refused, not applied") {
            val (vm, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    val before = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().chapters

                    vm.beginDrift()
                    awaitItem()
                    vm.pinAnchor("c1", 800_000L)
                    awaitItem()
                    // Later chapter pinned earlier than the one before it: audio does not run backwards.
                    vm.pinAnchor("c3", 100_000L)

                    val refused = awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>()
                    refused.drift
                        ?.preview
                        .shouldBeInstanceOf<DriftPreview.Refused>()
                        .reason shouldBe
                        DriftRefusal.InvertedAnchors

                    vm.applyDrift()

                    withClue("apply on a refused proposal must be a no-op, not a crash or a mangled book") {
                        editing(vm).chapters shouldBe before
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("pinning a third anchor replaces the second rather than growing the set") {
            // correctDrift rejects more than two anchors outright, so the flow can never hold three.
            val (vm, _) = rig()
            runTest {
                vm.state.test {
                    awaitItem()
                    awaitItem()
                    vm.beginDrift()
                    awaitItem()
                    vm.pinAnchor("c1", 310_000L)
                    awaitItem()
                    vm.pinAnchor("c2", 610_000L)
                    awaitItem()
                    vm.pinAnchor("c3", 910_000L)

                    val proposal =
                        awaitItem().shouldBeInstanceOf<ChapterEditorUiState.Editing>().drift?.proposal
                    proposal?.first?.chapterId shouldBe "c1"
                    proposal?.second?.chapterId shouldBe "c3"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
