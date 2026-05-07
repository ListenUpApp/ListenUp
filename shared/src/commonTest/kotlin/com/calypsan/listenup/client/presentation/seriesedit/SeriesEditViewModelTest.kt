package com.calypsan.listenup.client.presentation.seriesedit

import app.cash.turbine.test
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.usecase.series.UpdateSeriesUseCase
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesEditViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixture ==========

    private class TestFixture {
        val seriesRepository: SeriesRepository = mock()
        val updateSeriesUseCase: UpdateSeriesUseCase = mock()
        val imageRepository: ImageRepository = mock()
        val imageStagingRepository: ImageStagingRepository = mock()

        fun build(): SeriesEditViewModel =
            SeriesEditViewModel(
                seriesRepository = seriesRepository,
                updateSeriesUseCase = updateSeriesUseCase,
                imageRepository = imageRepository,
                imageStagingRepository = imageStagingRepository,
            )
    }

    private fun createFixture(): TestFixture = TestFixture()

    // ========== Test Data Factories ==========

    private fun createSeries(
        id: String = "series-1",
        name: String = "Test Series",
        description: String? = "A test series",
    ) = Series(
        id =
            com.calypsan.listenup.client.core
                .SeriesId(id),
        name = name,
        description = description,
    )

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Loading Tests ==========

    @Test
    fun `initial state is loading`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()

            assertTrue(viewModel.state.value.isLoading)
        }

    @Test
    fun `loadSeries populates state with series data`() =
        runTest {
            val fixture = createFixture()
            val series = createSeries()
            everySuspend { fixture.seriesRepository.getById("series-1") } returns series
            everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
            everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false

            val viewModel = fixture.build()
            viewModel.loadSeries("series-1")
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isLoading)
            assertEquals("Test Series", viewModel.state.value.name)
            assertEquals("A test series", viewModel.state.value.description)
            assertEquals(1, viewModel.state.value.bookCount)
        }

    @Test
    fun `loadSeries shows error for missing series`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.seriesRepository.getById("missing") } returns null

            val viewModel = fixture.build()
            viewModel.loadSeries("missing")
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isLoading)
            assertEquals("Series not found", viewModel.state.value.error)
        }

    // ========== Change Tracking Tests ==========

    @Test
    fun `NameChanged updates state and tracks changes`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.seriesRepository.getById("series-1") } returns createSeries()
            everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
            everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false

            val viewModel = fixture.build()
            viewModel.loadSeries("series-1")
            advanceUntilIdle()
            assertFalse(viewModel.state.value.hasChanges)

            viewModel.onEvent(SeriesEditUiEvent.NameChanged("New Name"))

            assertEquals("New Name", viewModel.state.value.name)
            assertTrue(viewModel.state.value.hasChanges)
        }

    @Test
    fun `DescriptionChanged updates state and tracks changes`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.seriesRepository.getById("series-1") } returns createSeries()
            everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
            everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false

            val viewModel = fixture.build()
            viewModel.loadSeries("series-1")
            advanceUntilIdle()

            viewModel.onEvent(SeriesEditUiEvent.DescriptionChanged("New description"))

            assertEquals("New description", viewModel.state.value.description)
            assertTrue(viewModel.state.value.hasChanges)
        }

    // ========== Save Tests ==========

    @Test
    fun `SaveClicked with no changes navigates back immediately`() =
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
                assertEquals(SeriesEditNavAction.NavigateBack, awaitItem())
            }
        }

    @Test
    fun `SaveClicked with changes calls use case`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.seriesRepository.getById("series-1") } returns createSeries()
            everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
            everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false
            everySuspend { fixture.updateSeriesUseCase.invoke(any()) } returns Success(Unit)

            val viewModel = fixture.build()
            viewModel.loadSeries("series-1")
            advanceUntilIdle()
            viewModel.onEvent(SeriesEditUiEvent.NameChanged("Updated Name"))

            viewModel.navActions.test {
                viewModel.onEvent(SeriesEditUiEvent.SaveClicked)
                advanceUntilIdle()
                verifySuspend { fixture.updateSeriesUseCase.invoke(any()) }
                assertEquals(SeriesEditNavAction.NavigateBack, awaitItem())
            }
        }

    @Test
    fun `SaveClicked shows error when use case fails`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.seriesRepository.getById("series-1") } returns createSeries()
            everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
            everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation to the ViewModel.
            everySuspend { fixture.updateSeriesUseCase.invoke(any()) } returns
                Failure(com.calypsan.listenup.api.error.ValidationError(message = "Save failed"))

            val viewModel = fixture.build()
            viewModel.loadSeries("series-1")
            advanceUntilIdle()
            viewModel.onEvent(SeriesEditUiEvent.NameChanged("Updated Name"))

            viewModel.onEvent(SeriesEditUiEvent.SaveClicked)
            advanceUntilIdle()

            assertEquals("Failed to save: Save failed", viewModel.state.value.error)
            viewModel.navActions.test {
                expectNoEvents()
            }
        }

    // ========== Cancel Tests ==========

    @Test
    fun `CancelClicked navigates back`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.seriesRepository.getById("series-1") } returns createSeries()
            everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns listOf("book-1")
            everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false
            everySuspend { fixture.imageStagingRepository.deleteSeriesCoverStaging(any()) } returns Success(Unit)

            val viewModel = fixture.build()
            viewModel.loadSeries("series-1")
            advanceUntilIdle()

            viewModel.navActions.test {
                viewModel.onEvent(SeriesEditUiEvent.CancelClicked)
                advanceUntilIdle()
                assertEquals(SeriesEditNavAction.NavigateBack, awaitItem())
            }
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `ErrorDismissed clears error`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.seriesRepository.getById("missing") } returns null

            val viewModel = fixture.build()
            viewModel.loadSeries("missing")
            advanceUntilIdle()
            assertTrue(viewModel.state.value.error != null)

            viewModel.onEvent(SeriesEditUiEvent.ErrorDismissed)

            assertNull(viewModel.state.value.error)
        }

    // ========== Staging cleanup on clear ==========

    @Test
    fun `onCleared requests staging cleanup when staging cover is present`() =
        runTest {
            val fixture = createFixture()
            val series = createSeries(id = "series-1")
            everySuspend { fixture.seriesRepository.getById("series-1") } returns series
            everySuspend { fixture.seriesRepository.getBookIdsForSeries("series-1") } returns emptyList()
            everySuspend { fixture.imageRepository.seriesCoverExists("series-1") } returns false
            everySuspend { fixture.imageStagingRepository.saveSeriesCoverStaging("series-1", any()) } returns Success(Unit)
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

    @Test
    fun `onCleared does not request cleanup when no staging cover exists`() =
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

private fun androidx.lifecycle.ViewModel.invokeOnCleared() {
    this.javaClass.superclass
        .getDeclaredMethod("onCleared")
        .apply { isAccessible = true }
        .invoke(this)
}
