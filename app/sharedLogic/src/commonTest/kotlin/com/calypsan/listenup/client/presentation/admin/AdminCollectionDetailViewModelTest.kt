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
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [AdminCollectionDetailViewModel] (Collections-2a).
 *
 * The collection, its book ids, and its shares are observed reactively from
 * [CollectionRepository]; rename / remove-book / share / revoke-share dispatch to the
 * repository, with failures surfacing on [ErrorBus] plus a transient `error`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminCollectionDetailViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeSpec { Dispatchers.setMain(dispatcher) }
        afterSpec { Dispatchers.resetMain() }

        fun collection(
            id: String = "c1",
            name: String = "Favorites",
        ) = Collection(
            id = id,
            name = name,
            ownerId = "owner1",
            isInbox = false,
            isSystem = false,
            bookCount = 0,
            callerPermission = SharePermission.Write,
            isOwner = true,
        )

        // Builds a BookWithContributors with a single author for hydration tests.
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

        class Fixture {
            val repo: CollectionRepository = mock()
            val adminRepo: AdminRepository = mock()
            val userRepo: UserRepository = mock()
            val userProfileRepo: UserProfileRepository = mock()
            val bookDao: BookDao = mock()
            val searchRepo: SearchRepository = mock()
            val imageStorage: ImageStorage = mock()
            val collectionsFlow = MutableStateFlow(listOf(collection()))
            val booksFlow = MutableStateFlow<List<String>>(emptyList())
            val sharesFlow = MutableStateFlow<List<CollectionShare>>(emptyList())

            init {
                // Default: no hydrated books (overridden per-test for hydration cases).
                every { bookDao.observeByIdsWithContributors(any()) } returns flowOf(emptyList())
                every { imageStorage.exists(any()) } returns false
                every { imageStorage.getCoverPath(any()) } returns ""
                // Default: no cached profile → share-name resolution falls back to the user id.
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
                    ErrorBus(),
                )
            }
        }

        test("Ready emitted with collection, books, and shares") {
            runTest(dispatcher) {
                val f = Fixture()
                f.booksFlow.value = listOf("b1", "b2")
                every { f.bookDao.observeByIdsWithContributors(any()) } returns
                    flowOf(
                        listOf(
                            bookWith(id = "b1", title = "The Way of Kings", author = "Brandon Sanderson"),
                            bookWith(id = "b2", title = "Mistborn", author = "Brandon Sanderson"),
                        ),
                    )
                f.sharesFlow.value = listOf(CollectionShare("s1", "c1", "u1", SharePermission.Read))
                val vm = f.build()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.collection.id shouldBe "c1"
                ready.books.map { it.id } shouldBe listOf("b1", "b2")
                ready.shares.map { it.userId } shouldBe listOf("u1")
            }
        }

        // Regression: a collection member must show their name, not their UUID. A share record carries
        // only the user id; the VM resolves the display name from the public_profiles mirror.
        test("share row shows the member's resolved display name, not the raw user id") {
            runTest(dispatcher) {
                val f = Fixture()
                every { f.userProfileRepo.observeProfile("u1") } returns
                    flowOf(CachedUserProfile(id = "u1", displayName = "Alice Reader", avatarType = "initials", updatedAt = 0L))
                f.sharesFlow.value = listOf(CollectionShare("s1", "c1", "u1", SharePermission.Read))

                val vm = f.build()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.shares.first().displayName shouldBe "Alice Reader"
                ready.shares.first().userId shouldBe "u1"
            }
        }

        // Never-stranded fallback: before the profile row has synced (or if the name is blank), the id
        // is shown rather than nothing — but the resolution path still runs.
        test("share row falls back to the user id when no cached profile exists yet") {
            runTest(dispatcher) {
                val f = Fixture() // default stub: observeProfile → null
                f.sharesFlow.value = listOf(CollectionShare("s1", "c1", "u-unsynced", SharePermission.Read))

                val vm = f.build()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.shares.first().displayName shouldBe "u-unsynced"
            }
        }

        test("Ready hydrates collection book ids into CollectionBookItems via BookDao") {
            runTest(dispatcher) {
                val f = Fixture()
                f.booksFlow.value = listOf("b1", "b2")
                every { f.bookDao.observeByIdsWithContributors(any()) } returns
                    flowOf(
                        listOf(
                            bookWith(id = "b1", title = "The Way of Kings", author = "Brandon Sanderson"),
                            bookWith(id = "b2", title = "Mistborn", author = "Brandon Sanderson"),
                        ),
                    )
                val vm = f.build()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.books.map { it.id } shouldBe listOf("b1", "b2")
                val first = ready.books.first { it.id == "b1" }
                first.title shouldBe "The Way of Kings"
                first.author shouldBe "Brandon Sanderson"
                first.durationMs shouldBe 3_600_000L
            }
        }

        test("collection ids with no Room row are omitted from the hydrated books") {
            runTest(dispatcher) {
                val f = Fixture()
                f.booksFlow.value = listOf("b1", "b2")
                // Only b1 has synced into Room; b2 is a collection id with no Room row yet.
                every { f.bookDao.observeByIdsWithContributors(any()) } returns
                    flowOf(listOf(bookWith(id = "b1", title = "The Way of Kings", author = "Brandon Sanderson")))
                val vm = f.build()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                // The collection still knows about both ids…
                ready.collection.id shouldBe "c1"
                // …but only the hydrated row appears in the books list.
                ready.books.map { it.id } shouldBe listOf("b1")
            }
        }

        test("missing collection yields Error") {
            runTest(dispatcher) {
                val f = Fixture()
                f.collectionsFlow.value = emptyList()
                val vm = f.build()
                advanceUntilIdle()
                vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Error>()
            }
        }

        test("saveName dispatches rename and sets saveSuccess") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.repo.rename("c1", "Renamed") } returns AppResult.Success(collection(name = "Renamed"))
                val vm = f.build()
                advanceUntilIdle()
                vm.updateName("Renamed")
                vm.saveName()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.saveSuccess shouldBe true
                ready.isSaving shouldBe false
            }
        }

        test("saveName failure surfaces a transient error") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.repo.rename(any(), any()) } returns
                    AppResult.Failure(ValidationError(message = "name taken"))
                val vm = f.build()
                advanceUntilIdle()
                vm.updateName("Renamed")
                vm.saveName()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.error shouldBe "name taken"
                ready.isSaving shouldBe false
            }
        }

        test("removeBook dispatches and clears the overlay") {
            runTest(dispatcher) {
                val f = Fixture()
                f.booksFlow.value = listOf("b1")
                everySuspend { f.repo.removeBook("c1", "b1") } returns AppResult.Success(Unit)
                val vm = f.build()
                advanceUntilIdle()
                vm.removeBook("b1")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.removingBookId shouldBe null
            }
        }

        test("revokeShare dispatches and clears the overlay") {
            runTest(dispatcher) {
                val f = Fixture()
                f.sharesFlow.value = listOf(CollectionShare("s1", "c1", "u1", SharePermission.Read))
                everySuspend { f.repo.revokeShare("c1", "u1") } returns AppResult.Success(Unit)
                val vm = f.build()
                advanceUntilIdle()
                vm.revokeShare("u1")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.removingShareUserId shouldBe null
            }
        }

        test("shareWithUser dispatches share at read permission and closes the sheet") {
            runTest(dispatcher) {
                val f = Fixture()
                everySuspend { f.repo.share("c1", "u2", SharePermission.Read) } returns
                    AppResult.Success(CollectionShare("s2", "c1", "u2", SharePermission.Read))
                val vm = f.build()
                advanceUntilIdle()
                vm.shareWithUser("u2")
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<AdminCollectionDetailUiState.Ready>()
                ready.isSharing shouldBe false
                ready.showAddMemberSheet shouldBe false
            }
        }
    })
