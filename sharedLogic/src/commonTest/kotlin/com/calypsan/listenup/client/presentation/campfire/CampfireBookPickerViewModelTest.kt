package com.calypsan.listenup.client.presentation.campfire

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [CampfireBookPickerViewModel] — the Discover "Start a campfire" book-picker sheet's
 * ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CampfireBookPickerViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        fun createBook(
            id: String,
            title: String,
        ): BookListItem =
            BookListItem(
                id = BookId(id),
                libraryId = LibraryId("lib-1"),
                folderId = FolderId("folder-1"),
                title = title,
                authors = emptyList(),
                narrators = emptyList(),
                duration = 3_600_000L,
                coverPath = null,
                addedAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            )

        class TestFixture {
            val bookRepository: BookRepository = mock()
            val allBooksFlow = MutableStateFlow<List<BookListItem>>(emptyList())

            fun build(): CampfireBookPickerViewModel {
                every { bookRepository.observeBookListItems() } returns allBooksFlow
                // Default stub so tests that don't care about search results (e.g. exercising
                // onQueryChange alone) don't trip Mokkery's CallNotMockedException when the
                // flatMapLatest re-collects on a query change.
                every { bookRepository.search(any()) } returns flowOf(emptyList())
                return CampfireBookPickerViewModel(bookRepository = bookRepository)
            }
        }

        fun TestScope.keepStateHot(viewModel: CampfireBookPickerViewModel) {
            backgroundScope.launch { viewModel.books.collect { } }
        }

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        test("books shows the full library when the query is blank") {
            runTest {
                // Given
                val fixture = TestFixture()
                fixture.allBooksFlow.value = listOf(createBook("book-1", "A Book"), createBook("book-2", "Another"))

                // When
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.books.value.map { it.id.value } shouldBe listOf("book-1", "book-2")
            }
        }

        test("books re-queries via search when the query is non-blank") {
            runTest {
                // Given - build first, then override the generic search(any()) default stub with a
                // query-specific one (Mokkery matches the most-recently-registered stub first).
                val fixture = TestFixture()
                val viewModel = fixture.build().also { keepStateHot(it) }
                every { fixture.bookRepository.search("way") } returns flowOf(listOf(createBook("book-3", "The Way")))

                // When
                advanceUntilIdle()
                viewModel.onQueryChange("way")
                advanceUntilIdle()

                // Then
                viewModel.books.value.map { it.id.value } shouldBe listOf("book-3")
            }
        }

        test("onQueryChange updates query") {
            runTest {
                // Given
                val fixture = TestFixture()
                val viewModel = fixture.build().also { keepStateHot(it) }

                // When
                viewModel.onQueryChange("hello")
                advanceUntilIdle()

                // Then
                viewModel.query.value shouldBe "hello"
            }
        }
    })
