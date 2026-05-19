package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.ActiveSessionDao
import com.calypsan.listenup.client.data.local.db.ActiveSessionWithDetails
import com.calypsan.listenup.client.domain.repository.ImageStorage
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for ActiveSessionRepositoryImpl.
 *
 * Tests the repository implementation that:
 * - Wraps ActiveSessionDao for data access
 * - Converts ActiveSessionWithDetails entities to ActiveSession domain models
 * - Provides reactive Flows for UI observation
 *
 * Uses Given-When-Then pattern for clarity.
 */
class ActiveSessionRepositoryImplTest {
    // --- Helper functions for creating test data ---

    private fun createMockDao(): ActiveSessionDao = mock<ActiveSessionDao>()

    private fun createMockImageStorage(): ImageStorage {
        val storage = mock<ImageStorage>()
        every { storage.exists(any()) } returns false
        return storage
    }

    private fun createTestActiveSessionWithDetails(
        sessionId: String = "session-1",
        userId: String = "user-1",
        bookId: String = "book-1",
        startedAt: Long = 1704067200000L, // 2024-01-01 00:00:00 UTC
        updatedAt: Long = 1704070800000L, // 2024-01-01 01:00:00 UTC
        displayName: String = "Test User",
        avatarType: String = "initials",
        avatarValue: String? = "TU",
        avatarColor: String = "#FF5733",
        title: String = "Test Book",
        coverBlurHash: String? = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
        authorName: String? = "Test Author",
    ): ActiveSessionWithDetails =
        ActiveSessionWithDetails(
            sessionId = sessionId,
            userId = userId,
            bookId = bookId,
            startedAt = startedAt,
            updatedAt = updatedAt,
            displayName = displayName,
            avatarType = avatarType,
            avatarValue = avatarValue,
            avatarColor = avatarColor,
            title = title,
            coverBlurHash = coverBlurHash,
            authorName = authorName,
        )

    private fun createRepository(
        dao: ActiveSessionDao,
        imageStorage: ImageStorage = createMockImageStorage(),
    ): ActiveSessionRepositoryImpl = ActiveSessionRepositoryImpl(dao, imageStorage)

    // ========== observeActiveSessions Tests ==========

    @Test
    fun `observeActiveSessions returns empty list when no sessions exist`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `observeActiveSessions converts single entity to domain model`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity = createTestActiveSessionWithDetails()
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            assertEquals(1, result.size)

            val session = result.first()
            assertEquals("session-1", session.sessionId)
            assertEquals("user-1", session.userId)
            assertEquals("book-1", session.bookId)
            assertEquals(1704067200000L, session.startedAtMs)
            assertEquals(1704070800000L, session.updatedAtMs)
        }

    @Test
    fun `observeActiveSessions converts nested SessionUser correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity =
                createTestActiveSessionWithDetails(
                    displayName = "John Doe",
                    avatarType = "image",
                    avatarValue = "/avatars/john.jpg",
                    avatarColor = "#4287f5",
                )
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            val user = result.first().user

            assertEquals("John Doe", user.displayName)
            assertEquals("image", user.avatarType)
            assertEquals("/avatars/john.jpg", user.avatarValue)
            assertEquals("#4287f5", user.avatarColor)
        }

    @Test
    fun `observeActiveSessions converts nested SessionBook correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity =
                createTestActiveSessionWithDetails(
                    bookId = "book-42",
                    title = "The Way of Kings",
                    coverBlurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
                    authorName = "Brandon Sanderson",
                )
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            val book = result.first().book

            assertEquals("book-42", book.id)
            assertEquals("The Way of Kings", book.title)
            assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", book.coverBlurHash)
            assertEquals("Brandon Sanderson", book.authorName)
        }

    @Test
    fun `observeActiveSessions handles null avatarValue`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity =
                createTestActiveSessionWithDetails(
                    avatarType = "initials",
                    avatarValue = null,
                )
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            assertNull(result.first().user.avatarValue)
        }

    @Test
    fun `observeActiveSessions handles null coverBlurHash`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity = createTestActiveSessionWithDetails(coverBlurHash = null)
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            assertNull(result.first().book.coverBlurHash)
        }

    @Test
    fun `observeActiveSessions handles null authorName`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity = createTestActiveSessionWithDetails(authorName = null)
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            assertNull(result.first().book.authorName)
        }

    @Test
    fun `observeActiveSessions handles all nullable fields as null`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity =
                createTestActiveSessionWithDetails(
                    avatarValue = null,
                    coverBlurHash = null,
                    authorName = null,
                )
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            val session = result.first()
            assertNull(session.user.avatarValue)
            assertNull(session.book.coverBlurHash)
            assertNull(session.book.authorName)
        }

    @Test
    fun `observeActiveSessions converts multiple entities correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entities =
                listOf(
                    createTestActiveSessionWithDetails(
                        sessionId = "session-1",
                        userId = "user-1",
                        displayName = "Alice",
                        title = "Book One",
                    ),
                    createTestActiveSessionWithDetails(
                        sessionId = "session-2",
                        userId = "user-2",
                        displayName = "Bob",
                        title = "Book Two",
                    ),
                    createTestActiveSessionWithDetails(
                        sessionId = "session-3",
                        userId = "user-3",
                        displayName = "Charlie",
                        title = "Book Three",
                    ),
                )
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(entities)
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            assertEquals(3, result.size)

            assertEquals("session-1", result[0].sessionId)
            assertEquals("Alice", result[0].user.displayName)
            assertEquals("Book One", result[0].book.title)

            assertEquals("session-2", result[1].sessionId)
            assertEquals("Bob", result[1].user.displayName)
            assertEquals("Book Two", result[1].book.title)

            assertEquals("session-3", result[2].sessionId)
            assertEquals("Charlie", result[2].user.displayName)
            assertEquals("Book Three", result[2].book.title)
        }

    @Test
    fun `observeActiveSessions passes currentUserId to DAO`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "user-12345"
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            repository.observeActiveSessions(currentUserId).first()

            // Then
            verify { dao.observeActiveSessions(currentUserId) }
        }

    @Test
    fun `observeActiveSessions emits updates when DAO flow updates`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val initialEntities = listOf(createTestActiveSessionWithDetails(sessionId = "session-1"))
            val updatedEntities =
                listOf(
                    createTestActiveSessionWithDetails(sessionId = "session-1"),
                    createTestActiveSessionWithDetails(sessionId = "session-2"),
                )

            every { dao.observeActiveSessions(currentUserId) } returns
                flowOf(initialEntities, updatedEntities)
            val repository = createRepository(dao)

            // When
            val emissions = repository.observeActiveSessions(currentUserId).toList()

            // Then
            assertEquals(2, emissions.size)
            assertEquals(1, emissions[0].size)
            assertEquals(2, emissions[1].size)
        }

    // ========== observeActiveCount Tests ==========

    @Test
    fun `observeActiveCount returns zero when no sessions exist`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            every { dao.observeActiveCount(currentUserId) } returns flowOf(0)
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveCount(currentUserId).first()

            // Then
            assertEquals(0, result)
        }

    @Test
    fun `observeActiveCount returns correct count`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            every { dao.observeActiveCount(currentUserId) } returns flowOf(5)
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveCount(currentUserId).first()

            // Then
            assertEquals(5, result)
        }

    @Test
    fun `observeActiveCount passes currentUserId to DAO`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "user-abc123"
            every { dao.observeActiveCount(currentUserId) } returns flowOf(0)
            val repository = createRepository(dao)

            // When
            repository.observeActiveCount(currentUserId).first()

            // Then
            verify { dao.observeActiveCount(currentUserId) }
        }

    @Test
    fun `observeActiveCount emits updates when count changes`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            every { dao.observeActiveCount(currentUserId) } returns flowOf(0, 1, 3, 2)
            val repository = createRepository(dao)

            // When
            val emissions = repository.observeActiveCount(currentUserId).toList()

            // Then
            assertEquals(4, emissions.size)
            assertEquals(0, emissions[0])
            assertEquals(1, emissions[1])
            assertEquals(3, emissions[2])
            assertEquals(2, emissions[3])
        }

    @Test
    fun `observeActiveCount handles large counts`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            every { dao.observeActiveCount(currentUserId) } returns flowOf(999)
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveCount(currentUserId).first()

            // Then
            assertEquals(999, result)
        }

    // ========== Domain Model Conversion Edge Cases ==========

    @Test
    fun `toDomain preserves bookId in both session and nested book`() =
        runTest {
            // Given - bookId should appear in both the session and SessionBook
            val dao = createMockDao()
            val currentUserId = "current-user"
            val bookId = "unique-book-id-123"
            val entity = createTestActiveSessionWithDetails(bookId = bookId)
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            val session = result.first()

            // Both should have the same bookId
            assertEquals(bookId, session.bookId)
            assertEquals(bookId, session.book.id)
        }

    @Test
    fun `toDomain handles empty string values`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity =
                createTestActiveSessionWithDetails(
                    displayName = "",
                    avatarColor = "",
                    title = "",
                )
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            val session = result.first()

            assertEquals("", session.user.displayName)
            assertEquals("", session.user.avatarColor)
            assertEquals("", session.book.title)
        }

    @Test
    fun `toDomain handles special characters in strings`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity =
                createTestActiveSessionWithDetails(
                    displayName = "John O'Brien \"Johnny\"",
                    title = "Book: The Beginning & More <Special>",
                    authorName = "Author\nWith\tWhitespace",
                )
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            val session = result.first()

            assertEquals("John O'Brien \"Johnny\"", session.user.displayName)
            assertEquals("Book: The Beginning & More <Special>", session.book.title)
            assertEquals("Author\nWith\tWhitespace", session.book.authorName)
        }

    @Test
    fun `toDomain handles unicode characters`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity =
                createTestActiveSessionWithDetails(
                    displayName = "Jean-Pierre Dupont",
                    title = "Les Miserables",
                    authorName = "Victor Hugo",
                )
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            val session = result.first()

            assertEquals("Jean-Pierre Dupont", session.user.displayName)
            assertEquals("Les Miserables", session.book.title)
        }

    @Test
    fun `toDomain handles zero timestamps`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity =
                createTestActiveSessionWithDetails(
                    startedAt = 0L,
                    updatedAt = 0L,
                )
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            val session = result.first()

            assertEquals(0L, session.startedAtMs)
            assertEquals(0L, session.updatedAtMs)
        }

    @Test
    fun `toDomain handles maximum Long timestamps`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity =
                createTestActiveSessionWithDetails(
                    startedAt = Long.MAX_VALUE,
                    updatedAt = Long.MAX_VALUE,
                )
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeActiveSessions(currentUserId).first()

            // Then
            val session = result.first()

            assertEquals(Long.MAX_VALUE, session.startedAtMs)
            assertEquals(Long.MAX_VALUE, session.updatedAtMs)
        }

    // ========== Integration Behavior Tests ==========

    @Test
    fun `repository correctly implements ActiveSessionRepository interface`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeActiveSessions("user-1") } returns flowOf(emptyList())
            every { dao.observeActiveCount("user-1") } returns flowOf(0)

            // When
            val repository: com.calypsan.listenup.client.domain.repository.ActiveSessionRepository =
                createRepository(dao)

            // Then - both methods are accessible via interface
            val sessions = repository.observeActiveSessions("user-1").first()
            val count = repository.observeActiveCount("user-1").first()

            assertTrue(sessions.isEmpty())
            assertEquals(0, count)
        }

    @Test
    fun `observeActiveSessions and observeActiveCount can be used independently`() =
        runTest {
            // Given
            val dao = createMockDao()
            val currentUserId = "current-user"
            val entity = createTestActiveSessionWithDetails()
            every { dao.observeActiveSessions(currentUserId) } returns flowOf(listOf(entity))
            every { dao.observeActiveCount(currentUserId) } returns flowOf(1)
            val repository = createRepository(dao)

            // When & Then - both flows work independently
            val sessions = repository.observeActiveSessions(currentUserId).first()
            assertEquals(1, sessions.size)

            val count = repository.observeActiveCount(currentUserId).first()
            assertEquals(1, count)
        }
}
