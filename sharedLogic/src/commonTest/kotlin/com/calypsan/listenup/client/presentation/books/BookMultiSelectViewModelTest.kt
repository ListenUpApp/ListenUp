package com.calypsan.listenup.client.presentation.books

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.collection.AddBooksToCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.CreateCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.core.ShelfId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
 * Tests for [BookMultiSelectViewModel].
 *
 * Selection state is owned by the VM itself (no shared manager); [addSelectedToCollection]
 * delegates to [AddBooksToCollectionUseCase] with the currently-selected ids.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookMultiSelectViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        class TestFixture {
            val userRepository: UserRepository = mock()
            val collectionRepository: CollectionRepository = mock()
            val shelfRepository: ShelfRepository = mock()
            val addBooksToShelfUseCase: AddBooksToShelfUseCase = mock()
            val addBooksToCollectionUseCase: AddBooksToCollectionUseCase = mock()
            val createShelfUseCase: CreateShelfUseCase = mock()
            val createCollectionUseCase: CreateCollectionUseCase = mock()
            val errorBus = ErrorBus()

            val userFlow = MutableStateFlow<User?>(null)
            val collectionsFlow = MutableStateFlow<List<Collection>>(emptyList())
            val shelvesFlow = MutableStateFlow<List<Shelf>>(emptyList())

            fun build(): BookMultiSelectViewModel =
                BookMultiSelectViewModel(
                    userRepository = userRepository,
                    collectionRepository = collectionRepository,
                    shelfRepository = shelfRepository,
                    addBooksToShelfUseCase = addBooksToShelfUseCase,
                    addBooksToCollectionUseCase = addBooksToCollectionUseCase,
                    createShelfUseCase = createShelfUseCase,
                    createCollectionUseCase = createCollectionUseCase,
                    errorBus = errorBus,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()
            every { fixture.userRepository.observeCurrentUser() } returns fixture.userFlow
            every { fixture.collectionRepository.observeCollections() } returns fixture.collectionsFlow
            every { fixture.shelfRepository.observeMyShelves(any()) } returns fixture.shelvesFlow
            return fixture
        }

        fun createUser(
            id: String = "user-1",
            isAdmin: Boolean = false,
        ): User =
            User(
                id = UserId(id),
                email = "test@example.com",
                displayName = "Test User",
                isAdmin = isAdmin,
                createdAtMs = 1704067200000L,
                updatedAtMs = 1704067200000L,
            )

        fun createShelf(
            id: String = "shelf-1",
            name: String = "My Shelf",
        ): Shelf =
            Shelf(
                id = ShelfId(id),
                name = name,
                description = "",
                isPrivate = false,
                ownerId = "user-1",
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

        // ========== Selection Mode ==========

        test("selectionMode is None initially") {
            runTest {
                val viewModel = createFixture().build()
                advanceUntilIdle()
                viewModel.selectionMode.value shouldBe SelectionMode.None
            }
        }

        test("enterSelectionMode (no-arg) activates with an empty selection") {
            runTest {
                val viewModel = createFixture().build()
                viewModel.enterSelectionMode()
                val mode = viewModel.selectionMode.value.shouldBeInstanceOf<SelectionMode.Active>()
                mode.selectedIds.shouldBeEmpty()
            }
        }

        test("enterSelectionMode (no-arg) then toggleSelection builds the first selection") {
            runTest {
                val viewModel = createFixture().build()
                viewModel.enterSelectionMode()
                viewModel.toggleSelection("book-1")
                val mode = viewModel.selectionMode.value.shouldBeInstanceOf<SelectionMode.Active>()
                mode.selectedIds shouldBe setOf("book-1")
            }
        }

        test("enterSelectionMode activates with the initial book") {
            runTest {
                val viewModel = createFixture().build()
                viewModel.enterSelectionMode("book-1")
                val mode = viewModel.selectionMode.value.shouldBeInstanceOf<SelectionMode.Active>()
                mode.selectedIds shouldBe setOf("book-1")
            }
        }

        test("toggleSelection adds a second book") {
            runTest {
                val viewModel = createFixture().build()
                viewModel.enterSelectionMode("book-1")
                viewModel.toggleSelection("book-2")
                val mode = viewModel.selectionMode.value.shouldBeInstanceOf<SelectionMode.Active>()
                mode.selectedIds shouldBe setOf("book-1", "book-2")
            }
        }

        test("toggleSelection exits selection mode when the last book is deselected") {
            runTest {
                val viewModel = createFixture().build()
                viewModel.enterSelectionMode("book-1")
                viewModel.toggleSelection("book-1")
                viewModel.selectionMode.value shouldBe SelectionMode.None
            }
        }

        test("exitSelectionMode clears selection") {
            runTest {
                val viewModel = createFixture().build()
                viewModel.enterSelectionMode("book-1")
                viewModel.exitSelectionMode()
                viewModel.selectionMode.value shouldBe SelectionMode.None
            }
        }

        // ========== isAdmin ==========

        test("isAdmin reflects the current user admin flag") {
            runTest {
                val fixture = createFixture()
                fixture.userFlow.value = createUser(isAdmin = true)
                val viewModel = fixture.build()

                viewModel.isAdmin.test {
                    skipItems(1) // initialValue = false
                    awaitItem() shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ========== addSelectedToShelf ==========

        test("addSelectedToShelf does nothing when no books selected") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.addSelectedToShelf("shelf-1")
                advanceUntilIdle()

                viewModel.isAddingToShelf.value shouldBe false
                verifySuspend(mode = VerifyMode.not) { fixture.addBooksToShelfUseCase(any(), any()) }
            }
        }

        test("addSelectedToShelf success clears selection and emits event") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                viewModel.enterSelectionMode("book-1")
                everySuspend { fixture.addBooksToShelfUseCase(any(), any()) } returns AppResult.Success(Unit)
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.addSelectedToShelf("shelf-1")
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<BookMultiSelectEvent.BooksAddedToShelf>()
                }

                viewModel.selectionMode.value shouldBe SelectionMode.None
                verifySuspend { fixture.addBooksToShelfUseCase(ShelfId("shelf-1"), any()) }
            }
        }

        test("addSelectedToShelf failure surfaces on errorBus, emits no event, keeps selection") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                viewModel.enterSelectionMode("book-1")
                val error = ValidationError(message = "Server error")
                everySuspend { fixture.addBooksToShelfUseCase(any(), any()) } returns AppResult.Failure(error)

                // Capture the success-only events channel to prove no event fires on failure.
                val events = mutableListOf<BookMultiSelectEvent>()
                backgroundScope.launch { viewModel.events.collect { events += it } }

                fixture.errorBus.errors.test {
                    viewModel.addSelectedToShelf("shelf-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe error
                }

                events.shouldBeEmpty()
                viewModel.selectionMode.value.shouldBeInstanceOf<SelectionMode.Active>()
            }
        }

        // ========== addSelectedToCollection ==========

        test("addSelectedToCollection does nothing when no books selected") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.addSelectedToCollection("collection-1")
                advanceUntilIdle()

                viewModel.isAddingToCollection.value shouldBe false
                verifySuspend(mode = VerifyMode.not) { fixture.addBooksToCollectionUseCase(any(), any()) }
            }
        }

        test("addSelectedToCollection success clears selection and emits event") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                viewModel.enterSelectionMode("book-1")
                everySuspend { fixture.addBooksToCollectionUseCase(any(), any()) } returns AppResult.Success(Unit)
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.addSelectedToCollection("collection-1")
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<BookMultiSelectEvent.BooksAddedToCollection>()
                }

                viewModel.selectionMode.value shouldBe SelectionMode.None
            }
        }

        test("addSelectedToCollection delegates to AddBooksToCollectionUseCase with selected ids then clears") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                viewModel.enterSelectionMode("book-1")
                everySuspend { fixture.addBooksToCollectionUseCase(any(), any()) } returns AppResult.Success(Unit)
                advanceUntilIdle()

                viewModel.addSelectedToCollection("collection-1")
                advanceUntilIdle()

                verifySuspend { fixture.addBooksToCollectionUseCase("collection-1", listOf("book-1")) }
                viewModel.selectionMode.value shouldBe SelectionMode.None
            }
        }

        test("addSelectedToCollection failure surfaces on errorBus, emits no event, keeps selection") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                viewModel.enterSelectionMode("book-1")
                val error = ValidationError(message = "Network error")
                everySuspend { fixture.addBooksToCollectionUseCase(any(), any()) } returns AppResult.Failure(error)

                // Capture the success-only events channel to prove no event fires on failure.
                val events = mutableListOf<BookMultiSelectEvent>()
                backgroundScope.launch { viewModel.events.collect { events += it } }

                fixture.errorBus.errors.test {
                    viewModel.addSelectedToCollection("collection-1")
                    advanceUntilIdle()
                    awaitItem() shouldBe error
                }

                events.shouldBeEmpty()
                viewModel.selectionMode.value.shouldBeInstanceOf<SelectionMode.Active>()
            }
        }

        // ========== createShelfAndAddBooks ==========

        test("createShelfAndAddBooks does nothing when no books selected") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.createShelfAndAddBooks("New Shelf")
                advanceUntilIdle()

                viewModel.isAddingToShelf.value shouldBe false
                verifySuspend(mode = VerifyMode.not) { fixture.createShelfUseCase(any(), any()) }
                verifySuspend(mode = VerifyMode.not) { fixture.addBooksToShelfUseCase(any(), any()) }
            }
        }

        test("createShelfAndAddBooks creates the shelf, adds the books, emits event, clears selection") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                viewModel.enterSelectionMode("book-1")
                val newShelf = createShelf(id = "new-shelf", name = "My New Shelf")
                everySuspend { fixture.createShelfUseCase(any(), any()) } returns AppResult.Success(newShelf)
                everySuspend { fixture.addBooksToShelfUseCase(any(), any()) } returns AppResult.Success(Unit)
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.createShelfAndAddBooks("My New Shelf")
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<BookMultiSelectEvent.ShelfCreatedAndBooksAdded>()
                }

                verifySuspend { fixture.createShelfUseCase("My New Shelf", null) }
                verifySuspend { fixture.addBooksToShelfUseCase(ShelfId("new-shelf"), any()) }
                viewModel.selectionMode.value shouldBe SelectionMode.None
            }
        }

        test("createShelfAndAddBooks shelf-creation failure surfaces on errorBus, no event, keeps selection, never adds") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                viewModel.enterSelectionMode("book-1")
                val error = ValidationError(message = "Failed to create shelf")
                everySuspend { fixture.createShelfUseCase(any(), any()) } returns AppResult.Failure(error)

                // Capture the success-only events channel to prove no event fires on failure.
                val events = mutableListOf<BookMultiSelectEvent>()
                backgroundScope.launch { viewModel.events.collect { events += it } }

                fixture.errorBus.errors.test {
                    viewModel.createShelfAndAddBooks("New Shelf")
                    advanceUntilIdle()
                    awaitItem() shouldBe error
                }

                events.shouldBeEmpty()
                viewModel.selectionMode.value.shouldBeInstanceOf<SelectionMode.Active>()
                verifySuspend(mode = VerifyMode.not) { fixture.addBooksToShelfUseCase(any(), any()) }
            }
        }

        test("createShelfAndAddBooks add-books failure surfaces on errorBus, no event, keeps selection") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                viewModel.enterSelectionMode("book-1")
                val newShelf = createShelf(id = "new-shelf", name = "My New Shelf")
                val error = ValidationError(message = "Failed to add books")
                everySuspend { fixture.createShelfUseCase(any(), any()) } returns AppResult.Success(newShelf)
                everySuspend { fixture.addBooksToShelfUseCase(any(), any()) } returns AppResult.Failure(error)

                // Capture the success-only events channel to prove no event fires on failure.
                val events = mutableListOf<BookMultiSelectEvent>()
                backgroundScope.launch { viewModel.events.collect { events += it } }

                fixture.errorBus.errors.test {
                    viewModel.createShelfAndAddBooks("My New Shelf")
                    advanceUntilIdle()
                    awaitItem() shouldBe error
                }

                events.shouldBeEmpty()
                viewModel.selectionMode.value.shouldBeInstanceOf<SelectionMode.Active>()
            }
        }

        // ========== createCollectionAndAddBooks ==========

        fun createCollection(
            id: String = "collection-1",
            name: String = "My Collection",
        ): Collection =
            Collection(
                id = id,
                name = name,
                ownerId = "user-1",
                isInbox = false,
                isSystem = false,
                bookCount = 0,
                callerPermission = SharePermission.Write,
                isOwner = true,
            )

        test("createCollectionAndAddBooks does nothing when no books selected") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.createCollectionAndAddBooks("New Collection")
                advanceUntilIdle()

                viewModel.isAddingToCollection.value shouldBe false
                verifySuspend(mode = VerifyMode.not) { fixture.createCollectionUseCase(any()) }
                verifySuspend(mode = VerifyMode.not) { fixture.addBooksToCollectionUseCase(any(), any()) }
            }
        }

        test("createCollectionAndAddBooks creates the collection, adds the books, emits event, clears selection") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                viewModel.enterSelectionMode("book-1")
                val newCollection = createCollection(id = "new-collection", name = "My New Collection")
                everySuspend { fixture.createCollectionUseCase(any()) } returns AppResult.Success(newCollection)
                everySuspend { fixture.addBooksToCollectionUseCase(any(), any()) } returns AppResult.Success(Unit)
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.createCollectionAndAddBooks("My New Collection")
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<BookMultiSelectEvent.CollectionCreatedAndBooksAdded>()
                }

                verifySuspend { fixture.createCollectionUseCase("My New Collection") }
                verifySuspend { fixture.addBooksToCollectionUseCase("new-collection", listOf("book-1")) }
                viewModel.selectionMode.value shouldBe SelectionMode.None
            }
        }

        test("createCollectionAndAddBooks creation failure surfaces on errorBus, no event, keeps selection, never adds") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                viewModel.enterSelectionMode("book-1")
                val error = ValidationError(message = "Failed to create collection")
                everySuspend { fixture.createCollectionUseCase(any()) } returns AppResult.Failure(error)

                // Capture the success-only events channel to prove no event fires on failure.
                val events = mutableListOf<BookMultiSelectEvent>()
                backgroundScope.launch { viewModel.events.collect { events += it } }

                fixture.errorBus.errors.test {
                    viewModel.createCollectionAndAddBooks("New Collection")
                    advanceUntilIdle()
                    awaitItem() shouldBe error
                }

                events.shouldBeEmpty()
                viewModel.selectionMode.value.shouldBeInstanceOf<SelectionMode.Active>()
                verifySuspend(mode = VerifyMode.not) { fixture.addBooksToCollectionUseCase(any(), any()) }
            }
        }

        test("createCollectionAndAddBooks add-books failure surfaces on errorBus, no event, keeps selection") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                viewModel.enterSelectionMode("book-1")
                val newCollection = createCollection(id = "new-collection", name = "My New Collection")
                val error = ValidationError(message = "Failed to add books")
                everySuspend { fixture.createCollectionUseCase(any()) } returns AppResult.Success(newCollection)
                everySuspend { fixture.addBooksToCollectionUseCase(any(), any()) } returns AppResult.Failure(error)

                // Capture the success-only events channel to prove no event fires on failure.
                val events = mutableListOf<BookMultiSelectEvent>()
                backgroundScope.launch { viewModel.events.collect { events += it } }

                fixture.errorBus.errors.test {
                    viewModel.createCollectionAndAddBooks("My New Collection")
                    advanceUntilIdle()
                    awaitItem() shouldBe error
                }

                events.shouldBeEmpty()
                viewModel.selectionMode.value.shouldBeInstanceOf<SelectionMode.Active>()
            }
        }

        // ========== collections (system filtering) ==========

        test("collections excludes system collections from the picker") {
            runTest {
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
                val normalCollection =
                    Collection(
                        id = "my-col",
                        name = "My Collection",
                        ownerId = "user-1",
                        isInbox = false,
                        isSystem = false,
                        bookCount = 0,
                        callerPermission = SharePermission.Write,
                        isOwner = true,
                    )
                fixture.collectionsFlow.value = listOf(systemCollection, normalCollection)
                val viewModel = fixture.build()

                viewModel.collections.test {
                    skipItems(1) // initialValue = emptyList()
                    awaitItem().map { it.id } shouldContainExactly listOf("my-col")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
