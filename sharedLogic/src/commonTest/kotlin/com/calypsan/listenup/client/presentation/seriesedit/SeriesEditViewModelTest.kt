package com.calypsan.listenup.client.presentation.seriesedit

import com.calypsan.listenup.api.result.AppResult
import app.cash.turbine.test
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.usecase.series.UpdateSeriesUseCase
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesEditViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixture ==========

        class TestFixture {
            val seriesRepository: SeriesRepository = mock()
            val updateSeriesUseCase: UpdateSeriesUseCase = mock()
            val imageRepository: ImageRepository = mock()
            val imageStagingRepository: ImageStagingRepository = mock()
            val seriesEditRepository: SeriesEditRepository = mock()
            val seriesDao: SeriesDao =
                mock {
                    every { observeAll() } returns flowOf(emptyList())
                }
            val errorBus: ErrorBus = ErrorBus()

            fun build(): SeriesEditViewModel =
                SeriesEditViewModel(
                    seriesRepository = seriesRepository,
                    updateSeriesUseCase = updateSeriesUseCase,
                    imageRepository = imageRepository,
                    imageStagingRepository = imageStagingRepository,
                    seriesEditRepository = seriesEditRepository,
                    seriesDao = seriesDao,
                    errorBus = errorBus,
                )
        }

        fun createFixture(): TestFixture = TestFixture()

        // ========== Test Data Factories ==========

        fun createSeries(
            id: String = "series-1",
            name: String = "Test Series",
            description: String? = "A test series",
        ) = Series(
            id =
                com.calypsan.listenup.core
                    .SeriesId(id),
            name = name,
            description = description,
        )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Loading Tests ==========

        test("initial state is loading") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()

                (viewModel.state.value.isLoading) shouldBe true
            }
        }

        test("loadSeries populates state with series data") {
            runTest {
                val fixture = createFixture()
                val series = createSeries()
                everySuspend { fixture.seriesRepository.getById("series-1") } returns series
                everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
                everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false

                val viewModel = fixture.build()
                viewModel.loadSeries("series-1")
                advanceUntilIdle()

                (viewModel.state.value.isLoading) shouldBe false
                viewModel.state.value.name shouldBe "Test Series"
                viewModel.state.value.description shouldBe "A test series"
                viewModel.state.value.bookCount shouldBe 1
            }
        }

        test("loadSeries shows error for missing series") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.seriesRepository.getById("missing") } returns null

                val viewModel = fixture.build()
                viewModel.loadSeries("missing")
                advanceUntilIdle()

                (viewModel.state.value.isLoading) shouldBe false
                viewModel.state.value.error shouldBe "Series not found"
            }
        }

        // ========== Change Tracking Tests ==========

        test("NameChanged updates state and tracks changes") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.seriesRepository.getById("series-1") } returns createSeries()
                everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
                everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false

                val viewModel = fixture.build()
                viewModel.loadSeries("series-1")
                advanceUntilIdle()
                (viewModel.state.value.hasChanges) shouldBe false

                viewModel.onEvent(SeriesEditUiEvent.NameChanged("New Name"))

                viewModel.state.value.name shouldBe "New Name"
                (viewModel.state.value.hasChanges) shouldBe true
            }
        }

        test("DescriptionChanged updates state and tracks changes") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.seriesRepository.getById("series-1") } returns createSeries()
                everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
                everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false

                val viewModel = fixture.build()
                viewModel.loadSeries("series-1")
                advanceUntilIdle()

                viewModel.onEvent(SeriesEditUiEvent.DescriptionChanged("New description"))

                viewModel.state.value.description shouldBe "New description"
                (viewModel.state.value.hasChanges) shouldBe true
            }
        }

        // ========== Save Tests ==========

        test("SaveClicked with no changes navigates back immediately") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.seriesRepository.getById("series-1") } returns createSeries()
                everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
                everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false

                val viewModel = fixture.build()
                viewModel.loadSeries("series-1")
                advanceUntilIdle()

                viewModel.navActions.test {
                    viewModel.onEvent(SeriesEditUiEvent.SaveClicked)
                    advanceUntilIdle()
                    awaitItem() shouldBe SeriesEditNavAction.NavigateBack
                }
            }
        }

        test("SaveClicked with changes calls use case") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.seriesRepository.getById("series-1") } returns createSeries()
                everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
                everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false
                everySuspend { fixture.updateSeriesUseCase.invoke(any()) } returns AppResult.Success(Unit)

                val viewModel = fixture.build()
                viewModel.loadSeries("series-1")
                advanceUntilIdle()
                viewModel.onEvent(SeriesEditUiEvent.NameChanged("Updated Name"))

                viewModel.navActions.test {
                    viewModel.onEvent(SeriesEditUiEvent.SaveClicked)
                    advanceUntilIdle()
                    verifySuspend { fixture.updateSeriesUseCase.invoke(any()) }
                    awaitItem() shouldBe SeriesEditNavAction.NavigateBack
                }
            }
        }

        test("SaveClicked shows error when use case fails") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.seriesRepository.getById("series-1") } returns createSeries()
                everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
                everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false
                // Body-level message convention: pass a typed AppError so the
                // user-facing message survives delegation to the ViewModel.
                everySuspend { fixture.updateSeriesUseCase.invoke(any()) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Save failed"),
                    )

                val viewModel = fixture.build()
                viewModel.loadSeries("series-1")
                advanceUntilIdle()
                viewModel.onEvent(SeriesEditUiEvent.NameChanged("Updated Name"))

                viewModel.onEvent(SeriesEditUiEvent.SaveClicked)
                advanceUntilIdle()

                viewModel.state.value.error shouldBe "Failed to save: Save failed"
                viewModel.navActions.test {
                    expectNoEvents()
                }
            }
        }

        test("SaveClicked failure emits typed AppError to ErrorBus for global snackbar") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.seriesRepository.getById("series-1") } returns createSeries()
                everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
                everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false
                everySuspend { fixture.updateSeriesUseCase.invoke(any()) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Save failed"),
                    )

                val viewModel = fixture.build()
                viewModel.loadSeries("series-1")
                advanceUntilIdle()
                viewModel.onEvent(SeriesEditUiEvent.NameChanged("Updated Name"))

                fixture.errorBus.errors.test {
                    viewModel.onEvent(SeriesEditUiEvent.SaveClicked)
                    advanceUntilIdle()
                    awaitItem().message shouldBe "Save failed"
                }
            }
        }

        // ========== Cancel Tests ==========

        test("CancelClicked navigates back") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.seriesRepository.getById("series-1") } returns createSeries()
                everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
                everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false

                val viewModel = fixture.build()
                viewModel.loadSeries("series-1")
                advanceUntilIdle()

                viewModel.navActions.test {
                    viewModel.onEvent(SeriesEditUiEvent.CancelClicked)
                    advanceUntilIdle()
                    awaitItem() shouldBe SeriesEditNavAction.NavigateBack
                }
            }
        }

        // ========== Error Handling Tests ==========

        test("ErrorDismissed clears error") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.seriesRepository.getById("missing") } returns null

                val viewModel = fixture.build()
                viewModel.loadSeries("missing")
                advanceUntilIdle()
                (viewModel.state.value.error != null) shouldBe true

                viewModel.onEvent(SeriesEditUiEvent.ErrorDismissed)

                viewModel.state.value.error shouldBe null
            }
        }

        // ========== Staging cleanup on clear ==========

        test("onCleared requests staging cleanup when staging cover is present") {
            runTest {
                val fixture = createFixture()
                val series = createSeries(id = "series-1")
                everySuspend { fixture.seriesRepository.getById("series-1") } returns series
                everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns emptyList()
                everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false
                everySuspend { fixture.imageStagingRepository.saveSeriesCoverStaging("series-1", any()) } returns AppResult.Success(Unit)
                every { fixture.imageStagingRepository.getSeriesCoverStagingPath("series-1") } returns "/tmp/staging-series-1.jpg"
                every { fixture.imageStagingRepository.requestSeriesCoverStagingCleanup(any()) } returns Unit

                val vm = fixture.build()
                vm.loadSeries("series-1")
                advanceUntilIdle()
                vm.onEvent(SeriesEditUiEvent.CoverSelected(byteArrayOf(1, 2, 3), "cover.jpg"))
                advanceUntilIdle()

                vm.invokeOnCleared()

                verify { fixture.imageStagingRepository.requestSeriesCoverStagingCleanup("series-1") }
            }
        }

        test("onCleared does not request cleanup when no staging cover exists") {
            runTest {
                val fixture = createFixture()
                val series = createSeries(id = "series-1")
                everySuspend { fixture.seriesRepository.getById("series-1") } returns series
                everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns emptyList()
                everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false

                val vm = fixture.build()
                vm.loadSeries("series-1")
                advanceUntilIdle()

                vm.invokeOnCleared()

                verify(mode = VerifyMode.not) {
                    fixture.imageStagingRepository.requestSeriesCoverStagingCleanup(any())
                }
            }
        }

        test("CancelClicked requests staging cleanup when a staging cover is present") {
            runTest {
                val fixture = createFixture()
                val series = createSeries(id = "series-1")
                everySuspend { fixture.seriesRepository.getById("series-1") } returns series
                everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns emptyList()
                everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false
                everySuspend { fixture.imageStagingRepository.saveSeriesCoverStaging("series-1", any()) } returns AppResult.Success(Unit)
                every { fixture.imageStagingRepository.getSeriesCoverStagingPath("series-1") } returns "/tmp/staging-series-1.jpg"
                every { fixture.imageStagingRepository.requestSeriesCoverStagingCleanup(any()) } returns Unit

                val vm = fixture.build()
                vm.loadSeries("series-1")
                advanceUntilIdle()
                vm.onEvent(SeriesEditUiEvent.CoverSelected(byteArrayOf(1, 2, 3), "cover.jpg"))
                advanceUntilIdle()

                vm.onEvent(SeriesEditUiEvent.CancelClicked)
                advanceUntilIdle()

                verify { fixture.imageStagingRepository.requestSeriesCoverStagingCleanup("series-1") }
            }
        }

        test("CoverRemoved requests staging cleanup when a staging cover is present") {
            runTest {
                val fixture = createFixture()
                val series = createSeries(id = "series-1")
                everySuspend { fixture.seriesRepository.getById("series-1") } returns series
                everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns emptyList()
                everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false
                everySuspend { fixture.imageStagingRepository.saveSeriesCoverStaging("series-1", any()) } returns AppResult.Success(Unit)
                every { fixture.imageStagingRepository.getSeriesCoverStagingPath("series-1") } returns "/tmp/staging-series-1.jpg"
                every { fixture.imageStagingRepository.requestSeriesCoverStagingCleanup(any()) } returns Unit

                val vm = fixture.build()
                vm.loadSeries("series-1")
                advanceUntilIdle()
                vm.onEvent(SeriesEditUiEvent.CoverSelected(byteArrayOf(1, 2, 3), "cover.jpg"))
                advanceUntilIdle()

                vm.onEvent(SeriesEditUiEvent.CoverRemoved)
                advanceUntilIdle()

                verify { fixture.imageStagingRepository.requestSeriesCoverStagingCleanup("series-1") }
            }
        }
    })

private fun androidx.lifecycle.ViewModel.invokeOnCleared() {
    this.javaClass.superclass
        .getDeclaredMethod("onCleared")
        .apply { isAccessible = true }
        .invoke(this)
}
