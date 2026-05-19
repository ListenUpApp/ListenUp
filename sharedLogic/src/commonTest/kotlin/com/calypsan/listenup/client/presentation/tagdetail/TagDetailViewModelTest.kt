package com.calypsan.listenup.client.presentation.tagdetail

import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Tests for TagDetailViewModel.
 *
 * Tests cover:
 * - Initial state (Idle)
 * - Books loaded for a tag
 * - N+1 regression: getBooks called once with full list, not per-book getBook
 *
 * Uses Mokkery for mocking domain repositories.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixtures ==========

    private class TestFixture {
        val tagRepository: TagRepository = mock()
        val bookRepository: BookRepository = mock()

        fun build(): TagDetailViewModel =
            TagDetailViewModel(
                tagRepository = tagRepository,
                bookRepository = bookRepository,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        every { fixture.tagRepository.observeById(any()) } returns flowOf(null)
        every { fixture.tagRepository.observeBookIdsForTag(any()) } returns flowOf(emptyList())
        everySuspend { fixture.bookRepository.getBookListItems(any()) } returns emptyList()

        return fixture
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun teardown() {
        Dispatchers.resetMain()
    }

    // ========== Basic State Tests ==========

    @Test
    fun `initial state is Idle`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()

            assertIs<TagDetailUiState.Idle>(viewModel.state.value)
        }

    @Test
    fun `loadTag transitions to Ready when tag and books are found`() =
        runTest {
            // Given
            val fixture = createFixture()
            val tag = Tag(id = "tag-1", slug = "found-family", bookCount = 2)
            val books = listOf(TestData.bookListItem(id = "book-1"), TestData.bookListItem(id = "book-2"))

            every { fixture.tagRepository.observeById("tag-1") } returns flowOf(tag)
            every { fixture.tagRepository.observeBookIdsForTag("tag-1") } returns flowOf(listOf("book-1", "book-2"))
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns books

            val viewModel = fixture.build()
            backgroundScope.launch { viewModel.state.collect { } }

            // When
            viewModel.loadTag("tag-1")
            advanceUntilIdle()

            // Then
            assertIs<TagDetailUiState.Ready>(viewModel.state.value)
        }

    // ========== Regression Test: N+1 fix — getBookListItems called once with full list ==========

    @Test
    fun `observeBooksForTag calls getBookListItems once with full list, not per book`() =
        runTest {
            // Given: tag with three book IDs — verifies batched call replaces per-book loop
            val fixture = createFixture()
            val tag = Tag(id = "tag-1", slug = "mystery", bookCount = 3)
            val bookIds = listOf("book-1", "book-2", "book-3")
            val books = bookIds.map { TestData.bookListItem(id = it) }

            every { fixture.tagRepository.observeById("tag-1") } returns flowOf(tag)
            every { fixture.tagRepository.observeBookIdsForTag("tag-1") } returns flowOf(bookIds)
            everySuspend { fixture.bookRepository.getBookListItems(any()) } returns books

            val viewModel = fixture.build()
            backgroundScope.launch { viewModel.state.collect { } }

            // When
            viewModel.loadTag("tag-1")
            advanceUntilIdle()

            // Then: getBookListItems called exactly once (batched)
            verifySuspend(VerifyMode.exactly(1)) { fixture.bookRepository.getBookListItems(any()) }
        }
}
