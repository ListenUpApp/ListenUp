package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** [MatchReceiptViewModel] — Book Detail's receipt after Apply, and its Undo. */
class MatchReceiptViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("the receipt left by Apply shows with the canvas counts; another book's doesn't") {
            runTest(dispatcher) {
                val store = MatchReceiptStore().apply { put(BOOK, receipt()) }
                val vm = MatchReceiptViewModel(BOOK, store, UndoMatch(FakeMatchingRepository()), ErrorBus())
                val other = MatchReceiptViewModel("book-2", store, UndoMatch(FakeMatchingRepository()), ErrorBus())
                backgroundScope.launch { vm.state.collect {} }
                backgroundScope.launch { other.state.collect {} }
                advanceUntilIdle()
                val shown =
                    vm.state.value
                        .shouldBeInstanceOf<MatchReceiptUiState.Shown>()
                        .receipt
                shown.fieldCount shouldBe 5
                shown.coverSource shouldBe HARDCOVER
                shown.chapterNameCount shouldBe 16
                other.state.value shouldBe MatchReceiptUiState.None
            }
        }

        test("Undo restores through the server, clears the receipt and confirms") {
            runTest(dispatcher) {
                val repo = FakeMatchingRepository()
                val store = MatchReceiptStore().apply { put(BOOK, receipt("r-9")) }
                val vm = MatchReceiptViewModel(BOOK, store, UndoMatch(repo), ErrorBus())
                backgroundScope.launch { vm.state.collect {} }
                vm.undo()
                advanceUntilIdle()
                repo.undoRequests shouldBe listOf("r-9")
                vm.state.value shouldBe MatchReceiptUiState.Undone
                store.all.value shouldBe emptyMap()
                vm.dismiss()
                advanceUntilIdle()
                vm.state.value shouldBe MatchReceiptUiState.None
            }
        }

        test("Undo too late removes the receipt and says the book changed since") {
            runTest(dispatcher) {
                val repo = FakeMatchingRepository().apply { undoReply = { AppResult.Failure(MetadataError.UndoExpired()) } }
                val store = MatchReceiptStore().apply { put(BOOK, receipt()) }
                val vm = MatchReceiptViewModel(BOOK, store, UndoMatch(repo), ErrorBus())
                backgroundScope.launch { vm.state.collect {} }
                vm.undo()
                advanceUntilIdle()
                vm.state.value shouldBe MatchReceiptUiState.Expired
                store.all.value shouldBe emptyMap()
            }
        }

        test("an offline Undo keeps the receipt, with its typed error, so it can be tried again") {
            runTest(dispatcher) {
                val offline = TransportError.NetworkUnavailable()
                val repo = FakeMatchingRepository().apply { undoReply = { AppResult.Failure(offline) } }
                val store = MatchReceiptStore().apply { put(BOOK, receipt()) }
                val vm = MatchReceiptViewModel(BOOK, store, UndoMatch(repo), ErrorBus())
                backgroundScope.launch { vm.state.collect {} }
                vm.undo()
                advanceUntilIdle()
                val shown = vm.state.value.shouldBeInstanceOf<MatchReceiptUiState.Shown>()
                shown.undoError shouldBe offline
                shown.undoing shouldBe false
            }
        }
    })
