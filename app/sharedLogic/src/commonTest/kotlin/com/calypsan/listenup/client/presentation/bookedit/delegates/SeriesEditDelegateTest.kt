package com.calypsan.listenup.client.presentation.bookedit.delegates

import com.calypsan.listenup.client.domain.model.MIN_SEARCH_QUERY_LENGTH
import com.calypsan.listenup.client.domain.model.SeriesSearchResponse
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests for [SeriesEditDelegate]'s debounced search-query gating.
 *
 * The client's local FTS5 index uses `tokenize='trigram'`, which cannot match a query
 * shorter than [MIN_SEARCH_QUERY_LENGTH] — a query below the floor must never reach
 * [SeriesRepository.searchSeries], let alone the index itself.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SeriesEditDelegateTest :
    FunSpec({

        class Fixture {
            val state = MutableStateFlow(BookEditUiState(bookId = "book-1"))
            val seriesRepository: SeriesRepository = mock()

            fun build(scope: CoroutineScope): SeriesEditDelegate =
                SeriesEditDelegate(
                    state = state,
                    seriesRepository = seriesRepository,
                    scope = scope,
                    onChangesMade = {},
                )
        }

        test("updateSearchQuery below MIN_SEARCH_QUERY_LENGTH never calls searchSeries") {
            runTest {
                val fixture = Fixture()
                val delegate = fixture.build(backgroundScope)

                delegate.updateSearchQuery("ab")
                advanceTimeBy(SEARCH_DEBOUNCE_MS + DEBOUNCE_MARGIN_MS)
                runCurrent()

                verifySuspend(VerifyMode.not) { fixture.seriesRepository.searchSeries(any(), any()) }
            }
        }

        test("updateSearchQuery at MIN_SEARCH_QUERY_LENGTH calls searchSeries and populates results") {
            runTest {
                val fixture = Fixture()
                everySuspend { fixture.seriesRepository.searchSeries(any(), any()) } returns
                    SeriesSearchResponse(
                        series = listOf(SeriesSearchResult(id = "series-1", name = "Cosmere", bookCount = 3)),
                        isOfflineResult = true,
                        tookMs = 1L,
                    )
                val delegate = fixture.build(backgroundScope)

                delegate.updateSearchQuery("cos")
                advanceTimeBy(SEARCH_DEBOUNCE_MS + DEBOUNCE_MARGIN_MS)
                runCurrent()

                verifySuspend { fixture.seriesRepository.searchSeries("cos", limit = 10) }
                fixture.state.value.seriesSearchResults
                    .map { it.id } shouldContainExactly listOf("series-1")
            }
        }
    })

/** The delegate's debounce window; virtual time must clear it before the search fires. */
private const val SEARCH_DEBOUNCE_MS = 300L

/** Slack past the debounce so the test never sits exactly on the boundary. */
private const val DEBOUNCE_MARGIN_MS = 50L
