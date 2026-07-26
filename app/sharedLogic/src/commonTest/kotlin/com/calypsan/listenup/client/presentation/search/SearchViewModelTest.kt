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
import dev.mokkery.verify.VerifyMode.Companion.not
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
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

        test("search triggers after debounce once the query reaches the trigram floor") {
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

                viewModel.onQueryChanged("tes")

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
                        query = "tes",
                        types = null,
                        genres = null,
                        genrePath = null,
                        limit = 30,
                    )
                }
            }
        }

        // The trigram tokenizer cannot match a query shorter than three characters, so a two-char
        // query is not "a search that found nothing" — it is a search that could never succeed.
        // Running it anyway is what produced a "no results" screen for `JK`, indistinguishable from
        // a real miss. The floor is MIN_SEARCH_QUERY_LENGTH; below it we must not query at all.
        test("a below-floor query surfaces TooShort and never reaches the repository") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.onQueryChanged("te")
                advanceTimeBy(500.milliseconds)
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SearchUiState.TooShort>()
                state.query shouldBe "te"
                verifySuspend(not) {
                    fixture.searchRepository.search(any(), any(), any(), any(), any())
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

                viewModel.state.value.shouldBeInstanceOf<SearchUiState.TooShort>()
                viewModel.state.value.query shouldBe "a"
            }
        }

        // Backspacing out of a completed search used to leave the old hits on screen: the
        // below-floor branch emitted nothing at all, so the phase kept its last value while the
        // query text updated underneath it — results for a query the user no longer had typed.
        test("backspacing below the floor clears prior results instead of stranding them") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                    createSearchResult(query = "test", hits = listOf(createBookHit()))
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.onQueryChanged("test")
                advanceTimeBy(500.milliseconds)
                advanceUntilIdle()
                viewModel.state.value.shouldBeInstanceOf<SearchUiState.Results>()

                viewModel.onQueryChanged("te")
                advanceTimeBy(500.milliseconds)
                advanceUntilIdle()

                viewModel.state.value.shouldBeInstanceOf<SearchUiState.TooShort>()
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

        test("selectedTypeNames projects the selected types as their enum names") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()
                viewModel.state.value.selectedTypeNames
                    .shouldBeEmpty()

                viewModel.toggleTypeFilter(SearchHitType.SERIES)
                advanceUntilIdle()

                viewModel.state.value.selectedTypeNames shouldBe listOf("SERIES")
            }
        }

        test("selectedTypeNames follows declaration order, not insertion order") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)

                // Toggled tag-first; the projection must still read in SearchHitType.entries order so
                // iOS's scope derivation can't flicker on set-iteration order.
                viewModel.toggleTypeFilter(SearchHitType.TAG)
                viewModel.toggleTypeFilter(SearchHitType.BOOK)
                advanceUntilIdle()

                viewModel.state.value.selectedTypeNames shouldBe listOf("BOOK", "TAG")
            }
        }

        test("every SearchHitType survives the selectedTypeNames projection") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                SearchHitType.entries.forEach { viewModel.toggleTypeFilter(it) }
                advanceUntilIdle()

                // Pins the exact strings iOS parses back through Swift Export's generated
                // `SearchHitType(_ description:)` init — a rename on either side must fail here.
                viewModel.state.value.selectedTypeNames shouldBe
                    listOf("BOOK", "CONTRIBUTOR", "SERIES", "TAG")
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

        test("setTypeFilter replaces the filter set rather than adding to it") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                viewModel.toggleTypeFilter(SearchHitType.BOOK)
                viewModel.toggleTypeFilter(SearchHitType.TAG)
                advanceUntilIdle()
                viewModel.state.value.selectedTypes shouldBe setOf(SearchHitType.BOOK, SearchHitType.TAG)

                viewModel.setTypeFilter(SearchHitType.SERIES)
                advanceUntilIdle()

                viewModel.state.value.selectedTypes shouldBe setOf(SearchHitType.SERIES)
            }
        }

        test("setTypeFilter switches scope without searching the intermediate type pair") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns createSearchResult()
                val viewModel = fixture.build()
                keepStateHot(viewModel)

                viewModel.onQueryChanged("test")
                advanceTimeBy(400.milliseconds)
                viewModel.setTypeFilter(SearchHitType.BOOK)
                advanceUntilIdle()

                viewModel.setTypeFilter(SearchHitType.SERIES)
                advanceUntilIdle()

                verifySuspend {
                    fixture.searchRepository.search(
                        query = "test",
                        types = listOf(SearchHitType.SERIES),
                        genres = null,
                        genrePath = null,
                        limit = 30,
                    )
                }
                // iOS used to switch scopes by firing the symmetric difference as additive toggles
                // (BOOK off, SERIES on), so the VM briefly held both and could issue a search for a
                // pair the user never selected. One replacing call cannot produce that state.
                verifySuspend(not) {
                    fixture.searchRepository.search(
                        query = "test",
                        types = listOf(SearchHitType.BOOK, SearchHitType.SERIES),
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
