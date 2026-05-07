package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.repository.CollectionBookSummary
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.CollectionShareSummary
import com.calypsan.listenup.client.domain.usecase.collection.GetUsersForSharingUseCase
import com.calypsan.listenup.client.domain.usecase.collection.LoadCollectionBooksUseCase
import com.calypsan.listenup.client.domain.usecase.collection.LoadCollectionSharesUseCase
import com.calypsan.listenup.client.domain.usecase.collection.RemoveBookFromCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.RemoveCollectionShareUseCase
import com.calypsan.listenup.client.domain.usecase.collection.ShareCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.UpdateCollectionNameUseCase
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.calypsan.listenup.client.core.error.ErrorBus

/**
 * Tests for AdminCollectionDetailViewModel.
 *
 * Tests cover:
 * - Initial `Loading` state before the load pipeline completes
 * - `Ready` emission on load success with collection/books/shares
 * - `Error` terminal state when the initial load throws
 * - Edit-buffer: `updateName` reflects on `Ready.editedName`
 * - `Ready.isDirty` derived property respects trimming and blanks
 * - `saveName` happy path: `isSaving` toggles, `saveSuccess` set, server data replaces `collection`
 * - `saveName` blank input: transient error, no repo call
 * - `saveName` unchanged input: sets `saveSuccess` without a repo call
 * - `saveName` failure: `isSaving` clears, `error` surfaces as transient snackbar data
 * - `removeBook` happy path: `removingBookId` overlay toggles, book filtered, count decremented
 * - `removeBook` failure: overlay clears, transient error set
 * - `shareWithUser` happy path: overlay toggles, enriched share appended, sheet closes
 * - `removeShare` happy path: overlay toggles, share filtered from list
 * - `clearError` / `clearSaveSuccess` reset transient flags
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminCollectionDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixtures ==========

    private class TestFixture {
        val collectionRepository: CollectionRepository = mock()
        val loadCollectionBooksUseCase: LoadCollectionBooksUseCase = mock()
        val loadCollectionSharesUseCase: LoadCollectionSharesUseCase = mock()
        val updateCollectionNameUseCase: UpdateCollectionNameUseCase = mock()
        val removeBookFromCollectionUseCase: RemoveBookFromCollectionUseCase = mock()
        val shareCollectionUseCase: ShareCollectionUseCase = mock()
        val removeCollectionShareUseCase: RemoveCollectionShareUseCase = mock()
        val getUsersForSharingUseCase: GetUsersForSharingUseCase = mock()

        fun build(collectionId: String = "c1"): AdminCollectionDetailViewModel =
            AdminCollectionDetailViewModel(
                collectionId = collectionId,
                collectionRepository = collectionRepository,
                loadCollectionBooksUseCase = loadCollectionBooksUseCase,
                loadCollectionSharesUseCase = loadCollectionSharesUseCase,
                updateCollectionNameUseCase = updateCollectionNameUseCase,
                removeBookFromCollectionUseCase = removeBookFromCollectionUseCase,
                shareCollectionUseCase = shareCollectionUseCase,
                removeCollectionShareUseCase = removeCollectionShareUseCase,
                getUsersForSharingUseCase = getUsersForSharingUseCase,
                errorBus = ErrorBus(),
            )
    }

    private fun createFixture(
        collection: Collection = createCollection(),
        books: List<CollectionBookSummary> = emptyList(),
        shares: List<CollectionShareSummary> = emptyList(),
    ): TestFixture {
        val fixture = TestFixture()
        everySuspend { fixture.collectionRepository.getById(collection.id) } returns collection
        everySuspend { fixture.loadCollectionBooksUseCase(collection.id) } returns Success(books)
        everySuspend { fixture.loadCollectionSharesUseCase(collection.id) } returns Success(shares)
        return fixture
    }

    // ========== Test Data Factories ==========

    private fun createCollection(
        id: String = "c1",
        name: String = "Original",
        bookCount: Int = 2,
    ): Collection =
        Collection(
            id = id,
            name = name,
            bookCount = bookCount,
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )

    private fun createBookSummary(
        id: String = "b1",
        title: String = "Book $id",
    ): CollectionBookSummary =
        CollectionBookSummary(
            id = id,
            title = title,
            coverPath = null,
        )

    private fun createShareSummary(
        id: String = "s1",
        userId: String = "u1",
        userName: String = "Alice",
        userEmail: String = "alice@example.com",
    ): CollectionShareSummary =
        CollectionShareSummary(
            id = id,
            userId = userId,
            userName = userName,
            userEmail = userEmail,
            permission = "view",
        )

    private fun createUser(
        id: String = "u2",
        email: String = "bob@example.com",
        displayName: String? = "Bob",
    ): AdminUserInfo =
        AdminUserInfo(
            id = id,
            email = email,
            displayName = displayName,
            firstName = null,
            lastName = null,
            isRoot = false,
            role = "user",
            status = "active",
            createdAt = "2026-04-16",
        )

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Initial State ==========

    @Test
    fun `initial state is Loading before load completes`() =
        runTest {
            val fixture = createFixture()

            val viewModel = fixture.build()

            // Before advancing, init-launched coroutine has not run yet.
            assertIs<AdminCollectionDetailUiState.Loading>(viewModel.state.value)
        }

    // ========== Load Pipeline ==========

    @Test
    fun `Ready emitted on load success with collection books and shares`() =
        runTest {
            val collection = createCollection(name = "My Collection", bookCount = 1)
            val book = createBookSummary(id = "b1", title = "Book One")
            val share = createShareSummary(id = "s1", userName = "Alice")
            val fixture =
                createFixture(
                    collection = collection,
                    books = listOf(book),
                    shares = listOf(share),
                )

            val viewModel = fixture.build()
            advanceUntilIdle()

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertEquals(collection, ready.collection)
            assertEquals("My Collection", ready.editedName)
            assertEquals(listOf(book.id), ready.books.map { it.id })
            assertEquals(listOf(share.id), ready.shares.map { it.id })
            assertFalse(ready.isSaving)
            assertFalse(ready.saveSuccess)
            assertNull(ready.removingBookId)
            assertNull(ready.error)
        }

    @Test
    fun `Ready falls back to server when local getById is null`() =
        runTest {
            val fixture = TestFixture()
            val collection = createCollection(name = "Server Collection")
            everySuspend { fixture.collectionRepository.getById(collection.id) } returns null
            everySuspend { fixture.collectionRepository.getCollectionFromServer(collection.id) } returns AppResult.Success(collection)
            everySuspend { fixture.loadCollectionBooksUseCase(collection.id) } returns Success(emptyList())
            everySuspend { fixture.loadCollectionSharesUseCase(collection.id) } returns Success(emptyList())

            val viewModel = fixture.build()
            advanceUntilIdle()

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertEquals(collection, ready.collection)
            assertEquals("Server Collection", ready.editedName)
        }

    @Test
    fun `Error state emitted when load throws`() =
        runTest {
            val fixture = TestFixture()
            everySuspend { fixture.collectionRepository.getById("c1") } throws RuntimeException("db broken")

            val viewModel = fixture.build()
            advanceUntilIdle()

            val error = assertIs<AdminCollectionDetailUiState.Error>(viewModel.state.value)
            assertEquals("db broken", error.message)
        }

    // ========== Edit Buffer + isDirty ==========

    @Test
    fun `updateName reflects on Ready editedName`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.updateName("Renamed")

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertEquals("Renamed", ready.editedName)
        }

    @Test
    fun `isDirty is false for untouched or blank or unchanged-after-trim buffer`() =
        runTest {
            val fixture = createFixture(collection = createCollection(name = "Original"))
            val viewModel = fixture.build()
            advanceUntilIdle()

            val initial = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertFalse(initial.isDirty, "untouched buffer should not be dirty")

            viewModel.updateName("   ")
            val blank = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertFalse(blank.isDirty, "blank buffer should not be dirty (Save disabled)")

            viewModel.updateName("  Original  ")
            val trimmedSame = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertFalse(trimmedSame.isDirty, "buffer matching collection name after trim should not be dirty")

            viewModel.updateName("New Name")
            val changed = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertTrue(changed.isDirty, "differing buffer should be dirty")
        }

    // ========== Save Name ==========

    @Test
    fun `saveName happy path clears isSaving sets saveSuccess and commits new collection`() =
        runTest {
            val fixture = createFixture(collection = createCollection(name = "Original"))
            val updated = createCollection(name = "Renamed")
            everySuspend {
                fixture.updateCollectionNameUseCase(collectionId = "c1", name = "Renamed")
            } returns Success(updated)

            val viewModel = fixture.build()
            advanceUntilIdle()
            viewModel.updateName("Renamed")

            viewModel.saveName()
            advanceUntilIdle()

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isSaving)
            assertTrue(ready.saveSuccess)
            assertEquals(updated, ready.collection)
            assertNull(ready.error)
        }

    @Test
    fun `saveName sets transient error for blank input and does not call repo`() =
        runTest {
            val fixture = createFixture()
            val viewModel = fixture.build()
            advanceUntilIdle()
            viewModel.updateName("   ")

            viewModel.saveName()
            advanceUntilIdle()

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertEquals("Collection name cannot be empty", ready.error)
            assertFalse(ready.isSaving)
            assertFalse(ready.saveSuccess)
        }

    @Test
    fun `saveName with unchanged name sets saveSuccess without calling repo`() =
        runTest {
            val fixture = createFixture(collection = createCollection(name = "Original"))
            val viewModel = fixture.build()
            advanceUntilIdle()
            // Buffer matches collection name.

            viewModel.saveName()
            advanceUntilIdle()

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertTrue(ready.saveSuccess)
            assertFalse(ready.isSaving)
        }

    @Test
    fun `saveName failure surfaces as transient error on Ready`() =
        runTest {
            val fixture = createFixture(collection = createCollection(name = "Original"))
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation to the ViewModel.
            everySuspend {
                fixture.updateCollectionNameUseCase(collectionId = "c1", name = "Renamed")
            } returns
                Failure(
                    com.calypsan.listenup.api.error
                        .ValidationError(message = "conflict"),
                )

            val viewModel = fixture.build()
            advanceUntilIdle()
            viewModel.updateName("Renamed")

            viewModel.saveName()
            advanceUntilIdle()

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isSaving)
            assertFalse(ready.saveSuccess)
            assertEquals("conflict", ready.error)
        }

    // ========== Remove Book ==========

    @Test
    fun `removeBook happy path clears overlay filters book and decrements count`() =
        runTest {
            val book1 = createBookSummary(id = "b1", title = "One")
            val book2 = createBookSummary(id = "b2", title = "Two")
            val fixture =
                createFixture(
                    collection = createCollection(bookCount = 2),
                    books = listOf(book1, book2),
                )
            everySuspend {
                fixture.removeBookFromCollectionUseCase(collectionId = "c1", bookId = "b1")
            } returns Success(Unit)

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.removeBook("b1")
            advanceUntilIdle()

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertNull(ready.removingBookId)
            assertEquals(listOf("b2"), ready.books.map { it.id })
            assertEquals(1, ready.collection.bookCount)
            assertNull(ready.error)
        }

    @Test
    fun `removeBook failure clears overlay and surfaces transient error`() =
        runTest {
            val book = createBookSummary(id = "b1")
            val fixture =
                createFixture(
                    books = listOf(book),
                )
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation to the ViewModel.
            everySuspend {
                fixture.removeBookFromCollectionUseCase(collectionId = "c1", bookId = "b1")
            } returns
                Failure(
                    com.calypsan.listenup.api.error
                        .ValidationError(message = "permission denied"),
                )

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.removeBook("b1")
            advanceUntilIdle()

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertNull(ready.removingBookId)
            assertEquals("permission denied", ready.error)
            // Book still present (no optimistic removal on failure).
            assertEquals(listOf("b1"), ready.books.map { it.id })
        }

    // ========== Share / Unshare ==========

    @Test
    fun `shareWithUser happy path appends enriched share and closes sheet`() =
        runTest {
            val fixture = createFixture()
            val user = createUser(id = "u2", displayName = "Bob", email = "bob@example.com")
            val rawShare =
                CollectionShareSummary(
                    id = "s-new",
                    userId = "u2",
                    userName = "",
                    userEmail = "",
                    permission = "view",
                )
            everySuspend { fixture.getUsersForSharingUseCase("c1") } returns Success(listOf(user))
            everySuspend {
                fixture.shareCollectionUseCase(collectionId = "c1", userId = "u2")
            } returns Success(rawShare)

            val viewModel = fixture.build()
            advanceUntilIdle()
            viewModel.showAddMemberSheet()
            advanceUntilIdle()

            viewModel.shareWithUser("u2")
            advanceUntilIdle()

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isSharing)
            assertFalse(ready.showAddMemberSheet)
            assertEquals(listOf("s-new"), ready.shares.map { it.id })
            assertEquals("Bob", ready.shares.first().userName)
            assertEquals("bob@example.com", ready.shares.first().userEmail)
        }

    @Test
    fun `removeShare happy path clears overlay and filters share`() =
        runTest {
            val share1 = createShareSummary(id = "s1", userName = "Alice")
            val share2 = createShareSummary(id = "s2", userId = "u3", userName = "Carol")
            val fixture = createFixture(shares = listOf(share1, share2))
            everySuspend { fixture.removeCollectionShareUseCase("s1") } returns Success(Unit)

            val viewModel = fixture.build()
            advanceUntilIdle()

            viewModel.removeShare("s1")
            advanceUntilIdle()

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertNull(ready.removingShareId)
            assertEquals(listOf("s2"), ready.shares.map { it.id })
        }

    // ========== Transient State Clearing ==========

    @Test
    fun `clearError resets Ready error to null`() =
        runTest {
            val fixture = createFixture()
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation to the ViewModel.
            everySuspend {
                fixture.updateCollectionNameUseCase(collectionId = "c1", name = any<String>())
            } returns
                Failure(
                    com.calypsan.listenup.api.error
                        .ValidationError(message = "boom"),
                )
            val viewModel = fixture.build()
            advanceUntilIdle()
            viewModel.updateName("Changed")
            viewModel.saveName()
            advanceUntilIdle()
            assertEquals(
                "boom",
                assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value).error,
            )

            viewModel.clearError()

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertNull(ready.error)
        }

    @Test
    fun `clearSaveSuccess resets saveSuccess flag`() =
        runTest {
            val fixture = createFixture(collection = createCollection(name = "Original"))
            val viewModel = fixture.build()
            advanceUntilIdle()
            // Unchanged name short-circuits to saveSuccess=true without a repo call.
            viewModel.saveName()
            advanceUntilIdle()
            assertTrue(
                assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value).saveSuccess,
            )

            viewModel.clearSaveSuccess()

            val ready = assertIs<AdminCollectionDetailUiState.Ready>(viewModel.state.value)
            assertFalse(ready.saveSuccess)
        }
}
