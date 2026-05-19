package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.CollectionDao
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.remote.AdminCollectionApiContract
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for CollectionRepositoryImpl.
 *
 * Tests cover:
 * - All interface methods: observeAll(), getAll(), getById()
 * - Entity to domain model conversion (toDomain)
 * - Edge cases: empty results, null handling
 *
 * Uses Mokkery for mocking CollectionDao.
 * Follows Given-When-Then pattern for test structure.
 */
class CollectionRepositoryImplTest {
    // ========== Test Fixtures ==========

    private class TestFixture {
        val collectionDao: CollectionDao = mock()
        val adminCollectionApi: AdminCollectionApiContract = mock()

        fun build(): CollectionRepositoryImpl = CollectionRepositoryImpl(dao = collectionDao, adminCollectionApi = adminCollectionApi)
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()
        // Default stubs for common operations
        every { fixture.collectionDao.observeAll() } returns flowOf(emptyList())
        return fixture
    }

    /**
     * Factory for creating test CollectionEntity instances.
     */
    private fun createTestCollectionEntity(
        id: String = "collection-1",
        name: String = "Test Collection",
        bookCount: Int = 5,
        createdAt: Long = 1704067200000L, // Jan 1, 2024 00:00:00 UTC
        updatedAt: Long = 1704153600000L, // Jan 2, 2024 00:00:00 UTC
    ): CollectionEntity =
        CollectionEntity(
            id = id,
            name = name,
            bookCount = bookCount,
            createdAt = Timestamp(createdAt),
            updatedAt = Timestamp(updatedAt),
        )

    // ========== observeAll Tests ==========

    @Test
    fun `observeAll returns empty list when no collections exist`() =
        runTest {
            // Given
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(emptyList())
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `observeAll transforms single entity to domain model`() =
        runTest {
            // Given
            val entity =
                createTestCollectionEntity(
                    id = "col-1",
                    name = "Sci-Fi Favorites",
                    bookCount = 10,
                    createdAt = 1704067200000L,
                    updatedAt = 1704153600000L,
                )
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(listOf(entity))
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(1, result.size)
            val collection = result.first()
            assertEquals("col-1", collection.id)
            assertEquals("Sci-Fi Favorites", collection.name)
            assertEquals(10, collection.bookCount)
            assertEquals(1704067200000L, collection.createdAtMs)
            assertEquals(1704153600000L, collection.updatedAtMs)
        }

    @Test
    fun `observeAll transforms multiple entities to domain models`() =
        runTest {
            // Given
            val entities =
                listOf(
                    createTestCollectionEntity(id = "col-1", name = "Fantasy", bookCount = 15),
                    createTestCollectionEntity(id = "col-2", name = "Mystery", bookCount = 8),
                    createTestCollectionEntity(id = "col-3", name = "Romance", bookCount = 20),
                )
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(entities)
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(3, result.size)
            assertEquals("col-1", result[0].id)
            assertEquals("Fantasy", result[0].name)
            assertEquals(15, result[0].bookCount)
            assertEquals("col-2", result[1].id)
            assertEquals("Mystery", result[1].name)
            assertEquals(8, result[1].bookCount)
            assertEquals("col-3", result[2].id)
            assertEquals("Romance", result[2].name)
            assertEquals(20, result[2].bookCount)
        }

    @Test
    fun `observeAll preserves order from DAO`() =
        runTest {
            // Given - DAO returns collections in alphabetical order by name
            val entities =
                listOf(
                    createTestCollectionEntity(id = "col-a", name = "A Collection"),
                    createTestCollectionEntity(id = "col-b", name = "B Collection"),
                    createTestCollectionEntity(id = "col-c", name = "C Collection"),
                )
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(entities)
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then - order is preserved
            assertEquals("A Collection", result[0].name)
            assertEquals("B Collection", result[1].name)
            assertEquals("C Collection", result[2].name)
        }

    @Test
    fun `observeAll handles collection with zero books`() =
        runTest {
            // Given
            val entity =
                createTestCollectionEntity(
                    id = "empty-col",
                    name = "Empty Collection",
                    bookCount = 0,
                )
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(listOf(entity))
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(1, result.size)
            assertEquals(0, result.first().bookCount)
        }

    @Test
    fun `observeAll correctly maps timestamp fields`() =
        runTest {
            // Given - specific timestamps for verification
            val createdAtMs = 1609459200000L // Jan 1, 2021 00:00:00 UTC
            val updatedAtMs = 1640995200000L // Jan 1, 2022 00:00:00 UTC
            val entity =
                createTestCollectionEntity(
                    createdAt = createdAtMs,
                    updatedAt = updatedAtMs,
                )
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(listOf(entity))
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(createdAtMs, result.first().createdAtMs)
            assertEquals(updatedAtMs, result.first().updatedAtMs)
        }

    // ========== getAll Tests ==========

    @Test
    fun `getAll returns empty list when no collections exist`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.collectionDao.getAll() } returns emptyList()
            val repository = fixture.build()

            // When
            val result = repository.getAll()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getAll transforms entities to domain models`() =
        runTest {
            // Given
            val entities =
                listOf(
                    createTestCollectionEntity(id = "col-1", name = "Horror", bookCount = 7),
                    createTestCollectionEntity(id = "col-2", name = "Thriller", bookCount = 12),
                )
            val fixture = createFixture()
            everySuspend { fixture.collectionDao.getAll() } returns entities
            val repository = fixture.build()

            // When
            val result = repository.getAll()

            // Then
            assertEquals(2, result.size)
            assertEquals("col-1", result[0].id)
            assertEquals("Horror", result[0].name)
            assertEquals(7, result[0].bookCount)
            assertEquals("col-2", result[1].id)
            assertEquals("Thriller", result[1].name)
            assertEquals(12, result[1].bookCount)
        }

    @Test
    fun `getAll returns all collections synchronously`() =
        runTest {
            // Given
            val entities =
                (1..10).map { i ->
                    createTestCollectionEntity(
                        id = "col-$i",
                        name = "Collection $i",
                        bookCount = i * 5,
                    )
                }
            val fixture = createFixture()
            everySuspend { fixture.collectionDao.getAll() } returns entities
            val repository = fixture.build()

            // When
            val result = repository.getAll()

            // Then
            assertEquals(10, result.size)
            result.forEachIndexed { index, collection ->
                assertEquals("col-${index + 1}", collection.id)
                assertEquals("Collection ${index + 1}", collection.name)
                assertEquals((index + 1) * 5, collection.bookCount)
            }
        }

    // ========== getById Tests ==========

    @Test
    fun `getById returns null when collection not found`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.collectionDao.getById("nonexistent") } returns null
            val repository = fixture.build()

            // When
            val result = repository.getById("nonexistent")

            // Then
            assertNull(result)
        }

    @Test
    fun `getById returns collection when found`() =
        runTest {
            // Given
            val entity =
                createTestCollectionEntity(
                    id = "col-123",
                    name = "Classic Literature",
                    bookCount = 25,
                )
            val fixture = createFixture()
            everySuspend { fixture.collectionDao.getById("col-123") } returns entity
            val repository = fixture.build()

            // When
            val result = repository.getById("col-123")

            // Then
            assertEquals("col-123", result?.id)
            assertEquals("Classic Literature", result?.name)
            assertEquals(25, result?.bookCount)
        }

    @Test
    fun `getById transforms entity to domain model with all fields`() =
        runTest {
            // Given
            val createdAtMs = 1672531200000L // Jan 1, 2023 00:00:00 UTC
            val updatedAtMs = 1704067200000L // Jan 1, 2024 00:00:00 UTC
            val entity =
                createTestCollectionEntity(
                    id = "col-full",
                    name = "Complete Collection",
                    bookCount = 100,
                    createdAt = createdAtMs,
                    updatedAt = updatedAtMs,
                )
            val fixture = createFixture()
            everySuspend { fixture.collectionDao.getById("col-full") } returns entity
            val repository = fixture.build()

            // When
            val result = repository.getById("col-full")

            // Then
            assertEquals("col-full", result?.id)
            assertEquals("Complete Collection", result?.name)
            assertEquals(100, result?.bookCount)
            assertEquals(createdAtMs, result?.createdAtMs)
            assertEquals(updatedAtMs, result?.updatedAtMs)
        }

    @Test
    fun `getById queries DAO with correct id parameter`() =
        runTest {
            // Given
            val targetId = "specific-collection-id"
            val entity = createTestCollectionEntity(id = targetId, name = "Specific")
            val fixture = createFixture()
            everySuspend { fixture.collectionDao.getById(targetId) } returns entity
            val repository = fixture.build()

            // When
            val result = repository.getById(targetId)

            // Then
            assertEquals(targetId, result?.id)
        }

    // ========== Entity to Domain Conversion Tests ==========

    @Test
    fun `toDomain preserves id field exactly`() =
        runTest {
            // Given
            val entityId = "uuid-12345-abcde-67890"
            val entity = createTestCollectionEntity(id = entityId)
            val fixture = createFixture()
            everySuspend { fixture.collectionDao.getById(entityId) } returns entity
            val repository = fixture.build()

            // When
            val result = repository.getById(entityId)

            // Then
            assertEquals(entityId, result?.id)
        }

    @Test
    fun `toDomain preserves name field with special characters`() =
        runTest {
            // Given - name with special characters
            val specialName = "Books & Stories: A Collection (2024)"
            val entity = createTestCollectionEntity(name = specialName)
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(listOf(entity))
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(specialName, result.first().name)
        }

    @Test
    fun `toDomain preserves name field with unicode characters`() =
        runTest {
            // Given - name with unicode characters
            val unicodeName = "Literature mondiale"
            val entity = createTestCollectionEntity(name = unicodeName)
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(listOf(entity))
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(unicodeName, result.first().name)
        }

    @Test
    fun `toDomain handles large book counts`() =
        runTest {
            // Given
            val largeBookCount = Int.MAX_VALUE
            val entity = createTestCollectionEntity(bookCount = largeBookCount)
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(listOf(entity))
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(largeBookCount, result.first().bookCount)
        }

    @Test
    fun `toDomain converts Timestamp createdAt to epochMillis`() =
        runTest {
            // Given
            val epochMs = 1704067200000L
            val entity = createTestCollectionEntity(createdAt = epochMs)
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(listOf(entity))
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(epochMs, result.first().createdAtMs)
        }

    @Test
    fun `toDomain converts Timestamp updatedAt to epochMillis`() =
        runTest {
            // Given
            val epochMs = 1704153600000L
            val entity = createTestCollectionEntity(updatedAt = epochMs)
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(listOf(entity))
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(epochMs, result.first().updatedAtMs)
        }

    @Test
    fun `toDomain handles zero timestamps`() =
        runTest {
            // Given - timestamps at epoch zero (Jan 1, 1970)
            val entity = createTestCollectionEntity(createdAt = 0L, updatedAt = 0L)
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(listOf(entity))
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(0L, result.first().createdAtMs)
            assertEquals(0L, result.first().updatedAtMs)
        }

    @Test
    fun `toDomain handles negative timestamps`() =
        runTest {
            // Given - negative timestamp (before Unix epoch, e.g., 1960)
            val negativeTimestamp = -315619200000L // Jan 1, 1960
            val entity =
                createTestCollectionEntity(
                    createdAt = negativeTimestamp,
                    updatedAt = negativeTimestamp,
                )
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(listOf(entity))
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(negativeTimestamp, result.first().createdAtMs)
            assertEquals(negativeTimestamp, result.first().updatedAtMs)
        }

    // ========== Edge Cases ==========

    @Test
    fun `getById returns null for empty string id`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.collectionDao.getById("") } returns null
            val repository = fixture.build()

            // When
            val result = repository.getById("")

            // Then
            assertNull(result)
        }

    @Test
    fun `observeAll handles very long collection names`() =
        runTest {
            // Given - extremely long name
            val longName = "A".repeat(1000)
            val entity = createTestCollectionEntity(name = longName)
            val fixture = createFixture()
            every { fixture.collectionDao.observeAll() } returns flowOf(listOf(entity))
            val repository = fixture.build()

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(longName, result.first().name)
            assertEquals(1000, result.first().name.length)
        }

    @Test
    fun `getAll handles empty name`() =
        runTest {
            // Given - collection with empty name (edge case)
            val entity = createTestCollectionEntity(name = "")
            val fixture = createFixture()
            everySuspend { fixture.collectionDao.getAll() } returns listOf(entity)
            val repository = fixture.build()

            // When
            val result = repository.getAll()

            // Then
            assertEquals("", result.first().name)
        }
}
