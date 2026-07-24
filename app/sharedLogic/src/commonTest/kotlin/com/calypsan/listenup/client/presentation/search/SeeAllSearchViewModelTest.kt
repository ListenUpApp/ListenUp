package com.calypsan.listenup.client.presentation.search

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.core.error.ErrorBus
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [SeeAllSearchViewModel].
 *
 * Every test calls [keepStateHot] because `state` uses `stateIn(WhileSubscribed)` —
 * without an active collector the upstream pipeline is torn down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeeAllSearchViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        class TestFixture {
            val searchRepository: SearchRepository = mock()

            fun build(): SeeAllSearchViewModel = SeeAllSearchViewModel(searchRepository = searchRepository, errorBus = ErrorBus())
        }

        fun TestScope.createFixture(): TestFixture = TestFixture()

        fun TestScope.keepStateHot(viewModel: SeeAllSearchViewModel) {
            backgroundScope.launch { viewModel.state.collect { } }
        }

        fun bookHit(
            id: String,
            name: String = "Book $id",
        ): SearchHit = SearchHit(id = id, type = SearchHitType.BOOK, name = name)

        fun seriesHit(
            id: String,
            name: String = "Series $id",
        ): SearchHit = SearchHit(id = id, type = SearchHitType.SERIES, name = name)

        fun searchResult(hits: List<SearchHit>): SearchResult = SearchResult(query = "test", total = hits.size, tookMs = 5L, hits = hits)

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        test("initial state is Idle before any load") {
            runTest {
                val viewModel = createFixture().build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.state.value shouldBe SeeAllSearchUiState.Idle
            }
        }

        test("load emits Results carrying the requested type and only that type's hits") {
            runTest {
                val fixture = createFixture()
                val hits = (1..6).map { bookHit("book-$it") }
                everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                    searchResult(hits)
                val viewModel = fixture.build()
                keepStateHot(viewModel)

                viewModel.load(query = "dragon", type = SearchHitType.BOOK)
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeeAllSearchUiState.Results>()
                state.type shouldBe SearchHitType.BOOK
                state.query shouldBe "dragon"
                state.hits.size shouldBe 6
                verifySuspend {
                    fixture.searchRepository.search(
                        query = "dragon",
                        types = listOf(SearchHitType.BOOK),
                        genres = null,
                        genrePath = null,
                        limit = 100,
                    )
                }
            }
        }

        test("load filters out hits whose type does not match the request") {
            runTest {
                val fixture = createFixture()
                val mixed = listOf(seriesHit("s-1"), bookHit("b-1"), seriesHit("s-2"))
                everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                    searchResult(mixed)
                val viewModel = fixture.build()
                keepStateHot(viewModel)

                viewModel.load(query = "saga", type = SearchHitType.SERIES)
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeeAllSearchUiState.Results>()
                state.hits.map { it.id } shouldBe listOf("s-1", "s-2")
            }
        }

        test("load failure emits Error with user-friendly message") {
            runTest {
                val fixture = createFixture()
                everySuspend {
                    fixture.searchRepository.search(any(), any(), any(), any(), any())
                } throws Exception("Network error")
                val viewModel = fixture.build()
                keepStateHot(viewModel)

                viewModel.load(query = "test", type = SearchHitType.BOOK)
                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<SeeAllSearchUiState.Error>()
                state.message shouldBe "Search unavailable. Please try again."
            }
        }

        test("onResultClicked on book emits NavigateToBook") {
            runTest {
                val viewModel = createFixture().build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.navActions.test {
                    viewModel.onResultClicked(bookHit("book-123"))
                    advanceUntilIdle()
                    val action = awaitItem().shouldBeInstanceOf<SearchNavAction.NavigateToBook>()
                    action.bookId shouldBe "book-123"
                }
            }
        }

        test("onResultClicked on series emits NavigateToSeries") {
            runTest {
                val viewModel = createFixture().build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.navActions.test {
                    viewModel.onResultClicked(seriesHit("series-789"))
                    advanceUntilIdle()
                    val action = awaitItem().shouldBeInstanceOf<SearchNavAction.NavigateToSeries>()
                    action.seriesId shouldBe "series-789"
                }
            }
        }
    })
