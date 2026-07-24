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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import kotlin.time.Duration.Companion.milliseconds
import com.calypsan.listenup.core.error.ErrorBus

/**
 * Tests for [SearchViewModel].
 *
 * Every test calls [keepStateHot] because `state` uses `stateIn(WhileSubscribed)` —
 * without an active collector the upstream pipeline is torn down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        class TestFixture {
            val searchRepository: SearchRepository = mock()

            fun build(): SearchViewModel = SearchViewModel(searchRepository = searchRepository, errorBus = ErrorBus())
        }

        fun TestScope.createFixture(): TestFixture = TestFixture()

        fun TestScope.keepStateHot(viewModel: SearchViewModel) {
            backgroundScope.launch { viewModel.state.collect { } }
        }

        fun createSearchResult(
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

        fun createBookHit(
            id: String = "book-1",
            name: String = "Test Book",
        ): SearchHit =
            SearchHit(
                id = id,
                type = SearchHitType.BOOK,
                name = name,
            )

        fun createContributorHit(
            id: String = "contributor-1",
            name: String = "Test Author",
        ): SearchHit =
            SearchHit(
                id = id,
                type = SearchHitType.CONTRIBUTOR,
                name = name,
            )

        fun createSeriesHit(
            id: String = "series-1",
            name: String = "Test Series",
        ): SearchHit =
            SearchHit(
                id = id,
                type = SearchHitType.SERIES,
                name = name,
            )

        fun createTagHit(
            id: String = "tag-1",
            name: String = "Test Tag",
        ): SearchHit =
            SearchHit(
                id = id,
                type = SearchHitType.TAG,
                name = name,
            )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        test("initial state is Idle with empty query and no type filters") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SearchUiState.Idle>()
                state.query shouldBe ""
                state.selectedTypes.isEmpty() shouldBe true
            }
        }

        test("onQueryChanged reflects new query in state immediately") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.onQueryChanged("hello")
                advanceUntilIdle()

                viewModel.state.value.query shouldBe "hello"
            }
        }

        test("search triggers after debounce for query with min 2 chars") {
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
                viewModel.state.value.shouldBeInstanceOf<SearchUiState.Idle>()

                advanceTimeBy(150.milliseconds)
                advanceUntilIdle()

                val results = viewModel.state.value.shouldBeInstanceOf<SearchUiState.Results>()
                results.result.hits.size shouldBe 1
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
        }

        test("search does not trigger for single character query") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.onQueryChanged("a")
                advanceTimeBy(500.milliseconds)
                advanceUntilIdle()

                // Phase stays Idle; search never executes.
                viewModel.state.value.shouldBeInstanceOf<SearchUiState.Idle>()
                viewModel.state.value.query shouldBe "a"
            }
        }

        test("blank query returns phase to Idle after prior results") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                    createSearchResult(hits = listOf(createBookHit()))
                val viewModel = fixture.build()
                keepStateHot(viewModel)

                viewModel.onQueryChanged("test")
                advanceTimeBy(400.milliseconds)
                advanceUntilIdle()
                viewModel.state.value.shouldBeInstanceOf<SearchUiState.Results>()

                viewModel.onQueryChanged("")
                advanceTimeBy(100.milliseconds)
                advanceUntilIdle()

                viewModel.state.value.shouldBeInstanceOf<SearchUiState.Idle>()
            }
        }

        test("search success emits Results carrying query and types") {
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

                val state = viewModel.state.value.shouldBeInstanceOf<SearchUiState.Results>()
                state.query shouldBe "test"
                state.result.hits.size shouldBe 2
            }
        }

        test("search failure emits Error with user-friendly message") {
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

                val state = viewModel.state.value.shouldBeInstanceOf<SearchUiState.Error>()
                state.query shouldBe "test"
                state.message shouldBe "Search unavailable. Please try again."
            }
        }

        test("toggleTypeFilter adds type to selectedTypes") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()
                viewModel.state.value.selectedTypes
                    .isEmpty() shouldBe true

                viewModel.toggleTypeFilter(SearchHitType.BOOK)
                advanceUntilIdle()

                viewModel.state.value.selectedTypes shouldBe setOf(SearchHitType.BOOK)
            }
        }

        test("toggleTypeFilter removes type if already selected") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                viewModel.toggleTypeFilter(SearchHitType.BOOK)
                advanceUntilIdle()
                viewModel.state.value.selectedTypes shouldBe setOf(SearchHitType.BOOK)

                viewModel.toggleTypeFilter(SearchHitType.BOOK)
                advanceUntilIdle()

                viewModel.state.value.selectedTypes
                    .isEmpty() shouldBe true
            }
        }

        test("clearTypeFilters resets selectedTypes to empty") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                viewModel.toggleTypeFilter(SearchHitType.BOOK)
                viewModel.toggleTypeFilter(SearchHitType.SERIES)
                advanceUntilIdle()
                viewModel.state.value.selectedTypes shouldBe setOf(SearchHitType.BOOK, SearchHitType.SERIES)

                viewModel.clearTypeFilters()
                advanceUntilIdle()

                viewModel.state.value.selectedTypes
                    .isEmpty() shouldBe true
            }
        }

        test("toggleTypeFilter triggers re-search immediately when query present") {
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
        }

        test("onResultClicked on book emits NavigateToBook") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.navActions.test {
                    viewModel.onResultClicked(createBookHit(id = "book-123"))
                    advanceUntilIdle()
                    val action = awaitItem().shouldBeInstanceOf<SearchNavAction.NavigateToBook>()
                    action.bookId shouldBe "book-123"
                }
            }
        }

        test("onResultClicked on contributor emits NavigateToContributor") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.navActions.test {
                    viewModel.onResultClicked(createContributorHit(id = "author-456"))
                    advanceUntilIdle()
                    val action = awaitItem().shouldBeInstanceOf<SearchNavAction.NavigateToContributor>()
                    action.contributorId shouldBe "author-456"
                }
            }
        }

        test("onResultClicked on series emits NavigateToSeries") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.navActions.test {
                    viewModel.onResultClicked(createSeriesHit(id = "series-789"))
                    advanceUntilIdle()
                    val action = awaitItem().shouldBeInstanceOf<SearchNavAction.NavigateToSeries>()
                    action.seriesId shouldBe "series-789"
                }
            }
        }

        test("onResultClicked on tag emits NavigateToTag") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.navActions.test {
                    viewModel.onResultClicked(createTagHit(id = "tag-42", name = "Staff Pick"))
                    advanceUntilIdle()
                    val action = awaitItem().shouldBeInstanceOf<SearchNavAction.NavigateToTag>()
                    action.tagId shouldBe "tag-42"
                    action.tagName shouldBe "Staff Pick"
                }
            }
        }

        test("clearQuery returns state to Idle after Results") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                    createSearchResult(hits = listOf(createBookHit()))
                val viewModel = fixture.build()
                keepStateHot(viewModel)

                viewModel.onQueryChanged("test")
                advanceTimeBy(400.milliseconds)
                advanceUntilIdle()
                viewModel.state.value.shouldBeInstanceOf<SearchUiState.Results>()

                viewModel.clearQuery()
                advanceTimeBy(50.milliseconds)
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SearchUiState.Idle>()
                state.query shouldBe ""
            }
        }
    })
