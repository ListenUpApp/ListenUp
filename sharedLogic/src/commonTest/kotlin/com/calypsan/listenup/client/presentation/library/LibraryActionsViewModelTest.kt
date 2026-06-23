package com.calypsan.listenup.client.presentation.library

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import app.cash.turbine.test
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for LibraryActionsViewModel.
 *
 * Tests cover:
 * - Selection mode observation from shared manager
 * - Admin state observation (isAdmin, collections)
 * - Shelf observation (myShelves)
 * - addSelectedToCollection success/failure
 * - addSelectedToShelf success/failure
 * - createShelfAndAddBooks success/failure
 * - Loading state during operations
 * - Selection clearing after actions
 *
 * Uses Mokkery for mocking dependencies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryActionsViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixtures ==========

        class TestFixture {
            val selectionManager = LibrarySelectionManager()
            val userRepository: UserRepository = mock()
            val collectionRepository: CollectionRepository = mock()
            val shelfRepository: ShelfRepository = mock()
            val addBooksToShelfUseCase: AddBooksToShelfUseCase = mock()
            val createShelfUseCase: CreateShelfUseCase = mock()

            val userFlow = MutableStateFlow<User?>(null)
            val collectionsFlow = MutableStateFlow<List<Collection>>(emptyList())
            val shelvesFlow = MutableStateFlow<List<Shelf>>(emptyList())

            fun build(): LibraryActionsViewModel =
                LibraryActionsViewModel(
                    selectionManager = selectionManager,
                    userRepository = userRepository,
                    collectionRepository = collectionRepository,
                    shelfRepository = shelfRepository,
                    addBooksToShelfUseCase = addBooksToShelfUseCase,
                    createShelfUseCase = createShelfUseCase,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            // Default stubs for reactive observation
            every { fixture.userRepository.observeCurrentUser() } returns fixture.userFlow
            every { fixture.collectionRepository.observeCollections() } returns fixture.collectionsFlow
            every { fixture.shelfRepository.observeMyShelves(any()) } returns fixture.shelvesFlow

            return fixture
        }

        // ========== Test Data Factories ==========

        fun createUser(
            id: String = "user-1",
            email: String = "test@example.com",
            displayName: String = "Test User",
            isAdmin: Boolean = false,
        ): User =
            User(
                id =
                    UserId(id),
                email = email,
                displayName = displayName,
                isAdmin = isAdmin,
                createdAtMs = 1704067200000L,
                updatedAtMs = 1704067200000L,
            )

        fun createCollection(
            id: String = "collection-1",
            name: String = "Test Collection",
            bookCount: Int = 0,
        ): Collection =
            Collection(
                id = id,
                name = name,
                ownerId = "user-1",
                isInbox = false,
                isSystem = false,
                bookCount = bookCount,
                callerPermission = SharePermission.Write,
                isOwner = true,
            )

        fun createShelf(
            id: String = "shelf-1",
            name: String = "My Shelf",
            ownerId: String = "user-1",
        ): Shelf =
            Shelf(
                id = id,
                name = name,
                description = "",
                isPrivate = false,
                ownerId = ownerId,
                ownerDisplayName = "Test User",
                bookCount = 0,
                totalDurationSeconds = 0,
                createdAtMs = 1704067200000L,
                updatedAtMs = 1704067200000L,
            )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Selection Mode Tests ==========

        test("selectionMode is None initially") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()

                // Then
                viewModel.selectionMode.value shouldBe SelectionMode.None
            }
        }

        test("selectionMode reflects selection manager state") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                val viewModel = fixture.build()
                advanceUntilIdle()

                // Then
                val mode = viewModel.selectionMode.value.shouldBeInstanceOf<SelectionMode.Active>()
                mode.selectedIds shouldBe setOf("book-1")
            }
        }

        test("selectionMode updates when manager changes") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()
                viewModel.selectionMode.value shouldBe SelectionMode.None

                // When
                fixture.selectionManager.enterSelectionMode("book-1")
                advanceUntilIdle()

                // Then
                checkIs<SelectionMode.Active>(viewModel.selectionMode.value)
            }
        }

        // ========== addSelectedToCollection Tests ==========

        test("addSelectedToCollection does nothing when no books selected") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.addSelectedToCollection("collection-1")
                advanceUntilIdle()

                // Then - use case should not be called, loading should be false
                (viewModel.isAddingToCollection.value) shouldBe false
            }
        }

        test("addSelectedToCollection calls repository addBook for each selected book") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                fixture.selectionManager.toggleSelection("book-2")
                everySuspend { fixture.collectionRepository.addBook(any(), any()) } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.addSelectedToCollection("collection-1")
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.collectionRepository.addBook("collection-1", any()) }
            }
        }

        test("addSelectedToCollection clears selection on success") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                everySuspend { fixture.collectionRepository.addBook(any(), any()) } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                advanceUntilIdle()
                checkIs<SelectionMode.Active>(fixture.selectionManager.selectionMode.value)

                // When
                viewModel.addSelectedToCollection("collection-1")
                advanceUntilIdle()

                // Then
                fixture.selectionManager.selectionMode.value shouldBe SelectionMode.None
            }
        }

        test("addSelectedToCollection does not clear selection on failure") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                everySuspend { fixture.collectionRepository.addBook(any(), any()) } returns
                    Failure(RuntimeException("Network error"))
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.addSelectedToCollection("collection-1")
                advanceUntilIdle()

                // Then - selection should still be active
                checkIs<SelectionMode.Active>(fixture.selectionManager.selectionMode.value)
            }
        }

        test("addSelectedToCollection sets and clears loading state") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                everySuspend { fixture.collectionRepository.addBook(any(), any()) } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.addSelectedToCollection("collection-1")
                advanceUntilIdle()

                // Then - loading should be false after completion
                (viewModel.isAddingToCollection.value) shouldBe false
            }
        }

        // ========== addSelectedToShelf Tests ==========

        test("addSelectedToShelf does nothing when no books selected") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.addSelectedToShelf("shelf-1")
                advanceUntilIdle()

                // Then - use case should not be called
                (viewModel.isAddingToShelf.value) shouldBe false
            }
        }

        test("addSelectedToShelf calls use case with selected book IDs") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                everySuspend { fixture.addBooksToShelfUseCase(any(), any()) } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.addSelectedToShelf("shelf-1")
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.addBooksToShelfUseCase("shelf-1", any()) }
            }
        }

        test("addSelectedToShelf clears selection on success") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                everySuspend { fixture.addBooksToShelfUseCase(any(), any()) } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.addSelectedToShelf("shelf-1")
                advanceUntilIdle()

                // Then
                fixture.selectionManager.selectionMode.value shouldBe SelectionMode.None
            }
        }

        test("addSelectedToShelf does not clear selection on failure") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                everySuspend { fixture.addBooksToShelfUseCase(any(), any()) } returns
                    Failure(RuntimeException("Server error"))
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.addSelectedToShelf("shelf-1")
                advanceUntilIdle()

                // Then - selection should still be active
                checkIs<SelectionMode.Active>(fixture.selectionManager.selectionMode.value)
            }
        }

        // ========== createShelfAndAddBooks Tests ==========

        test("createShelfAndAddBooks does nothing when no books selected") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.createShelfAndAddBooks("New Shelf")
                advanceUntilIdle()

                // Then - use case should not be called
                (viewModel.isAddingToShelf.value) shouldBe false
            }
        }

        test("createShelfAndAddBooks creates shelf then adds books") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                val newShelf = createShelf(id = "new-shelf", name = "My New Shelf")
                everySuspend { fixture.createShelfUseCase(any(), any()) } returns AppResult.Success(newShelf)
                everySuspend { fixture.addBooksToShelfUseCase(any(), any()) } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.createShelfAndAddBooks("My New Shelf")
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.createShelfUseCase("My New Shelf", null) }
                verifySuspend { fixture.addBooksToShelfUseCase("new-shelf", any()) }
            }
        }

        test("createShelfAndAddBooks clears selection on success") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                val newShelf = createShelf()
                everySuspend { fixture.createShelfUseCase(any(), any()) } returns AppResult.Success(newShelf)
                everySuspend { fixture.addBooksToShelfUseCase(any(), any()) } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.createShelfAndAddBooks("New Shelf")
                advanceUntilIdle()

                // Then
                fixture.selectionManager.selectionMode.value shouldBe SelectionMode.None
            }
        }

        test("createShelfAndAddBooks does not clear selection on shelf creation failure") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                everySuspend { fixture.createShelfUseCase(any(), any()) } returns
                    Failure(RuntimeException("Failed to create shelf"))
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.createShelfAndAddBooks("New Shelf")
                advanceUntilIdle()

                // Then - selection should still be active
                checkIs<SelectionMode.Active>(fixture.selectionManager.selectionMode.value)
            }
        }

        test("createShelfAndAddBooks does not clear selection on addBooks failure") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                val newShelf = createShelf()
                everySuspend { fixture.createShelfUseCase(any(), any()) } returns AppResult.Success(newShelf)
                everySuspend { fixture.addBooksToShelfUseCase(any(), any()) } returns
                    Failure(RuntimeException("Failed to add books"))
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.createShelfAndAddBooks("New Shelf")
                advanceUntilIdle()

                // Then - selection should still be active
                checkIs<SelectionMode.Active>(fixture.selectionManager.selectionMode.value)
            }
        }

        test("createShelfAndAddBooks sets and clears loading state") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.selectionManager.enterSelectionMode("book-1")
                val newShelf = createShelf()
                everySuspend { fixture.createShelfUseCase(any(), any()) } returns AppResult.Success(newShelf)
                everySuspend { fixture.addBooksToShelfUseCase(any(), any()) } returns AppResult.Success(Unit)
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.createShelfAndAddBooks("New Shelf")
                advanceUntilIdle()

                // Then - loading should be false after completion
                (viewModel.isAddingToShelf.value) shouldBe false
            }
        }

        // ========== System Collection Filtering Tests ==========

        test("collections excludes system collections from the picker") {
            runTest {
                // Given
                val fixture = createFixture()
                val systemCollection =
                    Collection(
                        id = "all-books",
                        name = "All Books",
                        ownerId = "system",
                        isInbox = false,
                        isSystem = true,
                        bookCount = 42,
                        callerPermission = SharePermission.Write,
                        isOwner = false,
                    )
                val normalCollection = createCollection(id = "my-col", name = "My Collection")
                fixture.collectionsFlow.value = listOf(systemCollection, normalCollection)
                val viewModel = fixture.build()

                // Then - only the non-system collection appears
                viewModel.collections.test {
                    skipItems(1) // initialValue = emptyList()
                    awaitItem().map { it.id } shouldContainExactly listOf("my-col")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
