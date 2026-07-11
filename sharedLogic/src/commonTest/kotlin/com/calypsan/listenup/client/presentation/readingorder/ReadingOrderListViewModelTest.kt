package com.calypsan.listenup.client.presentation.readingorder

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.model.ReadingOrder
import com.calypsan.listenup.client.domain.repository.ReadingOrderRepository
import com.calypsan.listenup.core.ReadingOrderId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [ReadingOrderListViewModel]. Pins the sealed [ReadingOrderListUiState]
 * mapping over [ReadingOrderRepository.observeMyReadingOrders]: Loading first, an
 * empty list maps to [ReadingOrderListUiState.Empty], and a non-empty list maps to
 * [ReadingOrderListUiState.Loaded] preserving order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingOrderListViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun order(
            id: String,
            name: String,
        ) = ReadingOrder(
            id = ReadingOrderId(id),
            name = name,
            description = null,
            attribution = "",
            isPrivate = false,
            ownerId = "u1",
            ownerDisplayName = "Me",
            bookCount = 0,
            totalDurationSeconds = 0,
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )

        fun repoEmitting(orders: List<ReadingOrder>): ReadingOrderRepository =
            mock<ReadingOrderRepository> {
                every { observeMyReadingOrders() } returns flowOf(orders)
            }

        test("initial state is Loading") {
            runTest {
                val viewModel = ReadingOrderListViewModel(repoEmitting(emptyList()))

                viewModel.state.test {
                    awaitItem() shouldBe ReadingOrderListUiState.Loading
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("an empty mirror maps to Empty") {
            runTest {
                val viewModel = ReadingOrderListViewModel(repoEmitting(emptyList()))

                viewModel.state.test {
                    awaitItem() shouldBe ReadingOrderListUiState.Loading
                    awaitItem() shouldBe ReadingOrderListUiState.Empty
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("orders map to Loaded preserving order") {
            runTest {
                val orders = listOf(order("ro2", "Newest"), order("ro1", "Older"))
                val viewModel = ReadingOrderListViewModel(repoEmitting(orders))

                viewModel.state.test {
                    awaitItem() shouldBe ReadingOrderListUiState.Loading
                    awaitItem() shouldBe ReadingOrderListUiState.Loaded(orders)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
