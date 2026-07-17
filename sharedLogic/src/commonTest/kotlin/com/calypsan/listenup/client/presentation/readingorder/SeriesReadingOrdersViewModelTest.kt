package com.calypsan.listenup.client.presentation.readingorder

import app.cash.turbine.test
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ReadingOrder
import com.calypsan.listenup.client.test.fake.FakeReadingOrderRepository
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [SeriesReadingOrdersViewModel]. Pins the owned/discovered split (discovery
 * excludes anything already owned), the per-series active chip, the passive (no-ErrorBus)
 * discovery-failure branch with [SeriesReadingOrdersViewModel.retryDiscover] recovery, the
 * [SeriesReadingOrdersViewModel.setActive] failure path, and
 * [SeriesReadingOrdersViewModel.createOrder]'s success/event/in-flight-guard behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesReadingOrdersViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun order(
            id: String,
            name: String = "Order $id",
            ownerId: String = "u1",
        ) = ReadingOrder(
            id = ReadingOrderId(id),
            name = name,
            description = null,
            attribution = "",
            isPrivate = false,
            ownerId = ownerId,
            ownerDisplayName = "Owner",
            bookCount = 0,
            totalDurationSeconds = 0,
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )

        class Fixture {
            val repository = FakeReadingOrderRepository()
            val errorBus = ErrorBus()

            fun build() = SeriesReadingOrdersViewModel(repository, errorBus)
        }

        test("owned rows carry isActive for the loaded series") {
            runTest {
                val fixture = Fixture()
                fixture.repository.setMyOrders(listOf(order("ro1"), order("ro2")))
                fixture.repository.setActiveOrder("series-1", ReadingOrderId("ro2"))
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe SeriesReadingOrdersUiState.Idle
                    viewModel.load("series-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe SeriesReadingOrdersUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<SeriesReadingOrdersUiState.Ready>()
                    ready.owned.associate { it.order.id.value to it.isActive } shouldBe
                        mapOf("ro1" to false, "ro2" to true)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("discovered excludes orders already owned") {
            runTest {
                val fixture = Fixture()
                fixture.repository.setMyOrders(listOf(order("ro1")))
                fixture.repository.setDiscoverableOrders(listOf(order("ro1", ownerId = "u1"), order("ro-other", ownerId = "u2")))
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe SeriesReadingOrdersUiState.Idle
                    viewModel.load("series-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe SeriesReadingOrdersUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<SeriesReadingOrdersUiState.Ready>()
                    ready.discovered.map { it.order.id.value } shouldContainExactly listOf("ro-other")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a discovery failure sets discoverFailed with an empty list and never touches errorBus") {
            runTest {
                val fixture = Fixture()
                fixture.repository.discoverReadingOrdersResult = AppResult.Failure(ReadingOrderError.NotFound())
                val viewModel = fixture.build()

                val emitted = mutableListOf<AppError>()
                backgroundScope.launch { fixture.errorBus.errors.collect { emitted += it } }
                advanceUntilIdle()

                viewModel.state.test {
                    awaitItem() shouldBe SeriesReadingOrdersUiState.Idle
                    viewModel.load("series-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe SeriesReadingOrdersUiState.Loading

                    val ready = awaitItem().shouldBeInstanceOf<SeriesReadingOrdersUiState.Ready>()
                    ready.discoverFailed shouldBe true
                    ready.discovered shouldBe emptyList()
                    cancelAndIgnoreRemainingEvents()
                }
                emitted shouldBe emptyList()
            }
        }

        test("retryDiscover recovers from a prior discovery failure") {
            runTest {
                val fixture = Fixture()
                fixture.repository.discoverReadingOrdersResult = AppResult.Failure(ReadingOrderError.NotFound())
                val viewModel = fixture.build()

                viewModel.state.test {
                    awaitItem() shouldBe SeriesReadingOrdersUiState.Idle
                    viewModel.load("series-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe SeriesReadingOrdersUiState.Loading
                    awaitItem().shouldBeInstanceOf<SeriesReadingOrdersUiState.Ready>().discoverFailed shouldBe true

                    fixture.repository.discoverReadingOrdersResult = null
                    fixture.repository.setDiscoverableOrders(listOf(order("ro-found")))
                    viewModel.retryDiscover()
                    advanceUntilIdle()

                    val recovered = awaitItem().shouldBeInstanceOf<SeriesReadingOrdersUiState.Ready>()
                    recovered.discoverFailed shouldBe false
                    recovered.discovered.map { it.order.id.value } shouldContainExactly listOf("ro-found")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("setActive failure emits typed AppError to errorBus") {
            runTest {
                val fixture = Fixture()
                fixture.repository.setActiveReadingOrderResult = AppResult.Failure(ReadingOrderError.Forbidden())
                val viewModel = fixture.build()
                viewModel.load("series-1")
                advanceUntilIdle()

                fixture.errorBus.errors.test {
                    viewModel.setActive(ReadingOrderId("ro1"))
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<ReadingOrderError.Forbidden>()
                }
            }
        }

        test("createOrder success emits Created and records the optional setActive follow-up") {
            runTest {
                val fixture = Fixture()
                val viewModel = fixture.build()
                viewModel.load("series-1")
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.createOrder(name = "Chronological", attribution = "Fan wiki", isPrivate = false, setActive = true)
                    advanceUntilIdle()

                    val created = awaitItem().shouldBeInstanceOf<SeriesReadingOrdersEvent.Created>()
                    fixture.repository.createReadingOrderCalls shouldContainExactly
                        listOf(
                            FakeReadingOrderRepository.CreateReadingOrderCall(
                                name = "Chronological",
                                description = null,
                                attribution = "Fan wiki",
                                isPrivate = false,
                            ),
                        )
                    fixture.repository.setActiveReadingOrderCalls shouldContainExactly
                        listOf(FakeReadingOrderRepository.SetActiveCall("series-1", ReadingOrderId(created.orderId)))
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("createOrder ignores a second call while one is already in flight") {
            runTest {
                val fixture = Fixture()
                val viewModel = fixture.build()
                viewModel.load("series-1")

                viewModel.createOrder(name = "First", attribution = null, isPrivate = false, setActive = false)
                viewModel.createOrder(name = "Second", attribution = null, isPrivate = false, setActive = false)
                advanceUntilIdle()

                fixture.repository.createReadingOrderCalls.map { it.name } shouldContainExactly listOf("First")
            }
        }
    })
