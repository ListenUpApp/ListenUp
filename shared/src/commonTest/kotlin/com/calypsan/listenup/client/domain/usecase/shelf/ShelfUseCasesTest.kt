package com.calypsan.listenup.client.domain.usecase.shelf

import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
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
 * Tests cover:
 * - CreateShelfUseCase: validation, repository call, error handling
 * - UpdateShelfUseCase: validation, repository call, error handling
 * - DeleteShelfUseCase: repository call, error handling
 */
class ShelfUseCasesTest {
    // ========== Test Fixtures ==========

    private fun createShelf(
        id: String = "shelf-123",
        name: String = "Test Shelf",
        description: String? = null,
    ) = Shelf(
        id = id,
        name = name,
        description = description,
        ownerId = "owner-123",
        ownerDisplayName = "Test User",
        ownerAvatarColor = "#FF0000",
        bookCount = 0,
        totalDurationSeconds = 0,
        createdAtMs = 1736208000000L,
        updatedAtMs = 1736208000000L,
    )

    // ========== CreateShelfUseCase Tests ==========

    @Test
    fun `create shelf returns success with valid name`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            val expectedShelf = createShelf(name = "My Reading List")
            everySuspend { shelfRepository.createShelf(any(), any()) } returns expectedShelf
            val useCase = CreateShelfUseCase(shelfRepository)

            // When
            val result = useCase(name = "My Reading List", description = null)

            // Then
            val success = assertIs<Success<Shelf>>(result)
            assertEquals("My Reading List", success.data.name)
        }

    @Test
    fun `create shelf calls repository with trimmed name`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.createShelf(any(), any()) } returns createShelf()
            val useCase = CreateShelfUseCase(shelfRepository)

            // When
            useCase(name = "  Trimmed Name  ", description = null)

            // Then
            verifySuspend { shelfRepository.createShelf("Trimmed Name", null) }
        }

    @Test
    fun `create shelf returns validation error for blank name`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            val useCase = CreateShelfUseCase(shelfRepository)

            // When
            val result = useCase(name = "   ", description = null)

            // Then
            val failure = assertIs<Failure>(result)
            assertIs<com.calypsan.listenup.api.error.ValidationError>(failure.error)
            assertEquals("Shelf name is required", failure.message)
        }

    @Test
    fun `create shelf returns validation error for empty name`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            val useCase = CreateShelfUseCase(shelfRepository)

            // When
            val result = useCase(name = "", description = null)

            // Then
            val failure = assertIs<Failure>(result)
            assertIs<com.calypsan.listenup.api.error.ValidationError>(failure.error)
        }

    @Test
    fun `create shelf passes description to repository`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.createShelf(any(), any()) } returns createShelf()
            val useCase = CreateShelfUseCase(shelfRepository)

            // When
            useCase(name = "Test", description = "A curated list")

            // Then
            verifySuspend { shelfRepository.createShelf("Test", "A curated list") }
        }

    @Test
    fun `create shelf converts empty description to null`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.createShelf(any(), any()) } returns createShelf()
            val useCase = CreateShelfUseCase(shelfRepository)

            // When
            useCase(name = "Test", description = "   ")

            // Then
            verifySuspend { shelfRepository.createShelf("Test", null) }
        }

    @Test
    fun `create shelf returns failure on repository error`() =
        runTest {
            // Test name: "returns failure on repository error" — only cares that the
            // failure path is hit. Specific message text comes from the test fixture
            // itself (not production behavior), so asserting on it adds no coverage.
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.createShelf(any(), any()) } throws Exception("repo failed")
            val useCase = CreateShelfUseCase(shelfRepository)

            val result = useCase(name = "Test", description = null)

            assertIs<Failure>(result)
        }

    // ========== UpdateShelfUseCase Tests ==========

    @Test
    fun `update shelf returns success with valid name`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            val expectedShelf = createShelf(name = "Updated Name")
            everySuspend { shelfRepository.updateShelf(any(), any(), any()) } returns expectedShelf
            val useCase = UpdateShelfUseCase(shelfRepository)

            // When
            val result = useCase(shelfId = "shelf-123", name = "Updated Name", description = null)

            // Then
            val success = assertIs<Success<Shelf>>(result)
            assertEquals("Updated Name", success.data.name)
        }

    @Test
    fun `update shelf calls repository with correct parameters`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.updateShelf(any(), any(), any()) } returns createShelf()
            val useCase = UpdateShelfUseCase(shelfRepository)

            // When
            useCase(shelfId = "shelf-456", name = "New Name", description = "New description")

            // Then
            verifySuspend { shelfRepository.updateShelf("shelf-456", "New Name", "New description") }
        }

    @Test
    fun `update shelf returns validation error for blank name`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            val useCase = UpdateShelfUseCase(shelfRepository)

            // When
            val result = useCase(shelfId = "shelf-123", name = "   ", description = null)

            // Then
            val failure = assertIs<Failure>(result)
            assertIs<com.calypsan.listenup.api.error.ValidationError>(failure.error)
            assertEquals("Shelf name is required", failure.message)
        }

    @Test
    fun `update shelf trims name before repository call`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.updateShelf(any(), any(), any()) } returns createShelf()
            val useCase = UpdateShelfUseCase(shelfRepository)

            // When
            useCase(shelfId = "shelf-123", name = "  Trimmed  ", description = null)

            // Then
            verifySuspend { shelfRepository.updateShelf("shelf-123", "Trimmed", null) }
        }

    @Test
    fun `update shelf converts empty description to null`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.updateShelf(any(), any(), any()) } returns createShelf()
            val useCase = UpdateShelfUseCase(shelfRepository)

            // When
            useCase(shelfId = "shelf-123", name = "Test", description = "")

            // Then
            verifySuspend { shelfRepository.updateShelf("shelf-123", "Test", null) }
        }

    @Test
    fun `update shelf returns failure on repository error`() =
        runTest {
            // See `create shelf returns failure on repository error` for rationale.
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.updateShelf(any(), any(), any()) } throws Exception("repo failed")
            val useCase = UpdateShelfUseCase(shelfRepository)

            val result = useCase(shelfId = "shelf-123", name = "Test", description = null)

            assertIs<Failure>(result)
        }

    // ========== DeleteShelfUseCase Tests ==========

    @Test
    fun `delete shelf returns success`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.deleteShelf(any()) } returns Unit
            val useCase = DeleteShelfUseCase(shelfRepository)

            // When
            val result = useCase(shelfId = "shelf-123")

            // Then
            checkIs<Success<Unit>>(result)
        }

    @Test
    fun `delete shelf calls repository with correct ID`() =
        runTest {
            // Given
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.deleteShelf(any()) } returns Unit
            val useCase = DeleteShelfUseCase(shelfRepository)

            // When
            useCase(shelfId = "shelf-456")

            // Then
            verifySuspend { shelfRepository.deleteShelf("shelf-456") }
        }

    @Test
    fun `delete shelf returns failure on repository error`() =
        runTest {
            // See `create shelf returns failure on repository error` for rationale.
            val shelfRepository: ShelfRepository = mock()
            everySuspend { shelfRepository.deleteShelf(any()) } throws Exception("repo failed")
            val useCase = DeleteShelfUseCase(shelfRepository)

            val result = useCase(shelfId = "shelf-123")

            assertIs<Failure>(result)
        }
}
