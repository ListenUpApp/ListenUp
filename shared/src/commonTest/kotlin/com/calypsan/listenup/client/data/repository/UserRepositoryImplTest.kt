package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.remote.SessionApiContract
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for UserRepositoryImpl.
 *
 * Tests cover:
 * - observeCurrentUser(): Flow emissions for user present, null, and changes
 * - observeIsAdmin(): Flow emissions for admin/non-admin/null states
 * - getCurrentUser(): Suspend function for one-time user retrieval
 * - Entity to domain conversion: All field mappings verified
 *
 * Uses Mokkery for mocking UserDao and follows Given-When-Then style.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserRepositoryImplTest {
    // ========== Test Data Factories ==========

    /**
     * Creates a test UserEntity with all fields populated.
     * Provides sensible defaults while allowing field overrides for specific test scenarios.
     */
    private fun createTestUserEntity(
        id: String = "user-001",
        email: String = "test@example.com",
        displayName: String = "Test User",
        firstName: String? = "Test",
        lastName: String? = "User",
        isRoot: Boolean = false,
        avatarType: String = "auto",
        avatarValue: String? = null,
        avatarColor: String = "#3B82F6",
        tagline: String? = "Audiobook enthusiast",
        createdAt: Long = 1704067200000L, // 2024-01-01 00:00:00 UTC
        updatedAt: Long = 1704153600000L, // 2024-01-02 00:00:00 UTC
    ): UserEntity =
        UserEntity(
            id =
                UserId(id),
            email = email,
            displayName = displayName,
            firstName = firstName,
            lastName = lastName,
            isRoot = isRoot,
            avatarType = avatarType,
            avatarValue = avatarValue,
            avatarColor = avatarColor,
            tagline = tagline,
            createdAt =
                com.calypsan.listenup.client.core
                    .Timestamp(createdAt),
            updatedAt =
                com.calypsan.listenup.client.core
                    .Timestamp(updatedAt),
        )

    private fun createMockUserDao(): UserDao = mock<UserDao>()

    private fun createMockSessionApi(): SessionApiContract = mock<SessionApiContract>()

    // ========== observeCurrentUser Tests ==========

    @Test
    fun `observeCurrentUser emits User when user exists`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity()
            every { userDao.observeCurrentUser() } returns flowOf(entity)
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.observeCurrentUser().first()

            // Then
            assertNotNull(user)
            assertEquals("user-001", user.id.value)
            assertEquals("test@example.com", user.email)
            assertEquals("Test User", user.displayName)
        }

    @Test
    fun `observeCurrentUser emits null when no user exists`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            every { userDao.observeCurrentUser() } returns flowOf(null)
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.observeCurrentUser().first()

            // Then
            assertNull(user)
        }

    @Test
    fun `observeCurrentUser emits changes when user updates`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val initialUser = createTestUserEntity(displayName = "Initial Name")
            val updatedUser = createTestUserEntity(displayName = "Updated Name")
            // Flow emits null -> user1 -> user2 -> null
            every { userDao.observeCurrentUser() } returns flowOf(null, initialUser, updatedUser, null)
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val emissions = repository.observeCurrentUser().take(4).toList()

            // Then
            assertNull(emissions[0])
            assertEquals("Initial Name", emissions[1]?.displayName)
            assertEquals("Updated Name", emissions[2]?.displayName)
            assertNull(emissions[3])
        }

    // ========== observeIsAdmin Tests ==========

    @Test
    fun `observeIsAdmin emits true when user isRoot is true`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(isRoot = true)
            every { userDao.observeCurrentUser() } returns flowOf(entity)
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val isAdmin = repository.observeIsAdmin().first()

            // Then
            assertTrue(isAdmin)
        }

    @Test
    fun `observeIsAdmin emits false when user isRoot is false`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(isRoot = false)
            every { userDao.observeCurrentUser() } returns flowOf(entity)
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val isAdmin = repository.observeIsAdmin().first()

            // Then
            assertFalse(isAdmin)
        }

    @Test
    fun `observeIsAdmin emits false when user is null`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            every { userDao.observeCurrentUser() } returns flowOf(null)
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val isAdmin = repository.observeIsAdmin().first()

            // Then
            assertFalse(isAdmin)
        }

    @Test
    fun `observeIsAdmin emits changes when admin status changes`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val regularUser = createTestUserEntity(isRoot = false)
            val adminUser = createTestUserEntity(isRoot = true)
            // Flow: null -> regular -> admin -> regular
            every { userDao.observeCurrentUser() } returns flowOf(null, regularUser, adminUser, regularUser)
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val emissions = repository.observeIsAdmin().take(4).toList()

            // Then
            assertFalse(emissions[0]) // null user -> not admin
            assertFalse(emissions[1]) // regular user -> not admin
            assertTrue(emissions[2]) // admin user -> admin
            assertFalse(emissions[3]) // demoted -> not admin
        }

    @Test
    fun `observeIsAdmin reacts to MutableStateFlow changes`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val userFlow = MutableStateFlow<UserEntity?>(null)
            every { userDao.observeCurrentUser() } returns userFlow
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When/Then - initial state
            assertFalse(repository.observeIsAdmin().first())

            // When/Then - user becomes admin
            userFlow.value = createTestUserEntity(isRoot = true)
            assertTrue(repository.observeIsAdmin().first())

            // When/Then - user demoted
            userFlow.value = createTestUserEntity(isRoot = false)
            assertFalse(repository.observeIsAdmin().first())

            // When/Then - user logs out
            userFlow.value = null
            assertFalse(repository.observeIsAdmin().first())
        }

    // ========== getCurrentUser Tests ==========

    @Test
    fun `getCurrentUser returns User when user exists`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity()
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertNotNull(user)
            assertEquals("user-001", user.id.value)
            assertEquals("test@example.com", user.email)
        }

    @Test
    fun `getCurrentUser returns null when no user exists`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            everySuspend { userDao.getCurrentUser() } returns null
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertNull(user)
        }

    // ========== Entity to Domain Conversion Tests ==========

    @Test
    fun `toDomain converts id correctly`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(id = "unique-user-id-123")
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertEquals("unique-user-id-123", user?.id?.value)
        }

    @Test
    fun `toDomain converts email correctly`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(email = "user@listenup.app")
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertEquals("user@listenup.app", user?.email)
        }

    @Test
    fun `toDomain converts displayName correctly`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(displayName = "Bookworm Betty")
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertEquals("Bookworm Betty", user?.displayName)
        }

    @Test
    fun `toDomain converts firstName correctly when present`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(firstName = "Elizabeth")
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertEquals("Elizabeth", user?.firstName)
        }

    @Test
    fun `toDomain converts firstName correctly when null`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(firstName = null)
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertNull(user?.firstName)
        }

    @Test
    fun `toDomain converts lastName correctly when present`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(lastName = "Bennet")
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertEquals("Bennet", user?.lastName)
        }

    @Test
    fun `toDomain converts lastName correctly when null`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(lastName = null)
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertNull(user?.lastName)
        }

    @Test
    fun `toDomain converts isRoot to isAdmin correctly when true`() =
        runTest {
            // Given - entity uses isRoot, domain uses isAdmin
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(isRoot = true)
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertTrue(user?.isAdmin == true)
        }

    @Test
    fun `toDomain converts isRoot to isAdmin correctly when false`() =
        runTest {
            // Given - entity uses isRoot, domain uses isAdmin
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(isRoot = false)
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertFalse(user?.isAdmin == true)
        }

    @Test
    fun `toDomain converts avatarType correctly for auto avatar`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(avatarType = "auto")
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertEquals("auto", user?.avatarType)
        }

    @Test
    fun `toDomain converts avatarType correctly for image avatar`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(avatarType = "image")
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertEquals("image", user?.avatarType)
        }

    @Test
    fun `toDomain converts avatarValue correctly when present`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity =
                createTestUserEntity(
                    avatarType = "image",
                    avatarValue = "/avatars/user-001.jpg",
                )
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertEquals("/avatars/user-001.jpg", user?.avatarValue)
        }

    @Test
    fun `toDomain converts avatarValue correctly when null`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(avatarValue = null)
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertNull(user?.avatarValue)
        }

    @Test
    fun `toDomain converts avatarColor correctly`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(avatarColor = "#EF4444")
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertEquals("#EF4444", user?.avatarColor)
        }

    @Test
    fun `toDomain converts tagline correctly when present`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(tagline = "Fantasy is my escape")
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertEquals("Fantasy is my escape", user?.tagline)
        }

    @Test
    fun `toDomain converts tagline correctly when null`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity = createTestUserEntity(tagline = null)
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertNull(user?.tagline)
        }

    @Test
    fun `toDomain converts createdAt to createdAtMs correctly`() =
        runTest {
            // Given - entity uses createdAt, domain uses createdAtMs
            val userDao = createMockUserDao()
            val timestamp = 1704067200000L // 2024-01-01 00:00:00 UTC
            val entity = createTestUserEntity(createdAt = timestamp)
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertEquals(timestamp, user?.createdAtMs)
        }

    @Test
    fun `toDomain converts updatedAt to updatedAtMs correctly`() =
        runTest {
            // Given - entity uses updatedAt, domain uses updatedAtMs
            val userDao = createMockUserDao()
            val timestamp = 1704153600000L // 2024-01-02 00:00:00 UTC
            val entity = createTestUserEntity(updatedAt = timestamp)
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertEquals(timestamp, user?.updatedAtMs)
        }

    @Test
    fun `toDomain converts all fields correctly in comprehensive test`() =
        runTest {
            // Given - test all fields together to ensure complete conversion
            val userDao = createMockUserDao()
            val entity =
                UserEntity(
                    id =
                        UserId("admin-user-42"),
                    email = "admin@listenup.app",
                    displayName = "Admin Alice",
                    firstName = "Alice",
                    lastName = "Administrator",
                    isRoot = true,
                    avatarType = "image",
                    avatarValue = "/avatars/admin.png",
                    avatarColor = "#10B981",
                    tagline = "Keeping things running smoothly",
                    createdAt =
                        com.calypsan.listenup.client.core
                            .Timestamp(1700000000000L),
                    updatedAt =
                        com.calypsan.listenup.client.core
                            .Timestamp(1705000000000L),
                )
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then - verify every field is correctly mapped
            assertNotNull(user)
            assertEquals("admin-user-42", user.id.value)
            assertEquals("admin@listenup.app", user.email)
            assertEquals("Admin Alice", user.displayName)
            assertEquals("Alice", user.firstName)
            assertEquals("Administrator", user.lastName)
            assertTrue(user.isAdmin)
            assertEquals("image", user.avatarType)
            assertEquals("/avatars/admin.png", user.avatarValue)
            assertEquals("#10B981", user.avatarColor)
            assertEquals("Keeping things running smoothly", user.tagline)
            assertEquals(1700000000000L, user.createdAtMs)
            assertEquals(1705000000000L, user.updatedAtMs)
        }

    // ========== Edge Cases ==========

    @Test
    fun `toDomain handles empty strings correctly`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity =
                createTestUserEntity(
                    displayName = "",
                    email = "",
                    avatarColor = "",
                )
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertNotNull(user)
            assertEquals("", user.displayName)
            assertEquals("", user.email)
            assertEquals("", user.avatarColor)
        }

    @Test
    fun `toDomain handles zero timestamps correctly`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val entity =
                createTestUserEntity(
                    createdAt = 0L,
                    updatedAt = 0L,
                )
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertNotNull(user)
            assertEquals(0L, user.createdAtMs)
            assertEquals(0L, user.updatedAtMs)
        }

    @Test
    fun `toDomain handles maximum timestamp values correctly`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val maxTimestamp = Long.MAX_VALUE
            val entity =
                createTestUserEntity(
                    createdAt = maxTimestamp,
                    updatedAt = maxTimestamp,
                )
            everySuspend { userDao.getCurrentUser() } returns entity
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val user = repository.getCurrentUser()

            // Then
            assertNotNull(user)
            assertEquals(maxTimestamp, user.createdAtMs)
            assertEquals(maxTimestamp, user.updatedAtMs)
        }

    @Test
    fun `observeCurrentUser correctly transforms entity stream to domain stream`() =
        runTest {
            // Given - multiple different users emitted
            val userDao = createMockUserDao()
            val user1Entity =
                createTestUserEntity(
                    id = "user-1",
                    isRoot = false,
                    avatarType = "auto",
                )
            val user2Entity =
                createTestUserEntity(
                    id = "user-2",
                    isRoot = true,
                    avatarType = "image",
                    avatarValue = "/path/to/avatar.jpg",
                )
            every { userDao.observeCurrentUser() } returns flowOf(user1Entity, user2Entity)
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When
            val emissions = repository.observeCurrentUser().take(2).toList()

            // Then
            val user1 = emissions[0]
            assertNotNull(user1)
            assertEquals("user-1", user1.id.value)
            assertFalse(user1.isAdmin)
            assertEquals("auto", user1.avatarType)

            val user2 = emissions[1]
            assertNotNull(user2)
            assertEquals("user-2", user2.id.value)
            assertTrue(user2.isAdmin)
            assertEquals("image", user2.avatarType)
            assertEquals("/path/to/avatar.jpg", user2.avatarValue)
        }

    @Test
    fun `observeCurrentUser handles user with MutableStateFlow updates`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val userFlow = MutableStateFlow<UserEntity?>(null)
            every { userDao.observeCurrentUser() } returns userFlow
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When/Then - initial null
            assertNull(repository.observeCurrentUser().first())

            // When/Then - user logs in
            userFlow.value = createTestUserEntity(id = "logged-in-user")
            val loggedIn = repository.observeCurrentUser().first()
            assertNotNull(loggedIn)
            assertEquals("logged-in-user", loggedIn.id.value)

            // When/Then - user updates profile
            userFlow.value = createTestUserEntity(id = "logged-in-user", displayName = "New Name")
            val updated = repository.observeCurrentUser().first()
            assertNotNull(updated)
            assertEquals("New Name", updated.displayName)

            // When/Then - user logs out
            userFlow.value = null
            assertNull(repository.observeCurrentUser().first())
        }

    @Test
    fun `repository correctly implements UserRepository interface`() =
        runTest {
            // Given
            val userDao = createMockUserDao()
            val sessionApi = createMockSessionApi()
            every { userDao.observeCurrentUser() } returns flowOf(null)
            everySuspend { userDao.getCurrentUser() } returns null

            // When
            val repository: com.calypsan.listenup.client.domain.repository.UserRepository =
                UserRepositoryImpl(userDao, sessionApi)

            // Then - all methods are accessible via interface
            assertNull(repository.observeCurrentUser().first())
            assertFalse(repository.observeIsAdmin().first())
            assertNull(repository.getCurrentUser())
        }

    @Test
    fun `observeIsAdmin correctly maps isRoot field from multiple emissions`() =
        runTest {
            // Given - test the specific mapping of isRoot -> isAdmin through flow
            val userDao = createMockUserDao()
            val userFlow = MutableStateFlow<UserEntity?>(null)
            every { userDao.observeCurrentUser() } returns userFlow
            val sessionApi = createMockSessionApi()
            val repository = UserRepositoryImpl(userDao, sessionApi)

            // When/Then - null user
            assertFalse(repository.observeIsAdmin().first())

            // When/Then - user with isRoot = false
            userFlow.value = createTestUserEntity(isRoot = false)
            assertFalse(repository.observeIsAdmin().first())

            // When/Then - user with isRoot = true
            userFlow.value = createTestUserEntity(isRoot = true)
            assertTrue(repository.observeIsAdmin().first())
        }
}
