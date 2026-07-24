package com.calypsan.listenup.client.presentation.browsefacet

import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.core.TagId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
 * Tests for BrowseFacetViewModel.
 *
 * Covers the single parameterized facet-browse surface over both flat axes:
 * - Initial Loading before [BrowseFacetViewModel.load]
 * - Tag: Ready carries the resolved name, the sorted book list, count, and total duration
 * - Mood: same pipeline resolves through the mood repository
 * - Missing facet → NotFound carrying the requested kind
 * - Ready's bookCount/totalDurationMs are sourced from the server stats aggregate, not summed
 *   from the local Room-hydrated book list
 * - A failed stats RPC falls back to the client-computed count/length rather than blanking the page
 *
 * Uses Mokkery for the repositories and MutableStateFlow upstreams (not flowOf) per
 * test_stateflow_use_mutablestateflow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseFacetViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeEach { Dispatchers.setMain(testDispatcher) }
        afterEach { Dispatchers.resetMain() }

        data class Fixture(
            val tagRepository: TagRepository,
            val moodRepository: MoodRepository,
            val bookRepository: BookRepository,
            val viewModel: BrowseFacetViewModel,
        )

        fun createFixture(): Fixture {
            val tagRepository: TagRepository = mock()
            val moodRepository: MoodRepository = mock()
            val bookRepository: BookRepository = mock()
            every { tagRepository.observeById(any()) } returns MutableStateFlow(null)
            every { tagRepository.observeBookIdsForTag(any()) } returns MutableStateFlow(emptyList())
            every { moodRepository.observeById(any()) } returns MutableStateFlow(null)
            every { moodRepository.observeBookIdsForMood(any()) } returns MutableStateFlow(emptyList())
            every { bookRepository.observeBookListItems(any()) } returns MutableStateFlow(emptyList())
            everySuspend { tagRepository.getTagStats(any()) } returns AppResult.Success(FacetStats.EMPTY)
            everySuspend { moodRepository.getMoodStats(any()) } returns AppResult.Success(FacetStats.EMPTY)
            val vm =
                BrowseFacetViewModel(
                    tagRepository = tagRepository,
                    moodRepository = moodRepository,
                    bookRepository = bookRepository,
                )
            return Fixture(tagRepository, moodRepository, bookRepository, vm)
        }

        test("initial state is Loading before load() is called") {
            runTest {
                val fixture = createFixture()
                fixture.viewModel.state.value
                    .shouldBeInstanceOf<BrowseFacetUiState.Loading>()
            }
        }

        test("Tag: load transitions to Ready with sorted books, count, and total duration") {
            runTest {
                val fixture = createFixture()
                val tag = Tag(id = "tag-1", name = "Staff Pick", slug = "staff-pick")
                val books =
                    listOf(
                        TestData.bookListItem(id = "book-2", title = "Zelda", duration = 3_600_000L),
                        TestData.bookListItem(id = "book-1", title = "Alpha", duration = 1_800_000L),
                    )
                every { fixture.tagRepository.observeById("tag-1") } returns MutableStateFlow(tag)
                every { fixture.tagRepository.observeBookIdsForTag("tag-1") } returns
                    MutableStateFlow(listOf("book-1", "book-2"))
                every { fixture.bookRepository.observeBookListItems(any()) } returns MutableStateFlow(books)
                everySuspend { fixture.tagRepository.getTagStats(TagId("tag-1")) } returns
                    AppResult.Success(FacetStats(bookCount = 2, totalDurationMs = 5_400_000L))

                backgroundScope.launch { fixture.viewModel.state.collect { } }
                fixture.viewModel.load(FacetKind.Tag, "tag-1")
                advanceUntilIdle()

                val ready =
                    fixture.viewModel.state.value
                        .shouldBeInstanceOf<BrowseFacetUiState.Ready>()
                ready.kind shouldBe FacetKind.Tag
                ready.facetName shouldBe "Staff Pick"
                ready.books.map { it.title } shouldBe listOf("Alpha", "Zelda")
                ready.bookCount shouldBe 2
                ready.totalDurationMs shouldBe 5_400_000L
            }
        }

        test("Tag: Ready's count and length come from the server stats aggregate, not the local book sum") {
            runTest {
                val fixture = createFixture()
                val tag = Tag(id = "tag-1", name = "Staff Pick", slug = "staff-pick")
                // Only one book is hydrated locally (e.g. the local list is capped/partial), but the
                // server aggregate knows about the full live set — the page must report the server's
                // numbers, not the sum of what happens to be in the local list.
                val books = listOf(TestData.bookListItem(id = "book-1", title = "Alpha", duration = 1_800_000L))
                every { fixture.tagRepository.observeById("tag-1") } returns MutableStateFlow(tag)
                every { fixture.tagRepository.observeBookIdsForTag("tag-1") } returns
                    MutableStateFlow(listOf("book-1"))
                every { fixture.bookRepository.observeBookListItems(any()) } returns MutableStateFlow(books)
                everySuspend { fixture.tagRepository.getTagStats(TagId("tag-1")) } returns
                    AppResult.Success(FacetStats(bookCount = 42, totalDurationMs = 999_000_000L))

                backgroundScope.launch { fixture.viewModel.state.collect { } }
                fixture.viewModel.load(FacetKind.Tag, "tag-1")
                advanceUntilIdle()

                val ready =
                    fixture.viewModel.state.value
                        .shouldBeInstanceOf<BrowseFacetUiState.Ready>()
                ready.bookCount shouldBe 42
                ready.totalDurationMs shouldBe 999_000_000L
            }
        }

        test("Tag: a failed stats RPC falls back to the client-computed count/length, page stays Ready") {
            runTest {
                val fixture = createFixture()
                val tag = Tag(id = "tag-1", name = "Staff Pick", slug = "staff-pick")
                val books =
                    listOf(
                        TestData.bookListItem(id = "book-2", title = "Zelda", duration = 3_600_000L),
                        TestData.bookListItem(id = "book-1", title = "Alpha", duration = 1_800_000L),
                    )
                every { fixture.tagRepository.observeById("tag-1") } returns MutableStateFlow(tag)
                every { fixture.tagRepository.observeBookIdsForTag("tag-1") } returns
                    MutableStateFlow(listOf("book-1", "book-2"))
                every { fixture.bookRepository.observeBookListItems(any()) } returns MutableStateFlow(books)
                everySuspend { fixture.tagRepository.getTagStats(TagId("tag-1")) } returns
                    AppResult.Failure(TransportError.NetworkUnavailable(debugInfo = "test"))

                backgroundScope.launch { fixture.viewModel.state.collect { } }
                fixture.viewModel.load(FacetKind.Tag, "tag-1")
                advanceUntilIdle()

                val ready =
                    fixture.viewModel.state.value
                        .shouldBeInstanceOf<BrowseFacetUiState.Ready>()
                ready.books.map { it.title } shouldBe listOf("Alpha", "Zelda")
                ready.bookCount shouldBe 2
                ready.totalDurationMs shouldBe 5_400_000L
            }
        }

        test("Mood: load resolves through the mood repository") {
            runTest {
                val fixture = createFixture()
                val mood = Mood(id = "mood-1", name = "Atmospheric", slug = "atmospheric")
                val books = listOf(TestData.bookListItem(id = "book-1", title = "Piranesi"))
                every { fixture.moodRepository.observeById("mood-1") } returns MutableStateFlow(mood)
                every { fixture.moodRepository.observeBookIdsForMood("mood-1") } returns
                    MutableStateFlow(listOf("book-1"))
                every { fixture.bookRepository.observeBookListItems(any()) } returns MutableStateFlow(books)

                backgroundScope.launch { fixture.viewModel.state.collect { } }
                fixture.viewModel.load(FacetKind.Mood, "mood-1")
                advanceUntilIdle()

                val ready =
                    fixture.viewModel.state.value
                        .shouldBeInstanceOf<BrowseFacetUiState.Ready>()
                ready.kind shouldBe FacetKind.Mood
                ready.facetName shouldBe "Atmospheric"
                ready.books.map { it.title } shouldBe listOf("Piranesi")
            }
        }

        test("missing facet surfaces NotFound carrying the requested kind") {
            runTest {
                val fixture = createFixture()
                every { fixture.tagRepository.observeById("gone") } returns MutableStateFlow(null)

                backgroundScope.launch { fixture.viewModel.state.collect { } }
                fixture.viewModel.load(FacetKind.Tag, "gone")
                advanceUntilIdle()

                val notFound =
                    fixture.viewModel.state.value
                        .shouldBeInstanceOf<BrowseFacetUiState.NotFound>()
                notFound.kind shouldBe FacetKind.Tag
            }
        }
    })
