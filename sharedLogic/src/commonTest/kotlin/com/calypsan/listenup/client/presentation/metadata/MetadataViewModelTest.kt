package com.calypsan.listenup.client.presentation.metadata

import app.cash.turbine.test
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.domain.repository.CoverOption
import com.calypsan.listenup.client.domain.repository.MetadataBook
import com.calypsan.listenup.client.domain.repository.MetadataContributor
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.repository.MetadataSearchResult
import com.calypsan.listenup.client.domain.repository.MetadataSeriesEntry
import com.calypsan.listenup.client.domain.usecase.metadata.ApplyMetadataMatchUseCase
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import com.calypsan.listenup.core.error.ErrorBus

@OptIn(ExperimentalCoroutinesApi::class)
class MetadataViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private class TestFixture {
        val metadataRepository: MetadataRepository = mock()
        val applyMetadataMatchUseCase: ApplyMetadataMatchUseCase = mock()

        fun build(): MetadataViewModel =
            MetadataViewModel(
                metadataRepository = metadataRepository,
                applyMetadataMatchUseCase = applyMetadataMatchUseCase,
                errorBus = ErrorBus(),
            )
    }

    private fun createSearchResult(
        asin: String = "B001",
        title: String = "Book One",
    ): MetadataSearchResult =
        MetadataSearchResult(
            asin = asin,
            title = title,
            authors = listOf("Jane Doe"),
            narrators = listOf("John Narrator"),
            coverUrl = null,
            runtimeMinutes = 120,
            releaseDate = "2024-01-01",
            rating = 4.5,
            ratingCount = 10,
            language = "en",
        )

    private fun createPreview(
        asin: String = "B001",
        title: String = "Book One",
        authors: List<MetadataContributor> = listOf(MetadataContributor(name = "Jane Doe", asin = "A1")),
        narrators: List<MetadataContributor> = listOf(MetadataContributor(name = "John Narrator", asin = "N1")),
        series: List<MetadataSeriesEntry> = emptyList(),
        genres: List<String> = listOf("Fiction"),
        coverUrl: String? = "https://example.com/cover.jpg",
        description: String? = "A great book",
    ): MetadataBook =
        MetadataBook(
            asin = asin,
            title = title,
            subtitle = "A subtitle",
            authors = authors,
            narrators = narrators,
            series = series,
            genres = genres,
            coverUrl = coverUrl,
            description = description,
            publisher = "Pub",
            releaseDate = "2024-01-01",
            rating = 4.5,
            ratingCount = 10,
            language = "en",
            runtimeMinutes = 120,
        )

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle`() =
        runTest {
            val viewModel = TestFixture().build()
            val state = assertIs<MetadataUiState.Idle>(viewModel.state.value)
            assertEquals(AudibleRegion.US, state.region)
        }

    @Test
    fun `initForBook transitions to Search Idle with seeded query`() =
        runTest {
            val viewModel = TestFixture().build()

            viewModel.initForBook(bookId = "b-1", title = "Dune", author = "Frank Herbert")

            val state = assertIs<MetadataUiState.Search>(viewModel.state.value)
            assertEquals("b-1", state.context.bookId)
            assertEquals("Dune Frank Herbert", state.query)
            assertEquals(SearchLoadState.Idle, state.loadState)
        }

    @Test
    fun `search success transitions Search Idle to InFlight to Loaded`() =
        runTest {
            val fixture = TestFixture()
            val results = listOf(createSearchResult())
            everySuspend { fixture.metadataRepository.searchAudible(any(), any()) } returns results
            val viewModel = fixture.build()

            viewModel.initForBook(bookId = "b-1", title = "Dune", author = "Frank Herbert")
            viewModel.search()
            advanceUntilIdle()

            val state = assertIs<MetadataUiState.Search>(viewModel.state.value)
            val loaded = assertIs<SearchLoadState.Loaded>(state.loadState)
            assertEquals(1, loaded.results.size)
        }

    @Test
    fun `search failure transitions to SearchLoadState Failed`() =
        runTest {
            val fixture = TestFixture()
            everySuspend {
                fixture.metadataRepository.searchAudible(any(), any())
            } throws RuntimeException("network down")
            val viewModel = fixture.build()

            viewModel.initForBook(bookId = "b-1", title = "Dune", author = "")
            viewModel.search()
            advanceUntilIdle()

            val state = assertIs<MetadataUiState.Search>(viewModel.state.value)
            val failed = assertIs<SearchLoadState.Failed>(state.loadState)
            assertEquals("network down", failed.message)
        }

    @Test
    fun `selectMatch transitions to Preview Loading then Ready`() =
        runTest {
            val fixture = TestFixture()
            val match = createSearchResult(asin = "B001", title = "Dune")
            val preview = createPreview(asin = "B001", title = "Dune")
            everySuspend { fixture.metadataRepository.searchAudible(any(), any()) } returns listOf(match)
            everySuspend { fixture.metadataRepository.getMetadataPreview(any(), any()) } returns preview
            everySuspend { fixture.metadataRepository.searchCovers(any(), any()) } returns emptyList<CoverOption>()
            val viewModel = fixture.build()

            viewModel.initForBook(bookId = "b-1", title = "Dune", author = "Frank Herbert")
            viewModel.search()
            advanceUntilIdle()

            viewModel.selectMatch(match)
            advanceUntilIdle()

            val state = assertIs<MetadataUiState.Preview>(viewModel.state.value)
            val ready = assertIs<PreviewLoadState.Ready>(state.loadState)
            assertEquals("Dune", ready.preview.title)
            // Selections initialized with fields that have data.
            assertEquals(true, ready.selections.cover)
            assertEquals(true, ready.selections.title)
            assertEquals(setOf("A1"), ready.selections.selectedAuthors)
        }

    @Test
    fun `selectMatch preview fetch failure falls back to search result data`() =
        runTest {
            val fixture = TestFixture()
            val match = createSearchResult(asin = "B001", title = "Fallback Title")
            everySuspend { fixture.metadataRepository.searchAudible(any(), any()) } returns listOf(match)
            everySuspend {
                fixture.metadataRepository.getMetadataPreview(any(), any())
            } throws RuntimeException("audible down")
            everySuspend { fixture.metadataRepository.searchCovers(any(), any()) } returns emptyList<CoverOption>()
            val viewModel = fixture.build()

            viewModel.initForBook(bookId = "b-1", title = "Fallback Title", author = "")
            viewModel.search()
            advanceUntilIdle()

            viewModel.selectMatch(match)
            advanceUntilIdle()

            val state = assertIs<MetadataUiState.Preview>(viewModel.state.value)
            val ready = assertIs<PreviewLoadState.Ready>(state.loadState)
            assertEquals("Fallback Title", ready.preview.title)
        }

    @Test
    fun `selectMatch preview fetch failure with blank title emits Failed`() =
        runTest {
            val fixture = TestFixture()
            val match = MetadataSearchResult(asin = "B001", title = "")
            everySuspend {
                fixture.metadataRepository.getMetadataPreview(any(), any())
            } throws RuntimeException("audible down")
            everySuspend { fixture.metadataRepository.searchCovers(any(), any()) } returns emptyList<CoverOption>()
            val viewModel = fixture.build()

            viewModel.initForBook(bookId = "b-1", title = "", author = "")
            viewModel.selectMatch(match)
            advanceUntilIdle()

            val state = assertIs<MetadataUiState.Preview>(viewModel.state.value)
            val failed = assertIs<PreviewLoadState.Failed>(state.loadState)
            assertEquals("audible down", failed.message)
        }

    @Test
    fun `clearSelection returns to Search with results preserved`() =
        runTest {
            val fixture = TestFixture()
            val match = createSearchResult()
            val preview = createPreview()
            everySuspend { fixture.metadataRepository.searchAudible(any(), any()) } returns listOf(match)
            everySuspend { fixture.metadataRepository.getMetadataPreview(any(), any()) } returns preview
            everySuspend { fixture.metadataRepository.searchCovers(any(), any()) } returns emptyList<CoverOption>()
            val viewModel = fixture.build()

            viewModel.initForBook(bookId = "b-1", title = "Dune", author = "FH")
            viewModel.search()
            advanceUntilIdle()
            viewModel.selectMatch(match)
            advanceUntilIdle()

            viewModel.clearSelection()

            val state = assertIs<MetadataUiState.Search>(viewModel.state.value)
            val loaded = assertIs<SearchLoadState.Loaded>(state.loadState)
            assertEquals(1, loaded.results.size)
        }

    @Test
    fun `toggleField flips the corresponding selection on Ready`() =
        runTest {
            val fixture = TestFixture()
            val match = createSearchResult()
            val preview = createPreview()
            everySuspend { fixture.metadataRepository.getMetadataPreview(any(), any()) } returns preview
            everySuspend { fixture.metadataRepository.searchCovers(any(), any()) } returns emptyList<CoverOption>()
            val viewModel = fixture.build()

            viewModel.initForBook(bookId = "b-1", title = "Dune", author = "FH")
            viewModel.selectMatch(match)
            advanceUntilIdle()

            viewModel.toggleField(MetadataField.TITLE)

            val state = assertIs<MetadataUiState.Preview>(viewModel.state.value)
            val ready = assertIs<PreviewLoadState.Ready>(state.loadState)
            // Was initially true (title has data); toggle flips it.
            assertEquals(false, ready.selections.title)
        }

    @Test
    fun `toggleAuthor adds and removes ASIN from selected set`() =
        runTest {
            val fixture = TestFixture()
            val match = createSearchResult()
            val preview = createPreview()
            everySuspend { fixture.metadataRepository.getMetadataPreview(any(), any()) } returns preview
            everySuspend { fixture.metadataRepository.searchCovers(any(), any()) } returns emptyList<CoverOption>()
            val viewModel = fixture.build()

            viewModel.initForBook(bookId = "b-1", title = "Dune", author = "FH")
            viewModel.selectMatch(match)
            advanceUntilIdle()

            viewModel.toggleAuthor("A1") // Remove (was initialized with A1)
            val afterRemove =
                assertIs<PreviewLoadState.Ready>(
                    (viewModel.state.value as MetadataUiState.Preview).loadState,
                )
            assertEquals(emptySet(), afterRemove.selections.selectedAuthors)

            viewModel.toggleAuthor("A2") // Add
            val afterAdd =
                assertIs<PreviewLoadState.Ready>(
                    (viewModel.state.value as MetadataUiState.Preview).loadState,
                )
            assertEquals(setOf("A2"), afterAdd.selections.selectedAuthors)
        }

    @Test
    fun `applyMatch success emits MatchApplied and clears applyError`() =
        runTest {
            val fixture = TestFixture()
            val match = createSearchResult()
            val preview = createPreview()
            everySuspend { fixture.metadataRepository.getMetadataPreview(any(), any()) } returns preview
            everySuspend { fixture.metadataRepository.searchCovers(any(), any()) } returns emptyList<CoverOption>()
            everySuspend {
                fixture.applyMetadataMatchUseCase(any(), any(), any(), any(), any(), any())
            } returns Success(Unit)
            val viewModel = fixture.build()

            viewModel.initForBook(bookId = "b-1", title = "Dune", author = "FH")
            viewModel.selectMatch(match)
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.applyMatch()
                advanceUntilIdle()
                assertEquals(MetadataEvent.MatchApplied, awaitItem())
            }

            val ready =
                assertIs<PreviewLoadState.Ready>(
                    (viewModel.state.value as MetadataUiState.Preview).loadState,
                )
            assertEquals(false, ready.isApplying)
            assertEquals(null, ready.applyError)
        }

    @Test
    fun `applyMatch failure sets applyError and stays in Ready`() =
        runTest {
            val fixture = TestFixture()
            val match = createSearchResult()
            val preview = createPreview()
            everySuspend { fixture.metadataRepository.getMetadataPreview(any(), any()) } returns preview
            everySuspend { fixture.metadataRepository.searchCovers(any(), any()) } returns emptyList<CoverOption>()
            everySuspend {
                fixture.applyMetadataMatchUseCase(any(), any(), any(), any(), any(), any())
            } returns AppResult.Failure(ValidationError(message = "server down"))
            val viewModel = fixture.build()

            viewModel.initForBook(bookId = "b-1", title = "Dune", author = "FH")
            viewModel.selectMatch(match)
            advanceUntilIdle()

            viewModel.applyMatch()
            advanceUntilIdle()

            val ready =
                assertIs<PreviewLoadState.Ready>(
                    (viewModel.state.value as MetadataUiState.Preview).loadState,
                )
            assertEquals(false, ready.isApplying)
            assertNotNull(ready.applyError)
            assertEquals("server down", ready.applyError)
        }

    @Test
    fun `changeRegion in Preview phase refetches with new region code`() =
        runTest {
            val fixture = TestFixture()
            val match = createSearchResult()
            val previewUs = createPreview(title = "US Edition")
            val previewUk = createPreview(title = "UK Edition")
            // First call (US region) and second call (UK) via the repeated mock.
            everySuspend {
                fixture.metadataRepository.getMetadataPreview(any(), "us")
            } returns previewUs
            everySuspend {
                fixture.metadataRepository.getMetadataPreview(any(), "uk")
            } returns previewUk
            everySuspend { fixture.metadataRepository.searchCovers(any(), any()) } returns emptyList<CoverOption>()
            val viewModel = fixture.build()

            viewModel.initForBook(bookId = "b-1", title = "Dune", author = "FH")
            viewModel.selectMatch(match)
            advanceUntilIdle()
            val usState = viewModel.state.value as MetadataUiState.Preview
            assertEquals("US Edition", (usState.loadState as PreviewLoadState.Ready).preview.title)

            viewModel.changeRegion(AudibleRegion.UK)
            advanceUntilIdle()

            val ukState = assertIs<MetadataUiState.Preview>(viewModel.state.value)
            assertEquals(AudibleRegion.UK, ukState.region)
            val ready = assertIs<PreviewLoadState.Ready>(ukState.loadState)
            assertEquals("UK Edition", ready.preview.title)
        }

    @Test
    fun `reset returns to Idle preserving region`() =
        runTest {
            val fixture = TestFixture()
            everySuspend { fixture.metadataRepository.searchAudible(any(), any()) } returns emptyList()
            val viewModel = fixture.build()

            viewModel.initForBook(bookId = "b-1", title = "Dune", author = "FH")
            viewModel.changeRegion(AudibleRegion.DE)
            viewModel.reset()

            val state = assertIs<MetadataUiState.Idle>(viewModel.state.value)
            assertEquals(AudibleRegion.DE, state.region)
        }
}
