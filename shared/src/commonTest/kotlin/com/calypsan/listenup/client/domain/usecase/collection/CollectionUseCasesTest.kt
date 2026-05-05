package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.repository.CollectionRepository
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
 * Tests for Collection use cases.
 *
 * Tests cover:
 * - CreateCollectionUseCase: validation, repository call
 * - DeleteCollectionUseCase: repository call
 */
class CollectionUseCasesTest {
    // ========== Test Data ==========

    private fun createCollection(
        id: String = "collection-123",
        name: String = "Test Collection",
        bookCount: Int = 0,
    ) = Collection(
        id = id,
        name = name,
        bookCount = bookCount,
        createdAtMs = 1704067200000L,
        updatedAtMs = 1704067200000L,
    )

    // ========== CreateCollectionUseCase Tests ==========

    @Test
    fun `create collection returns success with valid name`() =
        runTest {
            // Given
            val repository: CollectionRepository = mock()
            val collection = createCollection(name = "My Collection")
            everySuspend { repository.create(any()) } returns collection
            val useCase = CreateCollectionUseCase(repository)

            // When
            val result = useCase(name = "My Collection")

            // Then
            val success = assertIs<Success<Collection>>(result)
            assertEquals("My Collection", success.data.name)
            assertEquals("collection-123", success.data.id)
        }

    @Test
    fun `create collection calls repository with trimmed name`() =
        runTest {
            // Given
            val repository: CollectionRepository = mock()
            everySuspend { repository.create(any()) } returns createCollection()
            val useCase = CreateCollectionUseCase(repository)

            // When
            useCase(name = "  Trimmed Name  ")

            // Then
            verifySuspend { repository.create("Trimmed Name") }
        }

    @Test
    fun `create collection returns validation error for blank name`() =
        runTest {
            // Given
            val repository: CollectionRepository = mock()
            val useCase = CreateCollectionUseCase(repository)

            // When
            val result = useCase(name = "   ")

            // Then
            val failure = assertIs<Failure>(result)
            assertIs<com.calypsan.listenup.api.error.ValidationError>(failure.error)
            assertEquals("Collection name is required", failure.message)
        }

    @Test
    fun `create collection returns validation error for empty name`() =
        runTest {
            // Given
            val repository: CollectionRepository = mock()
            val useCase = CreateCollectionUseCase(repository)

            // When
            val result = useCase(name = "")

            // Then
            val failure = assertIs<Failure>(result)
            assertIs<com.calypsan.listenup.api.error.ValidationError>(failure.error)
        }

    @Test
    fun `create collection returns failure on repository error`() =
        runTest {
            // Given
            val repository: CollectionRepository = mock()
            everySuspend { repository.create(any()) } throws Exception("Server error")
            val useCase = CreateCollectionUseCase(repository)

            // When
            val result = useCase(name = "Test")

            // Then
            val failure = assertIs<Failure>(result)
            assertEquals("Server error", failure.message)
        }

    // ========== DeleteCollectionUseCase Tests ==========

    @Test
    fun `delete collection returns success`() =
        runTest {
            // Given
            val repository: CollectionRepository = mock()
            everySuspend { repository.delete(any()) } returns Unit
            val useCase = DeleteCollectionUseCase(repository)

            // When
            val result = useCase(collectionId = "collection-123")

            // Then
            checkIs<Success<Unit>>(result)
        }

    @Test
    fun `delete collection calls repository with correct ID`() =
        runTest {
            // Given
            val repository: CollectionRepository = mock()
            everySuspend { repository.delete(any()) } returns Unit
            val useCase = DeleteCollectionUseCase(repository)

            // When
            useCase(collectionId = "collection-456")

            // Then
            verifySuspend { repository.delete("collection-456") }
        }

    @Test
    fun `delete collection returns failure on repository error`() =
        runTest {
            // Given
            val repository: CollectionRepository = mock()
            everySuspend { repository.delete(any()) } throws Exception("Not found")
            val useCase = DeleteCollectionUseCase(repository)

            // When
            val result = useCase(collectionId = "collection-123")

            // Then
            val failure = assertIs<Failure>(result)
            assertEquals("Not found", failure.message)
        }
}
