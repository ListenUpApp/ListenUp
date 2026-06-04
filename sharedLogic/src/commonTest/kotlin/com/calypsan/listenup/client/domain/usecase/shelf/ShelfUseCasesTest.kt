package com.calypsan.listenup.client.domain.usecase.shelf

import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for Shelf use cases.
 *
 * The repository now returns [AppResult] directly; use cases validate then forward
 * the typed result. Covers validation gates plus the new privacy/reorder surface.
 */
class ShelfUseCasesTest {
    // ========== Test Fixtures ==========

    private fun createShelf(
        id: String = "shelf-123",
        name: String = "Test Shelf",
        description: String? = null,
        isPrivate: Boolean = false,
    ) = Shelf(
        id = id,
        name = name,
        description = description,
        isPrivate = isPrivate,
        ownerId = "owner-123",
        ownerDisplayName = "Test User",
        bookCount = 0,
        totalDurationSeconds = 0,
        createdAtMs = 1736208000000L,
        updatedAtMs = 1736208000000L,
    )

    // ========== CreateShelfUseCase Tests ==========

    @Test
    fun `create shelf returns success with valid name`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            val expectedShelf = createShelf(name = "My Reading List")
            everySuspend { shelfRepository.createShelf(any(), any(), any()) } returns AppResult.Success(expectedShelf)
            val useCase = CreateShelfUseCase(shelfRepository)

            val result = useCase(name = "My Reading List", description = null)

            val success = assertIs<AppResult.Success<Shelf>>(result)
            assertEquals("My Reading List", success.data.name)
        }

    @Test
    fun `create shelf calls repository with trimmed name and privacy flag`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            everySuspend {
                shelfRepository.createShelf(any(), any(), any())
            } returns AppResult.Success(createShelf())
            val useCase = CreateShelfUseCase(shelfRepository)

            useCase(name = "  Trimmed Name  ", description = null, isPrivate = true)

            verifySuspend { shelfRepository.createShelf("Trimmed Name", null, true) }
        }

    @Test
    fun `create shelf returns validation error for blank name`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            val useCase = CreateShelfUseCase(shelfRepository)

            val result = useCase(name = "   ", description = null)

            val failure = assertIs<AppResult.Failure>(result)
            assertIs<ValidationError>(failure.error)
            assertEquals("Shelf name is required", failure.message)
        }

    @Test
    fun `create shelf converts empty description to null`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            everySuspend {
                shelfRepository.createShelf(any(), any(), any())
            } returns AppResult.Success(createShelf())
            val useCase = CreateShelfUseCase(shelfRepository)

            useCase(name = "Test", description = "   ")

            verifySuspend { shelfRepository.createShelf("Test", null, false) }
        }

    @Test
    fun `create shelf forwards repository failure`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            everySuspend {
                shelfRepository.createShelf(any(), any(), any())
            } returns AppResult.Failure(ValidationError(message = "duplicate"))
            val useCase = CreateShelfUseCase(shelfRepository)

            val result = useCase(name = "Test", description = null)

            assertIs<AppResult.Failure>(result)
        }

    // ========== UpdateShelfUseCase Tests ==========

    @Test
    fun `update shelf returns success with valid name`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            val expectedShelf = createShelf(name = "Updated Name")
            everySuspend {
                shelfRepository.updateShelf(any(), any(), any(), any())
            } returns AppResult.Success(expectedShelf)
            val useCase = UpdateShelfUseCase(shelfRepository)

            val result = useCase(shelfId = "shelf-123", name = "Updated Name", description = null)

            val success = assertIs<AppResult.Success<Shelf>>(result)
            assertEquals("Updated Name", success.data.name)
        }

    @Test
    fun `update shelf calls repository with correct parameters and privacy flag`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            everySuspend {
                shelfRepository.updateShelf(any(), any(), any(), any())
            } returns AppResult.Success(createShelf())
            val useCase = UpdateShelfUseCase(shelfRepository)

            useCase(shelfId = "shelf-456", name = "New Name", description = "New description", isPrivate = true)

            verifySuspend { shelfRepository.updateShelf("shelf-456", "New Name", "New description", true) }
        }

    @Test
    fun `update shelf returns validation error for blank name`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            val useCase = UpdateShelfUseCase(shelfRepository)

            val result = useCase(shelfId = "shelf-123", name = "   ", description = null)

            val failure = assertIs<AppResult.Failure>(result)
            assertIs<ValidationError>(failure.error)
            assertEquals("Shelf name is required", failure.message)
        }

    // ========== DeleteShelfUseCase Tests ==========

    @Test
    fun `delete shelf returns success`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.deleteShelf(any()) } returns AppResult.Success(Unit)
            val useCase = DeleteShelfUseCase(shelfRepository)

            val result = useCase(shelfId = "shelf-123")

            checkIs<AppResult.Success<Unit>>(result)
        }

    @Test
    fun `delete shelf calls repository with correct ID`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.deleteShelf(any()) } returns AppResult.Success(Unit)
            val useCase = DeleteShelfUseCase(shelfRepository)

            useCase(shelfId = "shelf-456")

            verifySuspend { shelfRepository.deleteShelf("shelf-456") }
        }

    @Test
    fun `delete shelf forwards repository failure`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            everySuspend {
                shelfRepository.deleteShelf(any())
            } returns AppResult.Failure(ValidationError(message = "boom"))
            val useCase = DeleteShelfUseCase(shelfRepository)

            val result = useCase(shelfId = "shelf-123")

            assertIs<AppResult.Failure>(result)
        }

    // ========== ReorderShelfBooksUseCase Tests ==========

    @Test
    fun `reorder books forwards the new order to the repository`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.reorderBooks(any(), any()) } returns AppResult.Success(Unit)
            val useCase = ReorderShelfBooksUseCase(shelfRepository)

            val result = useCase(shelfId = "shelf-1", orderedBookIds = listOf("b2", "b1", "b3"))

            checkIs<AppResult.Success<Unit>>(result)
            verifySuspend { shelfRepository.reorderBooks("shelf-1", listOf("b2", "b1", "b3")) }
        }

    @Test
    fun `reorder books returns validation error for empty list`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            val useCase = ReorderShelfBooksUseCase(shelfRepository)

            val result = useCase(shelfId = "shelf-1", orderedBookIds = emptyList())

            val failure = assertIs<AppResult.Failure>(result)
            assertIs<ValidationError>(failure.error)
        }

    // ========== AddBooksToShelfUseCase Tests ==========

    @Test
    fun `add books returns validation error for empty list`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            val useCase = AddBooksToShelfUseCase(shelfRepository)

            val result = useCase(shelfId = "shelf-1", bookIds = emptyList())

            val failure = assertIs<AppResult.Failure>(result)
            assertIs<ValidationError>(failure.error)
        }

    @Test
    fun `add books forwards to the repository`() =
        runTest {
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.addBooksToShelf(any(), any()) } returns AppResult.Success(Unit)
            val useCase = AddBooksToShelfUseCase(shelfRepository)

            val result = useCase(shelfId = "shelf-1", bookIds = listOf("b1", "b2"))

            checkIs<AppResult.Success<Unit>>(result)
            verifySuspend { shelfRepository.addBooksToShelf("shelf-1", listOf("b1", "b2")) }
        }
}
