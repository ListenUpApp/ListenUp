package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.TagDao
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.remote.TagApiContract
import com.calypsan.listenup.client.domain.model.Tag
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for TagRepositoryImpl.
 *
 * Tests the repository layer that wraps TagDao and TagApi and converts
 * TagEntity to Tag domain models. Uses Given-When-Then style
 * with Mokkery for mocking.
 */
class TagRepositoryImplTest {
    // ========== Test Fixtures ==========

    private fun createMockDao(): TagDao = mock<TagDao>()

    private fun createMockApi(): TagApiContract = mock<TagApiContract>()

    private fun createRepository(
        dao: TagDao,
        tagApi: TagApiContract = createMockApi(),
    ): TagRepositoryImpl = TagRepositoryImpl(dao, tagApi)

    private fun createTestTagEntity(
        id: String = "tag-1",
        slug: String = "found-family",
        bookCount: Int = 10,
        createdAt: Long = 1000L,
    ): TagEntity =
        TagEntity(
            id = id,
            slug = slug,
            bookCount = bookCount,
            createdAt = Timestamp(createdAt),
        )

    // ========== observeAll Tests ==========

    @Test
    fun `observeAll returns empty list when no tags exist`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeAllTags() } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `observeAll transforms entities to domain models`() =
        runTest {
            // Given
            val entities =
                listOf(
                    createTestTagEntity(id = "tag-1", slug = "found-family", bookCount = 15),
                    createTestTagEntity(id = "tag-2", slug = "slow-burn", bookCount = 8),
                )
            val dao = createMockDao()
            every { dao.observeAllTags() } returns flowOf(entities)
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(2, result.size)
            assertEquals("tag-1", result[0].id)
            assertEquals("found-family", result[0].slug)
            assertEquals(15, result[0].bookCount)
            assertEquals("tag-2", result[1].id)
            assertEquals("slow-burn", result[1].slug)
            assertEquals(8, result[1].bookCount)
        }

    @Test
    fun `observeAll converts createdAt timestamp to Instant`() =
        runTest {
            // Given
            val entity = createTestTagEntity(id = "tag-1", createdAt = 1609459200000L)
            val dao = createMockDao()
            every { dao.observeAllTags() } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertNotNull(result[0].createdAt)
            assertEquals(
                Timestamp(1609459200000L),
                result[0].createdAt,
            )
        }

    @Test
    fun `observeAll preserves entity order from dao`() =
        runTest {
            // Given - entities ordered by popularity (bookCount DESC)
            val entities =
                listOf(
                    createTestTagEntity(id = "tag-1", slug = "popular", bookCount = 100),
                    createTestTagEntity(id = "tag-2", slug = "medium", bookCount = 50),
                    createTestTagEntity(id = "tag-3", slug = "rare", bookCount = 5),
                )
            val dao = createMockDao()
            every { dao.observeAllTags() } returns flowOf(entities)
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals("popular", result[0].slug)
            assertEquals("medium", result[1].slug)
            assertEquals("rare", result[2].slug)
        }

    // ========== getAll Tests ==========

    @Test
    fun `getAll returns empty list when no tags exist`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getAllTags() } returns emptyList()
            val repository = createRepository(dao)

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
                    createTestTagEntity(id = "tag-1", slug = "enemies-to-lovers", bookCount = 25),
                    createTestTagEntity(id = "tag-2", slug = "unreliable-narrator", bookCount = 12),
                )
            val dao = createMockDao()
            everySuspend { dao.getAllTags() } returns entities
            val repository = createRepository(dao)

            // When
            val result = repository.getAll()

            // Then
            assertEquals(2, result.size)
            assertEquals("tag-1", result[0].id)
            assertEquals("enemies-to-lovers", result[0].slug)
            assertEquals(25, result[0].bookCount)
            assertEquals("tag-2", result[1].id)
            assertEquals("unreliable-narrator", result[1].slug)
            assertEquals(12, result[1].bookCount)
        }

    @Test
    fun `getAll converts createdAt timestamp correctly`() =
        runTest {
            // Given
            val entity = createTestTagEntity(createdAt = 1704067200000L)
            val dao = createMockDao()
            everySuspend { dao.getAllTags() } returns listOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.getAll()

            // Then
            assertEquals(
                Timestamp(1704067200000L),
                result[0].createdAt,
            )
        }

    // ========== getById Tests ==========

    @Test
    fun `getById returns null when tag not found`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getById("nonexistent") } returns null
            val repository = createRepository(dao)

            // When
            val result = repository.getById("nonexistent")

            // Then
            assertNull(result)
        }

    @Test
    fun `getById returns tag when found`() =
        runTest {
            // Given
            val entity = createTestTagEntity(id = "tag-1", slug = "dark-academia", bookCount = 30)
            val dao = createMockDao()
            everySuspend { dao.getById("tag-1") } returns entity
            val repository = createRepository(dao)

            // When
            val result = repository.getById("tag-1")

            // Then
            assertNotNull(result)
            assertEquals("tag-1", result.id)
            assertEquals("dark-academia", result.slug)
            assertEquals(30, result.bookCount)
        }

    @Test
    fun `getById transforms entity correctly`() =
        runTest {
            // Given
            val entity =
                createTestTagEntity(
                    id = "tag-42",
                    slug = "coming-of-age",
                    bookCount = 55,
                    createdAt = 1609459200000L,
                )
            val dao = createMockDao()
            everySuspend { dao.getById("tag-42") } returns entity
            val repository = createRepository(dao)

            // When
            val result = repository.getById("tag-42")

            // Then
            assertNotNull(result)
            assertEquals("tag-42", result.id)
            assertEquals("coming-of-age", result.slug)
            assertEquals(55, result.bookCount)
            assertEquals(Timestamp(1609459200000L), result.createdAt)
        }

    // ========== getBySlug Tests ==========

    @Test
    fun `getBySlug returns null when tag not found`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getBySlug("nonexistent-slug") } returns null
            val repository = createRepository(dao)

            // When
            val result = repository.getBySlug("nonexistent-slug")

            // Then
            assertNull(result)
        }

    @Test
    fun `getBySlug returns tag when found`() =
        runTest {
            // Given
            val entity = createTestTagEntity(id = "tag-1", slug = "portal-fantasy", bookCount = 18)
            val dao = createMockDao()
            everySuspend { dao.getBySlug("portal-fantasy") } returns entity
            val repository = createRepository(dao)

            // When
            val result = repository.getBySlug("portal-fantasy")

            // Then
            assertNotNull(result)
            assertEquals("tag-1", result.id)
            assertEquals("portal-fantasy", result.slug)
            assertEquals(18, result.bookCount)
        }

    @Test
    fun `getBySlug transforms entity correctly`() =
        runTest {
            // Given
            val entity =
                createTestTagEntity(
                    id = "tag-99",
                    slug = "morally-grey",
                    bookCount = 77,
                    createdAt = 1672531200000L,
                )
            val dao = createMockDao()
            everySuspend { dao.getBySlug("morally-grey") } returns entity
            val repository = createRepository(dao)

            // When
            val result = repository.getBySlug("morally-grey")

            // Then
            assertNotNull(result)
            assertEquals("tag-99", result.id)
            assertEquals("morally-grey", result.slug)
            assertEquals(77, result.bookCount)
            assertEquals(Timestamp(1672531200000L), result.createdAt)
        }

    // ========== observeTagsForBook Tests ==========

    @Test
    fun `observeTagsForBook returns empty list when book has no tags`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeTagsForBook(BookId("book-1")) } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            val result = repository.observeTagsForBook("book-1").first()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `observeTagsForBook returns tags for book`() =
        runTest {
            // Given
            val entities =
                listOf(
                    createTestTagEntity(id = "tag-1", slug = "fantasy", bookCount = 100),
                    createTestTagEntity(id = "tag-2", slug = "magic-system", bookCount = 45),
                )
            val dao = createMockDao()
            every { dao.observeTagsForBook(BookId("book-1")) } returns flowOf(entities)
            val repository = createRepository(dao)

            // When
            val result = repository.observeTagsForBook("book-1").first()

            // Then
            assertEquals(2, result.size)
            assertEquals("fantasy", result[0].slug)
            assertEquals("magic-system", result[1].slug)
        }

    @Test
    fun `observeTagsForBook transforms entities to domain models`() =
        runTest {
            // Given
            val entity = createTestTagEntity(id = "tag-1", slug = "heist", bookCount = 20, createdAt = 1609459200000L)
            val dao = createMockDao()
            every { dao.observeTagsForBook(BookId("book-42")) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeTagsForBook("book-42").first()

            // Then
            assertEquals(1, result.size)
            assertEquals("tag-1", result[0].id)
            assertEquals("heist", result[0].slug)
            assertEquals(20, result[0].bookCount)
            assertEquals(Timestamp(1609459200000L), result[0].createdAt)
        }

    @Test
    fun `observeTagsForBook converts bookId string to BookId value class`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeTagsForBook(BookId("my-book-id")) } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            repository.observeTagsForBook("my-book-id").first()

            // Then - verify the correct BookId was passed to dao
            // The test passes if no exception is thrown and the flow emits
        }

    // ========== getTagsForBook Tests ==========

    @Test
    fun `getTagsForBook returns empty list when book has no tags`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getTagsForBook(BookId("book-1")) } returns emptyList()
            val repository = createRepository(dao)

            // When
            val result = repository.getTagsForBook("book-1")

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getTagsForBook returns tags for book`() =
        runTest {
            // Given
            val entities =
                listOf(
                    createTestTagEntity(id = "tag-1", slug = "romance", bookCount = 200),
                    createTestTagEntity(id = "tag-2", slug = "historical", bookCount = 80),
                    createTestTagEntity(id = "tag-3", slug = "regency", bookCount = 35),
                )
            val dao = createMockDao()
            everySuspend { dao.getTagsForBook(BookId("book-1")) } returns entities
            val repository = createRepository(dao)

            // When
            val result = repository.getTagsForBook("book-1")

            // Then
            assertEquals(3, result.size)
            assertEquals("romance", result[0].slug)
            assertEquals("historical", result[1].slug)
            assertEquals("regency", result[2].slug)
        }

    @Test
    fun `getTagsForBook transforms entities to domain models`() =
        runTest {
            // Given
            val entity =
                createTestTagEntity(
                    id = "tag-5",
                    slug = "mystery",
                    bookCount = 150,
                    createdAt = 1640995200000L,
                )
            val dao = createMockDao()
            everySuspend { dao.getTagsForBook(BookId("book-1")) } returns listOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.getTagsForBook("book-1")

            // Then
            assertEquals(1, result.size)
            assertEquals("tag-5", result[0].id)
            assertEquals("mystery", result[0].slug)
            assertEquals(150, result[0].bookCount)
            assertEquals(Timestamp(1640995200000L), result[0].createdAt)
        }

    @Test
    fun `getTagsForBook calls dao with correct BookId`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getTagsForBook(BookId("specific-book-id")) } returns emptyList()
            val repository = createRepository(dao)

            // When
            repository.getTagsForBook("specific-book-id")

            // Then
            verifySuspend { dao.getTagsForBook(BookId("specific-book-id")) }
        }

    // ========== getBookIdsForTag Tests ==========

    @Test
    fun `getBookIdsForTag returns empty list when no books have tag`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForTag("tag-1") } returns emptyList()
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForTag("tag-1")

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getBookIdsForTag returns book IDs with tag`() =
        runTest {
            // Given
            val bookIds =
                listOf(
                    BookId("book-1"),
                    BookId("book-2"),
                    BookId("book-3"),
                )
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForTag("tag-1") } returns bookIds
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForTag("tag-1")

            // Then
            assertEquals(3, result.size)
            assertEquals("book-1", result[0])
            assertEquals("book-2", result[1])
            assertEquals("book-3", result[2])
        }

    @Test
    fun `getBookIdsForTag extracts string values from BookId wrapper`() =
        runTest {
            // Given
            val bookIds =
                listOf(
                    BookId("uuid-12345"),
                    BookId("uuid-67890"),
                )
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForTag("tag-1") } returns bookIds
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForTag("tag-1")

            // Then
            assertEquals(listOf("uuid-12345", "uuid-67890"), result)
        }

    @Test
    fun `getBookIdsForTag calls dao with correct tagId`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForTag("specific-tag-id") } returns emptyList()
            val repository = createRepository(dao)

            // When
            repository.getBookIdsForTag("specific-tag-id")

            // Then
            verifySuspend { dao.getBookIdsForTag("specific-tag-id") }
        }

    // ========== Entity to Domain Conversion Tests ==========

    @Test
    fun `toDomain preserves all fields correctly`() =
        runTest {
            // Given
            val entity =
                TagEntity(
                    id = "tag-conversion-test",
                    slug = "test-conversion-slug",
                    bookCount = 42,
                    createdAt = Timestamp(1234567890123L),
                )
            val dao = createMockDao()
            everySuspend { dao.getById("tag-conversion-test") } returns entity
            val repository = createRepository(dao)

            // When
            val result = repository.getById("tag-conversion-test")

            // Then
            assertNotNull(result)
            assertEquals("tag-conversion-test", result.id)
            assertEquals("test-conversion-slug", result.slug)
            assertEquals(42, result.bookCount)
            assertEquals(Timestamp(1234567890123L), result.createdAt)
        }

    @Test
    fun `toDomain handles zero bookCount`() =
        runTest {
            // Given
            val entity = createTestTagEntity(bookCount = 0)
            val dao = createMockDao()
            everySuspend { dao.getAllTags() } returns listOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.getAll()

            // Then
            assertEquals(0, result[0].bookCount)
        }

    @Test
    fun `toDomain handles epoch zero timestamp`() =
        runTest {
            // Given
            val entity = createTestTagEntity(createdAt = 0L)
            val dao = createMockDao()
            everySuspend { dao.getAllTags() } returns listOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.getAll()

            // Then
            assertEquals(Timestamp(0L), result[0].createdAt)
        }

    @Test
    fun `toDomain handles large bookCount`() =
        runTest {
            // Given
            val entity = createTestTagEntity(bookCount = Int.MAX_VALUE)
            val dao = createMockDao()
            everySuspend { dao.getAllTags() } returns listOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.getAll()

            // Then
            assertEquals(Int.MAX_VALUE, result[0].bookCount)
        }

    @Test
    fun `toDomain handles slugs with multiple hyphens`() =
        runTest {
            // Given
            val entity = createTestTagEntity(slug = "enemies-to-lovers-to-friends")
            val dao = createMockDao()
            everySuspend { dao.getAllTags() } returns listOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.getAll()

            // Then
            assertEquals("enemies-to-lovers-to-friends", result[0].slug)
        }

    @Test
    fun `toDomain handles single word slugs`() =
        runTest {
            // Given
            val entity = createTestTagEntity(slug = "fantasy")
            val dao = createMockDao()
            everySuspend { dao.getAllTags() } returns listOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.getAll()

            // Then
            assertEquals("fantasy", result[0].slug)
        }

    // ========== Domain Model Behavior Tests ==========

    @Test
    fun `domain model displayName converts slug correctly`() =
        runTest {
            // Given
            val entity = createTestTagEntity(slug = "found-family")
            val dao = createMockDao()
            everySuspend { dao.getById("tag-1") } returns entity
            val repository = createRepository(dao)

            // When
            val result = repository.getById("tag-1")

            // Then
            assertNotNull(result)
            assertEquals("Found Family", result.displayName())
        }

    @Test
    fun `domain model displayName handles single word`() =
        runTest {
            // Given
            val entity = createTestTagEntity(slug = "fantasy")
            val dao = createMockDao()
            everySuspend { dao.getById("tag-1") } returns entity
            val repository = createRepository(dao)

            // When
            val result = repository.getById("tag-1")

            // Then
            assertNotNull(result)
            assertEquals("Fantasy", result.displayName())
        }

    @Test
    fun `domain model displayName handles multiple hyphens`() =
        runTest {
            // Given
            val entity = createTestTagEntity(slug = "enemies-to-lovers")
            val dao = createMockDao()
            everySuspend { dao.getById("tag-1") } returns entity
            val repository = createRepository(dao)

            // When
            val result = repository.getById("tag-1")

            // Then
            assertNotNull(result)
            assertEquals("Enemies To Lovers", result.displayName())
        }

    // ========== Multiple Items Tests ==========

    @Test
    fun `observeAll handles large number of tags`() =
        runTest {
            // Given
            val entities =
                (1..100).map { i ->
                    createTestTagEntity(
                        id = "tag-$i",
                        slug = "tag-slug-$i",
                        bookCount = i * 10,
                    )
                }
            val dao = createMockDao()
            every { dao.observeAllTags() } returns flowOf(entities)
            val repository = createRepository(dao)

            // When
            val result = repository.observeAll().first()

            // Then
            assertEquals(100, result.size)
            assertEquals("tag-1", result[0].id)
            assertEquals("tag-100", result[99].id)
        }

    @Test
    fun `getAll handles large number of tags`() =
        runTest {
            // Given
            val entities =
                (1..50).map { i ->
                    createTestTagEntity(
                        id = "tag-$i",
                        slug = "slug-$i",
                        bookCount = i,
                    )
                }
            val dao = createMockDao()
            everySuspend { dao.getAllTags() } returns entities
            val repository = createRepository(dao)

            // When
            val result = repository.getAll()

            // Then
            assertEquals(50, result.size)
        }

    @Test
    fun `getBookIdsForTag handles large number of books`() =
        runTest {
            // Given
            val bookIds = (1..200).map { BookId("book-$it") }
            val dao = createMockDao()
            everySuspend { dao.getBookIdsForTag("popular-tag") } returns bookIds
            val repository = createRepository(dao)

            // When
            val result = repository.getBookIdsForTag("popular-tag")

            // Then
            assertEquals(200, result.size)
            assertEquals("book-1", result[0])
            assertEquals("book-200", result[199])
        }
}
