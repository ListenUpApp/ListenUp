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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for TagDetailViewModel.
 *
 * Tests cover:
 * - Initial state (Idle)
 * - Books loaded for a tag
 * - N+1 regression: getBooks called once with full list, not per-book getBook
 *
 * Uses Mokkery for mocking domain repositories.
 * Uses MutableStateFlow (not flowOf) as upstream per test_stateflow_use_mutablestateflow memory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagDetailViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeEach { Dispatchers.setMain(testDispatcher) }
        afterEach { Dispatchers.resetMain() }

        fun createFixture(): Triple<TagRepository, BookRepository, TagDetailViewModel> {
            val tagRepository: TagRepository = mock()
            val bookRepository: BookRepository = mock()
            every { tagRepository.observeById(any()) } returns MutableStateFlow(null)
            every { tagRepository.observeBookIdsForTag(any()) } returns MutableStateFlow(emptyList())
            everySuspend { bookRepository.getBookListItems(any()) } returns emptyList()
            val vm = TagDetailViewModel(tagRepository = tagRepository, bookRepository = bookRepository)
            return Triple(tagRepository, bookRepository, vm)
        }

        test("initial state is Idle") {
            runTest {
                val (_, _, vm) = createFixture()
                vm.state.value.shouldBeInstanceOf<TagDetailUiState.Idle>()
            }
        }

        test("loadTag transitions to Ready when tag and books are found") {
            runTest {
                val (tagRepo, bookRepo, vm) = createFixture()
                val tag = Tag(id = "tag-1", name = "Found Family", slug = "found-family")
                val books = listOf(TestData.bookListItem(id = "book-1"), TestData.bookListItem(id = "book-2"))

                every { tagRepo.observeById("tag-1") } returns MutableStateFlow(tag)
                every { tagRepo.observeBookIdsForTag("tag-1") } returns MutableStateFlow(listOf("book-1", "book-2"))
                everySuspend { bookRepo.getBookListItems(any()) } returns books

                backgroundScope.launch { vm.state.collect { } }

                vm.loadTag("tag-1")
                advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<TagDetailUiState.Ready>()
            }
        }

        test("observeBooksForTag calls getBookListItems once with full list, not per book") {
            runTest {
                // Given: tag with three book IDs — verifies batched call replaces per-book loop
                val (tagRepo, bookRepo, vm) = createFixture()
                val tag = Tag(id = "tag-1", name = "Mystery", slug = "mystery")
                val bookIds = listOf("book-1", "book-2", "book-3")
                val books = bookIds.map { TestData.bookListItem(id = it) }

                every { tagRepo.observeById("tag-1") } returns MutableStateFlow(tag)
                every { tagRepo.observeBookIdsForTag("tag-1") } returns MutableStateFlow(bookIds)
                everySuspend { bookRepo.getBookListItems(any()) } returns books

                backgroundScope.launch { vm.state.collect { } }

                vm.loadTag("tag-1")
                advanceUntilIdle()

                // getBookListItems called exactly once (batched)
                verifySuspend(VerifyMode.exactly(1)) { bookRepo.getBookListItems(any()) }
            }
        }
    })
