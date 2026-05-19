package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.ActivityDao
import com.calypsan.listenup.client.data.local.db.ActivityEntity
import com.calypsan.listenup.client.data.remote.ActivityFeedApiContract
import com.calypsan.listenup.client.domain.model.Activity
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for ActivityRepositoryImpl.
 *
 * Tests cover:
 * - All interface methods (observeRecent, getOlderThan, getNewestTimestamp)
 * - Entity to domain model conversion including nested classes
 * - Handling of nullable fields (book, milestoneUnit, shelfId, shelfName, avatarValue)
 * - Various activity types (started_book, finished_book, listening_milestone, etc.)
 *
 * Uses Mokkery for mocking and follows Given-When-Then style.
 */
class ActivityRepositoryImplTest {
    // ========== Test Fixtures ==========

    private fun createMockDao(): ActivityDao = mock<ActivityDao>()

    private fun createMockApi(): ActivityFeedApiContract = mock<ActivityFeedApiContract>()

    private fun createRepository(
        dao: ActivityDao = createMockDao(),
        api: ActivityFeedApiContract = createMockApi(),
    ): ActivityRepositoryImpl = ActivityRepositoryImpl(dao, api)

    // ========== Test Data Factories ==========

    /**
     * Creates a full ActivityEntity with all fields populated.
     * Use this as the base for most tests.
     */
    private fun createActivityEntity(
        id: String = "activity-1",
        userId: String = "user-1",
        type: String = "finished_book",
        createdAt: Long = 1704067200000L,
        userDisplayName: String = "John Smith",
        userAvatarColor: String = "#FF5733",
        userAvatarType: String = "initials",
        userAvatarValue: String? = "JS",
        bookId: String? = "book-1",
        bookTitle: String? = "The Way of Kings",
        bookAuthorName: String? = "Brandon Sanderson",
        bookCoverPath: String? = "/covers/book-1.jpg",
        isReread: Boolean = false,
        durationMs: Long = 3600000L,
        milestoneValue: Int = 0,
        milestoneUnit: String? = null,
        shelfId: String? = null,
        shelfName: String? = null,
    ): ActivityEntity =
        ActivityEntity(
            id = id,
            userId = userId,
            type = type,
            createdAt = createdAt,
            userDisplayName = userDisplayName,
            userAvatarColor = userAvatarColor,
            userAvatarType = userAvatarType,
            userAvatarValue = userAvatarValue,
            bookId = bookId,
            bookTitle = bookTitle,
            bookAuthorName = bookAuthorName,
            bookCoverPath = bookCoverPath,
            isReread = isReread,
            durationMs = durationMs,
            milestoneValue = milestoneValue,
            milestoneUnit = milestoneUnit,
            shelfId = shelfId,
            shelfName = shelfName,
        )

    /**
     * Creates an ActivityEntity for a milestone activity (no book).
     */
    private fun createMilestoneActivityEntity(
        id: String = "activity-milestone",
        type: String = "listening_milestone",
        milestoneValue: Int = 100,
        milestoneUnit: String = "hours",
    ): ActivityEntity =
        createActivityEntity(
            id = id,
            type = type,
            bookId = null,
            bookTitle = null,
            bookAuthorName = null,
            bookCoverPath = null,
            milestoneValue = milestoneValue,
            milestoneUnit = milestoneUnit,
        )

    /**
     * Creates an ActivityEntity for a shelf created activity.
     */
    private fun createShelfActivityEntity(
        id: String = "activity-shelf",
        shelfId: String = "shelf-1",
        shelfName: String = "Fantasy Favorites",
    ): ActivityEntity =
        createActivityEntity(
            id = id,
            type = "shelf_created",
            bookId = null,
            bookTitle = null,
            bookAuthorName = null,
            bookCoverPath = null,
            shelfId = shelfId,
            shelfName = shelfName,
        )

    // ========== observeRecent Tests ==========

    @Test
    fun `observeRecent returns empty list when no activities`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeRecent(any()) } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `observeRecent returns activities from dao`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entities =
                listOf(
                    createActivityEntity(id = "act-1"),
                    createActivityEntity(id = "act-2"),
                )
            every { dao.observeRecent(10) } returns flowOf(entities)
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first()

            // Then
            assertEquals(2, result.size)
            assertEquals("act-1", result[0].id)
            assertEquals("act-2", result[1].id)
        }

    @Test
    fun `observeRecent passes limit parameter to dao`() =
        runTest {
            // Given
            val dao = createMockDao()
            every { dao.observeRecent(5) } returns flowOf(emptyList())
            val repository = createRepository(dao)

            // When
            repository.observeRecent(5).first()

            // Then - verify correct limit was passed (verified by the specific mock setup)
            // The test would fail if observeRecent(5) wasn't called
        }

    @Test
    fun `observeRecent emits updates when flow updates`() =
        runTest {
            // Given
            val dao = createMockDao()
            val flowSource = MutableStateFlow<List<ActivityEntity>>(emptyList())
            every { dao.observeRecent(any()) } returns flowSource
            val repository = createRepository(dao)

            // When - initial emission
            val result1 = repository.observeRecent(10).first()

            // Then - initially empty
            assertTrue(result1.isEmpty())

            // When - flow updates
            flowSource.value = listOf(createActivityEntity(id = "new-activity"))
            val result2 = repository.observeRecent(10).first()

            // Then - updated list
            assertEquals(1, result2.size)
            assertEquals("new-activity", result2[0].id)
        }

    // ========== getOlderThan Tests ==========

    @Test
    fun `getOlderThan returns empty list when no older activities`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getOlderThan(any(), any()) } returns emptyList()
            val repository = createRepository(dao)

            // When
            val result = repository.getOlderThan(beforeMs = 1704067200000L, limit = 10)

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getOlderThan returns activities from dao`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entities =
                listOf(
                    createActivityEntity(id = "old-1", createdAt = 1704000000000L),
                    createActivityEntity(id = "old-2", createdAt = 1703900000000L),
                )
            everySuspend { dao.getOlderThan(1704067200000L, 10) } returns entities
            val repository = createRepository(dao)

            // When
            val result = repository.getOlderThan(beforeMs = 1704067200000L, limit = 10)

            // Then
            assertEquals(2, result.size)
            assertEquals("old-1", result[0].id)
            assertEquals("old-2", result[1].id)
        }

    @Test
    fun `getOlderThan passes correct parameters to dao`() =
        runTest {
            // Given
            val dao = createMockDao()
            val beforeMs = 1704067200000L
            val limit = 25
            everySuspend { dao.getOlderThan(beforeMs, limit) } returns emptyList()
            val repository = createRepository(dao)

            // When
            repository.getOlderThan(beforeMs = beforeMs, limit = limit)

            // Then
            verifySuspend { dao.getOlderThan(beforeMs, limit) }
        }

    // ========== getNewestTimestamp Tests ==========

    @Test
    fun `getNewestTimestamp returns null when no activities`() =
        runTest {
            // Given
            val dao = createMockDao()
            everySuspend { dao.getNewestTimestamp() } returns null
            val repository = createRepository(dao)

            // When
            val result = repository.getNewestTimestamp()

            // Then
            assertNull(result)
        }

    @Test
    fun `getNewestTimestamp returns timestamp from dao`() =
        runTest {
            // Given
            val dao = createMockDao()
            val expectedTimestamp = 1704067200000L
            everySuspend { dao.getNewestTimestamp() } returns expectedTimestamp
            val repository = createRepository(dao)

            // When
            val result = repository.getNewestTimestamp()

            // Then
            assertEquals(expectedTimestamp, result)
        }

    // ========== Entity to Domain Conversion - Basic Fields ==========

    @Test
    fun `toDomain converts id correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(id = "unique-activity-id")
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("unique-activity-id", result.id)
        }

    @Test
    fun `toDomain converts type correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(type = "finished_book")
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("finished_book", result.type)
        }

    @Test
    fun `toDomain converts userId correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(userId = "user-123")
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("user-123", result.userId)
        }

    @Test
    fun `toDomain converts createdAt to createdAtMs correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val timestamp = 1704067200000L
            val entity = createActivityEntity(createdAt = timestamp)
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals(timestamp, result.createdAtMs)
        }

    @Test
    fun `toDomain converts isReread correctly when true`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(isReread = true)
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertTrue(result.isReread)
        }

    @Test
    fun `toDomain converts isReread correctly when false`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(isReread = false)
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals(false, result.isReread)
        }

    @Test
    fun `toDomain converts durationMs correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val duration = 7200000L // 2 hours
            val entity = createActivityEntity(durationMs = duration)
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals(duration, result.durationMs)
        }

    @Test
    fun `toDomain converts milestoneValue correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createMilestoneActivityEntity(milestoneValue = 500)
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals(500, result.milestoneValue)
        }

    @Test
    fun `toDomain converts milestoneUnit correctly when present`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createMilestoneActivityEntity(milestoneUnit = "hours")
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("hours", result.milestoneUnit)
        }

    @Test
    fun `toDomain converts milestoneUnit correctly when null`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(milestoneUnit = null)
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertNull(result.milestoneUnit)
        }

    @Test
    fun `toDomain converts shelfId correctly when present`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createShelfActivityEntity(shelfId = "shelf-abc-123")
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("shelf-abc-123", result.shelfId)
        }

    @Test
    fun `toDomain converts shelfId correctly when null`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(shelfId = null)
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertNull(result.shelfId)
        }

    @Test
    fun `toDomain converts shelfName correctly when present`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createShelfActivityEntity(shelfName = "My Reading List")
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("My Reading List", result.shelfName)
        }

    @Test
    fun `toDomain converts shelfName correctly when null`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(shelfName = null)
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertNull(result.shelfName)
        }

    // ========== Entity to Domain Conversion - ActivityUser ==========

    @Test
    fun `toDomain converts user displayName correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(userDisplayName = "Jane Doe")
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("Jane Doe", result.user.displayName)
        }

    @Test
    fun `toDomain converts user avatarColor correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(userAvatarColor = "#3498DB")
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("#3498DB", result.user.avatarColor)
        }

    @Test
    fun `toDomain converts user avatarType correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(userAvatarType = "emoji")
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("emoji", result.user.avatarType)
        }

    @Test
    fun `toDomain converts user avatarValue correctly when present`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(userAvatarValue = "AB")
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("AB", result.user.avatarValue)
        }

    @Test
    fun `toDomain converts user avatarValue correctly when null`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createActivityEntity(userAvatarValue = null)
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertNull(result.user.avatarValue)
        }

    // ========== Entity to Domain Conversion - ActivityBook ==========

    @Test
    fun `toDomain creates ActivityBook when bookId and bookTitle are present`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    bookId = "book-123",
                    bookTitle = "Mistborn",
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertNotNull(result.book)
            assertEquals("book-123", result.book?.id)
            assertEquals("Mistborn", result.book?.title)
        }

    @Test
    fun `toDomain returns null book when bookId is null`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    bookId = null,
                    bookTitle = "Some Title", // Even if title is present
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertNull(result.book)
        }

    @Test
    fun `toDomain returns null book when bookTitle is null`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    bookId = "book-123", // Even if id is present
                    bookTitle = null,
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertNull(result.book)
        }

    @Test
    fun `toDomain returns null book when both bookId and bookTitle are null`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity = createMilestoneActivityEntity() // No book info
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertNull(result.book)
        }

    @Test
    fun `toDomain converts book authorName correctly when present`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    bookId = "book-1",
                    bookTitle = "Elantris",
                    bookAuthorName = "Brandon Sanderson",
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertNotNull(result.book)
            assertEquals("Brandon Sanderson", result.book?.authorName)
        }

    @Test
    fun `toDomain converts book authorName correctly when null`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    bookId = "book-1",
                    bookTitle = "Unknown Author Book",
                    bookAuthorName = null,
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertNotNull(result.book)
            assertNull(result.book?.authorName)
        }

    @Test
    fun `toDomain converts book coverPath correctly when present`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    bookId = "book-1",
                    bookTitle = "Test Book",
                    bookCoverPath = "/covers/book-1.webp",
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertNotNull(result.book)
            assertEquals("/covers/book-1.webp", result.book?.coverPath)
        }

    @Test
    fun `toDomain converts book coverPath correctly when null`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    bookId = "book-1",
                    bookTitle = "No Cover Book",
                    bookCoverPath = null,
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertNotNull(result.book)
            assertNull(result.book?.coverPath)
        }

    // ========== Activity Type Tests ==========

    @Test
    fun `toDomain handles started_book activity type`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    type = "started_book",
                    bookId = "book-1",
                    bookTitle = "New Read",
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("started_book", result.type)
            assertNotNull(result.book)
        }

    @Test
    fun `toDomain handles finished_book activity type`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    type = "finished_book",
                    bookId = "book-1",
                    bookTitle = "Completed Read",
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("finished_book", result.type)
            assertNotNull(result.book)
        }

    @Test
    fun `toDomain handles streak_milestone activity type`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createMilestoneActivityEntity(
                    type = "streak_milestone",
                    milestoneValue = 30,
                    milestoneUnit = "days",
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("streak_milestone", result.type)
            assertEquals(30, result.milestoneValue)
            assertEquals("days", result.milestoneUnit)
            assertNull(result.book)
        }

    @Test
    fun `toDomain handles listening_milestone activity type`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createMilestoneActivityEntity(
                    type = "listening_milestone",
                    milestoneValue = 100,
                    milestoneUnit = "hours",
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("listening_milestone", result.type)
            assertEquals(100, result.milestoneValue)
            assertEquals("hours", result.milestoneUnit)
        }

    @Test
    fun `toDomain handles shelf_created activity type`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createShelfActivityEntity(
                    shelfId = "shelf-xyz",
                    shelfName = "Sci-Fi Favorites",
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("shelf_created", result.type)
            assertEquals("shelf-xyz", result.shelfId)
            assertEquals("Sci-Fi Favorites", result.shelfName)
            assertNull(result.book)
        }

    @Test
    fun `toDomain handles listening_session activity type`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    type = "listening_session",
                    bookId = "book-1",
                    bookTitle = "Podcast Episode",
                    durationMs = 1800000L, // 30 minutes
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("listening_session", result.type)
            assertEquals(1800000L, result.durationMs)
            assertNotNull(result.book)
        }

    // ========== Edge Cases ==========

    @Test
    fun `toDomain handles empty string fields`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    userDisplayName = "",
                    userAvatarColor = "",
                    userAvatarType = "",
                    userAvatarValue = "",
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals("", result.user.displayName)
            assertEquals("", result.user.avatarColor)
            assertEquals("", result.user.avatarType)
            assertEquals("", result.user.avatarValue)
        }

    @Test
    fun `toDomain handles zero values`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    createdAt = 0L,
                    durationMs = 0L,
                    milestoneValue = 0,
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals(0L, result.createdAtMs)
            assertEquals(0L, result.durationMs)
            assertEquals(0, result.milestoneValue)
        }

    @Test
    fun `toDomain handles large timestamp values`() =
        runTest {
            // Given
            val dao = createMockDao()
            val farFutureTimestamp = 4102444800000L // Year 2100
            val entity = createActivityEntity(createdAt = farFutureTimestamp)
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertEquals(farFutureTimestamp, result.createdAtMs)
        }

    @Test
    fun `toDomain handles reread activity with book info`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    type = "finished_book",
                    isReread = true,
                    bookId = "book-1",
                    bookTitle = "Re-read Favorite",
                )
            every { dao.observeRecent(any()) } returns flowOf(listOf(entity))
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first().first()

            // Then
            assertTrue(result.isReread)
            assertNotNull(result.book)
            assertEquals("finished_book", result.type)
        }

    @Test
    fun `toDomain handles multiple activities in single list`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entities =
                listOf(
                    createActivityEntity(id = "act-1", type = "finished_book"),
                    createMilestoneActivityEntity(id = "act-2", type = "listening_milestone"),
                    createShelfActivityEntity(id = "act-3"),
                )
            every { dao.observeRecent(any()) } returns flowOf(entities)
            val repository = createRepository(dao)

            // When
            val result = repository.observeRecent(10).first()

            // Then
            assertEquals(3, result.size)
            assertEquals("finished_book", result[0].type)
            assertNotNull(result[0].book)
            assertEquals("listening_milestone", result[1].type)
            assertNull(result[1].book)
            assertEquals("shelf_created", result[2].type)
            assertNotNull(result[2].shelfId)
        }

    @Test
    fun `getOlderThan converts entities correctly`() =
        runTest {
            // Given
            val dao = createMockDao()
            val entity =
                createActivityEntity(
                    id = "older-act",
                    type = "started_book",
                    userId = "user-456",
                    userDisplayName = "Bob",
                    bookId = "book-old",
                    bookTitle = "Old Book",
                )
            everySuspend { dao.getOlderThan(any(), any()) } returns listOf(entity)
            val repository = createRepository(dao)

            // When
            val result = repository.getOlderThan(beforeMs = 1704067200000L, limit = 10)

            // Then
            assertEquals(1, result.size)
            val activity = result[0]
            assertEquals("older-act", activity.id)
            assertEquals("started_book", activity.type)
            assertEquals("user-456", activity.userId)
            assertEquals("Bob", activity.user.displayName)
            assertNotNull(activity.book)
            assertEquals("book-old", activity.book?.id)
            assertEquals("Old Book", activity.book?.title)
        }
}
