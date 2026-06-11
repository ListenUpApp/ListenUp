package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.data.remote.SearchHitResponse
import com.calypsan.listenup.client.data.remote.SearchResponse
import com.calypsan.listenup.client.presentation.admin.absimport.BookMappingDelegate
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BookMappingDelegateTest : FunSpec({
    lateinit var state: MutableStateFlow<ABSImportUiState>
    lateinit var searchApi: SearchApiContract
    lateinit var errorBus: ErrorBus

    fun buildDelegate(scope: TestScope): BookMappingDelegate {
        state = MutableStateFlow<ABSImportUiState>(ABSImportUiState.Ready())
        searchApi = mock()
        errorBus = ErrorBus()
        return BookMappingDelegate(
            scope = scope,
            state = state,
            errorBus = errorBus,
            searchApi = searchApi,
        )
    }

    fun ready() = state.value as ABSImportUiState.Ready

    fun searchResponse(vararg hits: SearchHitResponse) = SearchResponse(
        query = "",
        total = hits.size.toLong(),
        tookMs = 1L,
        hits = hits.toList(),
    )

    test("updateBookSearchQuery populates bookSearchResults when api returns hits") {
        runTest(StandardTestDispatcher()) {
            val delegate = buildDelegate(this)
            val hit = SearchHitResponse(id = "book1", type = "book", name = "Dune")
            everySuspend {
                searchApi.search(any(), any(), any(), any(), any(), any(), any(), any())
            } returns searchResponse(hit)

            delegate.updateBookSearchQuery("du")
            advanceUntilIdle()

            ready().bookSearchResults shouldHaveSize 1
            ready().bookSearchResults.first().id shouldBe "book1"
            ready().isSearchingBooks shouldBe false
        }
    }

    test("selectBook populates bookMappings and selectedBookDisplays and clears search state") {
        runTest(StandardTestDispatcher()) {
            val delegate = buildDelegate(this)

            delegate.selectBook(
                absItemId = "abs-book-1",
                bookId = "book1",
                title = "Dune",
                author = "Frank Herbert",
                durationMs = 80_000L,
            )
            advanceUntilIdle()

            val r = ready()
            r.bookMappings shouldContainKey "abs-book-1"
            r.bookMappings["abs-book-1"] shouldBe "book1"
            r.selectedBookDisplays shouldContainKey "abs-book-1"
            r.selectedBookDisplays["abs-book-1"]?.bookId shouldBe "book1"
            r.selectedBookDisplays["abs-book-1"]?.title shouldBe "Dune"
            r.activeSearchAbsItemId.shouldBeNull()
        }
    }

    test("clearBookMapping removes mapping and display after selectBook") {
        runTest(StandardTestDispatcher()) {
            val delegate = buildDelegate(this)

            delegate.selectBook(
                absItemId = "abs-book-1",
                bookId = "book1",
                title = "Dune",
                author = null,
                durationMs = null,
            )
            advanceUntilIdle()

            // precondition: mapping is present
            ready().bookMappings shouldContainKey "abs-book-1"

            delegate.clearBookMapping("abs-book-1")

            val r = ready()
            r.bookMappings shouldNotContainKey "abs-book-1"
            r.selectedBookDisplays shouldNotContainKey "abs-book-1"
        }
    }

    test("updateBookSearchQuery with short query clears results without searching") {
        runTest(StandardTestDispatcher()) {
            val delegate = buildDelegate(this)

            state.value = ABSImportUiState.Ready(
                bookSearchResults = listOf(SearchHitResponse(id = "b1", type = "book", name = "Dune")),
            )

            delegate.updateBookSearchQuery("d") // length < 2
            advanceUntilIdle()

            ready().bookSearchResults shouldHaveSize 0
            ready().isSearchingBooks shouldBe false
        }
    }

    test("activateBookSearch sets activeSearchAbsItemId and clears prior search state") {
        runTest(StandardTestDispatcher()) {
            val delegate = buildDelegate(this)

            delegate.activateBookSearch("abs-book-2")

            val r = ready()
            r.activeSearchAbsItemId shouldBe "abs-book-2"
            r.bookSearchQuery shouldBe ""
            r.bookSearchResults shouldHaveSize 0
        }
    }

    test("deactivateBookSearch clears activeSearchAbsItemId") {
        runTest(StandardTestDispatcher()) {
            val delegate = buildDelegate(this)

            delegate.activateBookSearch("abs-book-2")
            delegate.deactivateBookSearch()

            ready().activeSearchAbsItemId.shouldBeNull()
        }
    }

    test("selectedBookDisplays entry carries author and durationMs") {
        runTest(StandardTestDispatcher()) {
            val delegate = buildDelegate(this)

            delegate.selectBook(
                absItemId = "abs-book-3",
                bookId = "book3",
                title = "Foundation",
                author = "Isaac Asimov",
                durationMs = 50_000L,
            )
            advanceUntilIdle()

            val display = ready().selectedBookDisplays["abs-book-3"]
            display?.author shouldBe "Isaac Asimov"
            display?.durationMs shouldBe 50_000L
        }
    }
})
