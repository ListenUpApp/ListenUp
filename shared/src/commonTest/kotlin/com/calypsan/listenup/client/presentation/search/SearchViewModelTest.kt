package com.calypsan.listenup.client.presentation.search

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.domain.repository.SearchRepository
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import com.calypsan.listenup.core.error.ErrorBus

/**
 * Tests for [SearchViewModel].
 *
 * Every test calls [keepStateHot] because `state` uses `stateIn(WhileSubscribed)` —
 * without an active collector the upstream pipeline is torn down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private class TestFixture {
        val searchRepository: SearchRepository = mock()

        fun build(): SearchViewModel = SearchViewModel(searchRepository = searchRepository, errorBus = ErrorBus())
    }

    private fun TestScope.createFixture(): TestFixture = TestFixture()

    private fun TestScope.keepStateHot(viewModel: SearchViewModel) {
        backgroundScope.launch { viewModel.state.collect { } }
    }

    private fun createSearchResult(
        query: String = "test",
        hits: List<SearchHit> = emptyList(),
        total: Int = hits.size,
    ): SearchResult =
        SearchResult(
            query = query,
            total = total,
            tookMs = 10L,
            hits = hits,
        )

    private fun createBookHit(
        id: String = "book-1",
        name: String = "Test Book",
    ): SearchHit =
        SearchHit(
            id = id,
            type = SearchHitType.BOOK,
            name = name,
        )

    private fun createContributorHit(
        id: String = "contributor-1",
        name: String = "Test Author",
    ): SearchHit =
        SearchHit(
            id = id,
            type = SearchHitType.CONTRIBUTOR,
            name = name,
        )

    private fun createSeriesHit(
        id: String = "series-1",
        name: String = "Test Series",
    ): SearchHit =
        SearchHit(
            id = id,
            type = SearchHitType.SERIES,
            name = name,
        )

    private fun createTagHit(
        id: String = "tag-1",
        name: String = "Test Tag",
    ): SearchHit =
        SearchHit(
            id = id,
            type = SearchHitType.TAG,
            name = name,
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
    fun `initial state is Idle with empty query and no type filters`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            val state = assertIs<SearchUiState.Idle>(viewModel.state.value)
            assertEquals("", state.query)
            assertTrue(state.selectedTypes.isEmpty())
        }

    @Test
    fun `onQueryChanged reflects new query in state immediately`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.onQueryChanged("hello")
            advanceUntilIdle()

            assertEquals("hello", viewModel.state.value.query)
        }

    @Test
    fun `search triggers after debounce for query with min 2 chars`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                createSearchResult(
                    query = "test",
                    hits = listOf(createBookHit()),
                )
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.onQueryChanged("te")

            // Before debounce fires: phase still Idle (search hasn't started).
            // Don't call advanceUntilIdle here — it would advance through the 300ms debounce.
            advanceTimeBy(200.milliseconds)
            assertIs<SearchUiState.Idle>(viewModel.state.value)

            advanceTimeBy(150.milliseconds)
            advanceUntilIdle()

            val results = assertIs<SearchUiState.Results>(viewModel.state.value)
            assertEquals(1, results.result.hits.size)
            verifySuspend {
                fixture.searchRepository.search(
                    query = "te",
                    types = null,
                    genres = null,
                    genrePath = null,
                    limit = 30,
                )
            }
        }

    @Test
    fun `search does not trigger for single character query`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.onQueryChanged("a")
            advanceTimeBy(500.milliseconds)
            advanceUntilIdle()

            // Phase stays Idle; search never executes.
            assertIs<SearchUiState.Idle>(viewModel.state.value)
            assertEquals("a", viewModel.state.value.query)
        }

    @Test
    fun `blank query returns phase to Idle after prior results`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                createSearchResult(hits = listOf(createBookHit()))
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.onQueryChanged("test")
            advanceTimeBy(400.milliseconds)
            advanceUntilIdle()
            assertIs<SearchUiState.Results>(viewModel.state.value)

            viewModel.onQueryChanged("")
            advanceTimeBy(100.milliseconds)
            advanceUntilIdle()

            assertIs<SearchUiState.Idle>(viewModel.state.value)
        }

    @Test
    fun `search success emits Results carrying query and types`() =
        runTest {
            val fixture = createFixture()
            val expectedHits = listOf(createBookHit(), createContributorHit())
            everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                createSearchResult(hits = expectedHits)
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.onQueryChanged("test")
            advanceTimeBy(400.milliseconds)
            advanceUntilIdle()

            val state = assertIs<SearchUiState.Results>(viewModel.state.value)
            assertEquals("test", state.query)
            assertEquals(2, state.result.hits.size)
        }

    @Test
    fun `search failure emits Error with user-friendly message`() =
        runTest {
            val fixture = createFixture()
            everySuspend {
                fixture.searchRepository.search(any(), any(), any(), any(), any())
            } throws Exception("Network error")
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.onQueryChanged("test")
            advanceTimeBy(400.milliseconds)
            advanceUntilIdle()

            val state = assertIs<SearchUiState.Error>(viewModel.state.value)
            assertEquals("test", state.query)
            assertEquals("Search unavailable. Please try again.", state.message)
        }

    @Test
    fun `toggleTypeFilter adds type to selectedTypes`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()
            assertTrue(
                viewModel.state.value.selectedTypes
                    .isEmpty(),
            )

            viewModel.toggleTypeFilter(SearchHitType.BOOK)
            advanceUntilIdle()

            assertEquals(setOf(SearchHitType.BOOK), viewModel.state.value.selectedTypes)
        }

    @Test
    fun `toggleTypeFilter removes type if already selected`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            viewModel.toggleTypeFilter(SearchHitType.BOOK)
            advanceUntilIdle()
            assertEquals(setOf(SearchHitType.BOOK), viewModel.state.value.selectedTypes)

            viewModel.toggleTypeFilter(SearchHitType.BOOK)
            advanceUntilIdle()

            assertTrue(
                viewModel.state.value.selectedTypes
                    .isEmpty(),
            )
        }

    @Test
    fun `toggleTypeFilter triggers re-search immediately when query present`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns createSearchResult()
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.onQueryChanged("test")
            advanceTimeBy(400.milliseconds)
            advanceUntilIdle()

            viewModel.toggleTypeFilter(SearchHitType.BOOK)
            advanceUntilIdle()

            verifySuspend {
                fixture.searchRepository.search(
                    query = "test",
                    types = listOf(SearchHitType.BOOK),
                    genres = null,
                    genrePath = null,
                    limit = 30,
                )
            }
        }

    @Test
    fun `onResultClicked on book emits NavigateToBook`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.navActions.test {
                viewModel.onResultClicked(createBookHit(id = "book-123"))
                advanceUntilIdle()
                val action = assertIs<SearchNavAction.NavigateToBook>(awaitItem())
                assertEquals("book-123", action.bookId)
            }
        }

    @Test
    fun `onResultClicked on contributor emits NavigateToContributor`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.navActions.test {
                viewModel.onResultClicked(createContributorHit(id = "author-456"))
                advanceUntilIdle()
                val action = assertIs<SearchNavAction.NavigateToContributor>(awaitItem())
                assertEquals("author-456", action.contributorId)
            }
        }

    @Test
    fun `onResultClicked on series emits NavigateToSeries`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.navActions.test {
                viewModel.onResultClicked(createSeriesHit(id = "series-789"))
                advanceUntilIdle()
                val action = assertIs<SearchNavAction.NavigateToSeries>(awaitItem())
                assertEquals("series-789", action.seriesId)
            }
        }

    @Test
    fun `onResultClicked on tag emits NavigateToTag`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.navActions.test {
                viewModel.onResultClicked(createTagHit(id = "tag-42"))
                advanceUntilIdle()
                val action = assertIs<SearchNavAction.NavigateToTag>(awaitItem())
                assertEquals("tag-42", action.tagId)
            }
        }

    @Test
    fun `clearQuery returns state to Idle after Results`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                createSearchResult(hits = listOf(createBookHit()))
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.onQueryChanged("test")
            advanceTimeBy(400.milliseconds)
            advanceUntilIdle()
            assertIs<SearchUiState.Results>(viewModel.state.value)

            viewModel.clearQuery()
            advanceTimeBy(50.milliseconds)
            advanceUntilIdle()

            val state = assertIs<SearchUiState.Idle>(viewModel.state.value)
            assertEquals("", state.query)
        }
}
