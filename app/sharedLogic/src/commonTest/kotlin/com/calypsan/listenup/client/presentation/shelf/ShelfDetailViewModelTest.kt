package com.calypsan.listenup.client.presentation.shelf

import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.domain.usecase.shelf.LoadShelfDetailUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.RemoveBookFromShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.ReorderShelfBooksUseCase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [ShelfDetailViewModel].
 *
 * Covers: ownership flag surfaced straight from [ShelfDetail.isOwner] (no separate
 * identity check), the reorder action dispatching to the use case, and load failure
 * mapping to the Error state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShelfDetailViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        fun detail(isOwner: Boolean) =
            ShelfDetail(
                id = ShelfId("s1"),
                name = "Reads",
                description = null,
                isPrivate = false,
                isOwner = isOwner,
                bookCount = 2,
                totalDurationSeconds = 0,
                books = emptyList(),
            )

        fun viewModel(
            load: LoadShelfDetailUseCase = mock(),
            remove: RemoveBookFromShelfUseCase = mock(),
            reorder: ReorderShelfBooksUseCase = mock(),
        ) = ShelfDetailViewModel(load, remove, reorder)

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("loadShelf surfaces isOwner from the detail (owner)") {
            runTest {
                val load: LoadShelfDetailUseCase =
                    mock { everySuspend { invoke(ShelfId("s1")) } returns AppResult.Success(detail(isOwner = true)) }
                val vm = viewModel(load = load)

                vm.loadShelf("s1")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<ShelfDetailUiState.Ready>()
                ready.isOwner shouldBe true
            }
        }

        test("loadShelf surfaces isOwner from the detail (non-owner)") {
            runTest {
                val load: LoadShelfDetailUseCase =
                    mock { everySuspend { invoke(ShelfId("s1")) } returns AppResult.Success(detail(isOwner = false)) }
                val vm = viewModel(load = load)

                vm.loadShelf("s1")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<ShelfDetailUiState.Ready>()
                ready.isOwner shouldBe false
            }
        }

        test("loadShelf failure maps to the Error state") {
            runTest {
                val load: LoadShelfDetailUseCase =
                    mock { everySuspend { invoke(ShelfId("s1")) } returns AppResult.Failure(ShelfError.NotFound()) }
                val vm = viewModel(load = load)

                vm.loadShelf("s1")
                advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<ShelfDetailUiState.Error>()
            }
        }

        test("reorderBooks dispatches the new order and reloads") {
            runTest {
                val load: LoadShelfDetailUseCase =
                    mock { everySuspend { invoke(ShelfId("s1")) } returns AppResult.Success(detail(isOwner = true)) }
                val reorder: ReorderShelfBooksUseCase =
                    mock { everySuspend { invoke(any(), any()) } returns AppResult.Success(Unit) }
                val vm = viewModel(load = load, reorder = reorder)
                vm.loadShelf("s1")
                advanceUntilIdle()

                vm.reorderBooks(listOf("b2", "b1"))
                advanceUntilIdle()

                verifySuspend { reorder.invoke(ShelfId("s1"), listOf(BookId("b2"), BookId("b1"))) }
            }
        }

        test("reorderBooks is a no-op before a shelf is loaded") {
            runTest {
                val reorder: ReorderShelfBooksUseCase =
                    mock { everySuspend { invoke(any(), any()) } returns AppResult.Success(Unit) }
                val vm = viewModel(reorder = reorder)

                vm.reorderBooks(listOf("b1"))
                advanceUntilIdle()

                verifySuspend(dev.mokkery.verify.VerifyMode.not) { reorder.invoke(any(), any()) }
            }
        }
    })
