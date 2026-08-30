package com.calypsan.listenup.client.presentation.bulkedit

import app.cash.turbine.test
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.bulkedit.BulkEditApplier
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.api.dto.BookUpdate
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private const val PUBLISHER = "Tor"

/**
 * The bulk editor's projection and its one destructive button.
 *
 * The property worth pinning: the preview count and what Apply does are computed from the same pure
 * function over the same books, so the number on screen cannot disagree with what happens.
 */
class BulkEditViewModelTest :
    FunSpec({

        // viewModelScope runs on Dispatchers.Main; without this the load coroutine never starts and
        // every state assertion fails against Loading rather than against what it is testing.
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }

        afterTest { Dispatchers.resetMain() }

        fun book(
            id: String,
            publisher: String? = null,
        ) = BookDetail(
            id = BookId(id),
            libraryId = LibraryId("lib"),
            folderId = FolderId("folder"),
            title = id,
            authors = emptyList(),
            narrators = emptyList(),
            duration = 1_000L,
            coverPath = null,
            addedAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
            publisher = publisher,
        )

        // A REAL applier over mocked repositories. BulkEditApplier is a final class, so Mokkery
        // cannot mock it — and building the real one is better anyway: it exercises the actual
        // action-to-repository routing rather than a stub that could drift from it.
        fun rig(
            books: List<BookDetail>,
            failOn: BookId? = null,
            requestedIds: List<String>? = null,
        ): BulkEditViewModel {
            val byId = books.associateBy { it.id.value }
            val repo = mock<BookRepository>(MockMode.autoUnit)
            everySuspend { repo.getBookDetail(any()) } calls { (id: String) -> byId[id] }

            val edits = mock<BookEditRepository>(MockMode.autoUnit)
            val failure = AppResult.Failure(BookError.InvalidInput())
            everySuspend { edits.updateBook(any(), any()) } calls { (id: BookId, _: Any?) ->
                if (id == failOn) failure else AppResult.Success(Unit)
            }
            everySuspend { edits.setBookGenres(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { edits.setBookSeries(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { edits.setBookContributors(any(), any()) } returns AppResult.Success(Unit)

            val tags = mock<TagRepository>(MockMode.autoUnit)
            val moods = mock<MoodRepository>(MockMode.autoUnit)

            return BulkEditViewModel(
                bookIds = requestedIds ?: books.map { it.id.value },
                bookRepository = repo,
                applier = BulkEditApplier(edits, tags, moods),
                errorBus = ErrorBus(),
            )
        }

        test("the preview counts only books that would actually change") {
            // One book already has the publisher, so it is not affected. Counting it would overstate
            // what Apply does, on an operation with no undo.
            val vm = rig(listOf(book("b1", publisher = PUBLISHER), book("b2")))
            runTest {
                vm.state.test {
                    advanceUntilIdle()
                    vm.setPublisher(PUBLISHER)
                    advanceUntilIdle()

                    val editing = vm.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                    editing.preview.single().affectedCount shouldBe 1
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a shared value is offered as context, and differing values are not") {
            val agreeing = rig(listOf(book("b1", publisher = PUBLISHER), book("b2", publisher = PUBLISHER)))
            val differing = rig(listOf(book("b1", publisher = PUBLISHER), book("b2", publisher = "Gollancz")))
            runTest {
                agreeing.state.test {
                    advanceUntilIdle()
                    val editing = agreeing.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                    withClue("all books agree, so the shared value can be shown as a placeholder") {
                        editing.sharedPublisher shouldBe PUBLISHER
                    }
                    cancelAndIgnoreRemainingEvents()
                }
                differing.state.test {
                    advanceUntilIdle()
                    val editing = differing.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                    withClue("the books disagree, so there is no value to offer") {
                        editing.sharedPublisher shouldBe null
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("clearing a field removes its instruction rather than writing an empty value") {
            val vm = rig(listOf(book("b1")))
            runTest {
                vm.state.test {
                    advanceUntilIdle()
                    vm.setPublisher(PUBLISHER)
                    advanceUntilIdle()
                    vm.state.value
                        .shouldBeInstanceOf<BulkEditUiState.Editing>()
                        .edits.size shouldBe 1

                    vm.setPublisher("")
                    advanceUntilIdle()

                    withClue("empty means untouched — it must never become SetPublisher(\"\")") {
                        vm.state.value
                            .shouldBeInstanceOf<BulkEditUiState.Editing>()
                            .edits
                            .shouldBeEmpty()
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("apply reports how many books changed") {
            val vm = rig(listOf(book("b1", publisher = PUBLISHER), book("b2")))
            runTest {
                vm.events.test {
                    vm.setPublisher(PUBLISHER)
                    advanceUntilIdle()
                    vm.apply()
                    advanceUntilIdle()

                    awaitItem().shouldBeInstanceOf<BulkEditEvent.Applied>().changedCount shouldBe 1
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a mid-batch failure reports how many books were already committed") {
            // Books are applied in title order, so b1 is committed before b2 fails. Those commits
            // stand — there is no rollback — and the count is the only thing that tells the user so.
            val vm = rig(listOf(book("b1"), book("b2")), failOn = BookId("b2"))
            runTest {
                vm.events.test {
                    vm.setPublisher(PUBLISHER)
                    advanceUntilIdle()
                    vm.apply()
                    advanceUntilIdle()

                    val failed = awaitItem().shouldBeInstanceOf<BulkEditEvent.Failed>()
                    withClue("the books committed before the failure are not undone") {
                        failed.appliedCount shouldBe 1
                    }
                    failed.error.shouldBeInstanceOf<BookError.InvalidInput>()
                    cancelAndIgnoreRemainingEvents()
                }
                vm.state.test {
                    advanceUntilIdle()
                    withClue("a failed run must not leave the form stuck behind a spinner") {
                        vm.state.value
                            .shouldBeInstanceOf<BulkEditUiState.Editing>()
                            .isApplying shouldBe false
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
        test("a book that could not be loaded is not counted as one Apply will change") {
            // The selection can shrink between the grid and this screen — a book deleted from
            // another device is the realistic way. bookCount must describe what Apply will touch,
            // while requestedCount remembers what the user actually selected, so the screen can own
            // up to the difference instead of quietly editing fewer books than were chosen.
            val vm = rig(listOf(book("b1")), requestedIds = listOf("b1", "gone"))
            runTest {
                vm.state.test {
                    awaitItem()
                    advanceUntilIdle()

                    val editing = vm.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                    editing.bookCount shouldBe 1
                    withClue("the user selected two, and the screen must be able to say so") {
                        editing.requestedCount shouldBe 2
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
        test("the number on Apply is the number Apply reports changing") {
            // The button must not say "Change 2 books" and then report "1 book updated". Both come
            // from the same planning function over the same books, so the promise the user reads
            // and the outcome they get cannot drift apart.
            val vm = rig(listOf(book("b1", publisher = PUBLISHER), book("b2")))
            runTest {
                vm.events.test {
                    vm.state.test {
                        awaitItem()
                        advanceUntilIdle()
                        vm.setPublisher(PUBLISHER)
                        advanceUntilIdle()

                        val editing = vm.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                        withClue("one of the two books already publishes with Tor") {
                            editing.changedBookCount shouldBe 1
                        }
                        editing.bookCount shouldBe 2
                        cancelAndIgnoreRemainingEvents()
                    }

                    vm.apply()
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<BulkEditEvent.Applied>().changedCount shouldBe 1
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
        test("a keystroke past the limit is refused, not thrown") {
            // The setters are driven by a text field, so every intermediate string reaches them.
            // BulkEdit validates eagerly — which is what keeps actionsFor total — so an unguarded
            // setter would turn the 201st character into a crash mid-typing. Refusing the change
            // leaves the last good value in the field, which is what a length-capped field does.
            val vm = rig(listOf(book("b1")))
            runTest {
                vm.state.test {
                    awaitItem()
                    advanceUntilIdle()
                    vm.setPublisher("Tor")
                    advanceUntilIdle()

                    vm.setPublisher("x".repeat(BookUpdate.MAX_PUBLISHER + 1))
                    vm.setLanguage("x".repeat(BookUpdate.MAX_LANGUAGE + 1))
                    vm.setYear(BookUpdate.MAX_YEAR + 1)
                    advanceUntilIdle()

                    val editing = vm.state.value.shouldBeInstanceOf<BulkEditUiState.Editing>()
                    withClue("the over-long publisher must not have replaced the good one") {
                        editing.edits
                            .filterIsInstance<BulkEdit.SetPublisher>()
                            .single()
                            .publisher shouldBe "Tor"
                    }
                    withClue("and nothing invalid may have been recorded") {
                        editing.edits.filterIsInstance<BulkEdit.SetLanguage>().shouldBeEmpty()
                        editing.edits.filterIsInstance<BulkEdit.SetPublishYear>().shouldBeEmpty()
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
