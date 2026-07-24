package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.data.local.db.BookContributorCrossRef
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookWithContributors
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.CollectionShare
import com.calypsan.listenup.client.domain.model.SearchFacets
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import app.cash.turbine.test
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for the book search-and-add flow on [AdminCollectionDetailViewModel].
 *
 * Verifies:
 *  - [openAddBooks] is a no-op for system collections.
 *  - [onBookQueryChange] debounces and excludes books already in the collection.
 *  - [addBookFromSearch] dispatches [CollectionRepository.addBook] and drops the hit on success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminCollectionDetailViewModelSearchTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeSpec { Dispatchers.setMain(dispatcher) }
        afterSpec { Dispatchers.resetMain() }

        fun collection(
            id: String = "c1",
            name: String = "Favorites",
            isSystem: Boolean = false,
        ) = Collection(
            id = id,
            name = name,
            ownerId = "owner1",
            isInbox = false,
            isSystem = isSystem,
            bookCount = 0,
            callerPermission = SharePermission.Write,
            isOwner = true,
        )

        fun bookWith(
            id: String,
            title: String,
            author: String,
            durationMs: Long = 3_600_000L,
        ): BookWithContributors {
            val authorId = "$id-author"
            return BookWithContributors(
                book =
                    BookEntity(
                        id = BookId(id),
                        libraryId = LibraryId("lib1"),
                        folderId = FolderId("folder1"),
                        title = title,
                        totalDuration = durationMs,
                        createdAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                contributors =
                    listOf(
                        ContributorEntity(
                            id = ContributorId(authorId),
                            name = author,
                            description = null,
                            imagePath = null,
                            createdAt = Timestamp(0L),
                            updatedAt = Timestamp(0L),
                        ),
                    ),
                contributorRoles =
                    listOf(
                        BookContributorCrossRef(
                            bookId = BookId(id),
                            contributorId = ContributorId(authorId),
                            role = "author",
                        ),
                    ),
                series = emptyList(),
                seriesSequences = emptyList(),
            )
        }

        fun searchHit(
            id: String,
            name: String = "Book $id",
        ) = SearchHit(
            id = id,
            type = SearchHitType.BOOK,
            name = name,
        )

        fun searchResult(vararg hits: SearchHit) =
            SearchResult(
                query = "dune",
                total = hits.size,
                tookMs = 1L,
                hits = hits.toList(),
                facets = SearchFacets(),
            )

        class Fixture(
            systemCollection: Boolean = false,
        ) {
            val repo: CollectionRepository = mock()
            val adminRepo: AdminRepository = mock()
            val userRepo: UserRepository = mock()
            val userProfileRepo: UserProfileRepository = mock()
            val bookDao: BookDao = mock()
            val searchRepo: SearchRepository = mock()
            val imageStorage: ImageStorage = mock()
            val errorBus = ErrorBus()
            val collectionsFlow = MutableStateFlow(listOf(collection(isSystem = systemCollection)))
            val booksFlow = MutableStateFlow<List<String>>(emptyList())
            val sharesFlow = MutableStateFlow<List<CollectionShare>>(emptyList())

            init {
                every { bookDao.observeByIdsWithContributors(any()) } returns flowOf(emptyList())
                every { imageStorage.exists(any()) } returns false
                every { imageStorage.getCoverPath(any()) } returns ""
                every { userProfileRepo.observeProfile(any()) } returns flowOf(null)
            }

            fun build(): AdminCollectionDetailViewModel {
                every { repo.observeCollections() } returns collectionsFlow
                every { repo.observeCollectionBooks("c1") } returns booksFlow
                every { repo.observeShares("c1") } returns sharesFlow
                return AdminCollectionDetailViewModel(
                    "c1",
                    repo,
                    adminRepo,
                    userRepo,
                    userProfileRepo,
                    bookDao,
                    searchRepo,
                    imageStorage,
                    errorBus,
                )
            }
        }

        test("openAddBooks is a no-op for system collections") {
            runTest(dispatcher) {
                val f = Fixture(systemCollection = true)
                val vm = f.build()
                advanceUntilIdle()

                vm.openAddBooks()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.showAddBooks shouldBe false
            }
        }

        test("openAddBooks sets showAddBooks for non-system collections") {
            runTest(dispatcher) {
                val f = Fixture(systemCollection = false)
                val vm = f.build()
                advanceUntilIdle()

                vm.openAddBooks()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.showAddBooks shouldBe true
            }
        }

        test("book search excludes books already in the collection") {
            runTest(dispatcher) {
                val f = Fixture()
                // Seed the collection with book "b-member" already in it
                f.booksFlow.value = listOf("b-member")
                every { f.bookDao.observeByIdsWithContributors(any()) } returns
                    flowOf(listOf(bookWith(id = "b-member", title = "Dune", author = "Frank Herbert")))
                val vm = f.build()
                advanceUntilIdle()

                // Search returns two hits: one that's a member, one that's not
                everySuspend {
                    f.searchRepo.search(
                        query = "dune",
                        types = listOf(SearchHitType.BOOK),
                        limit = 20,
                    )
                } returns searchResult(searchHit("b-member", "Dune"), searchHit("b-new", "Dune Messiah"))

                vm.openAddBooks()
                vm.onBookQueryChange("dune")
                // Advance past the 300ms debounce
                advanceTimeBy(301L)
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                // Only the non-member hit should appear
                ready.bookResults.map { it.id } shouldBe listOf("b-new")
                ready.isSearchingBooks shouldBe false
            }
        }

        test("book search failure surfaces on errorBus and collapses results to empty") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend {
                    f.searchRepo.search(
                        query = "dune",
                        types = listOf(SearchHitType.BOOK),
                        limit = 20,
                    )
                } throws Exception("Network error")
                val vm = f.build()
                advanceUntilIdle()

                f.errorBus.errors.test {
                    vm.openAddBooks()
                    vm.onBookQueryChange("dune")
                    advanceTimeBy(301L)
                    advanceUntilIdle()

                    // The failure is surfaced — not swallowed as "no results".
                    awaitItem()
                    cancelAndIgnoreRemainingEvents()
                }

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.bookResults shouldBe emptyList()
                ready.isSearchingBooks shouldBe false
            }
        }

        test("addBookFromSearch dispatches addBook and drops the hit on success") {
            runTest(dispatcher) {
                val f = Fixture()
                f.booksFlow.value = listOf("b-existing")
                everySuspend {
                    f.searchRepo.search(
                        query = "dune",
                        types = listOf(SearchHitType.BOOK),
                        limit = 20,
                    )
                } returns searchResult(searchHit("b-new", "Dune Messiah"))
                everySuspend { f.repo.addBook("c1", "b-new") } returns AppResult.Success(Unit)

                val vm = f.build()
                advanceUntilIdle()

                vm.openAddBooks()
                vm.onBookQueryChange("dune")
                advanceTimeBy(301L)
                advanceUntilIdle()

                // Confirm the hit is present before adding
                val before = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                before.bookResults.map { it.id } shouldBe listOf("b-new")

                vm.addBookFromSearch("b-new")
                advanceUntilIdle()

                // Hit should be removed from results
                val after = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                after.bookResults.map { it.id } shouldBe emptyList()

                // And the repo method was called
                verifySuspend { f.repo.addBook("c1", "b-new") }
            }
        }

        test("addBookFromSearch surfaces error on failure") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.repo.addBook("c1", "b-fail") } returns
                    AppResult.Failure(ValidationError(message = "book not found"))

                val vm = f.build()
                advanceUntilIdle()

                vm.addBookFromSearch("b-fail")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.error shouldBe "book not found"
            }
        }

        test("closeAddBooks resets search state") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend {
                    f.searchRepo.search(
                        query = "dune",
                        types = listOf(SearchHitType.BOOK),
                        limit = 20,
                    )
                } returns searchResult(searchHit("b-new", "Dune Messiah"))

                val vm = f.build()
                advanceUntilIdle()

                vm.openAddBooks()
                vm.onBookQueryChange("dune")
                advanceTimeBy(301L)
                advanceUntilIdle()

                vm.closeAddBooks()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.showAddBooks shouldBe false
                ready.bookQuery shouldBe ""
                ready.bookResults shouldBe emptyList()
                ready.isSearchingBooks shouldBe false
            }
        }
    })
