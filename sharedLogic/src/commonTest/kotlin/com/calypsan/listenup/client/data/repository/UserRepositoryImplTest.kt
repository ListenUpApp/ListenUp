package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.dto.auth.User as ContractUser
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.remote.AuthRpcFactory
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

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
class UserRepositoryImplTest :
    FunSpec({
        // ========== Test Data Factories ==========

        // Creates a test UserEntity with all fields populated.
        // Provides sensible defaults while allowing field overrides for specific test scenarios.
        fun createTestUserEntity(
            id: String = "user-001",
            email: String = "test@example.com",
            displayName: String = "Test User",
            firstName: String? = "Test",
            lastName: String? = "User",
            isRoot: Boolean = false,
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
                tagline = tagline,
                createdAt =
                    com.calypsan.listenup.core
                        .Timestamp(createdAt),
                updatedAt =
                    com.calypsan.listenup.core
                        .Timestamp(updatedAt),
            )

        fun createMockUserDao(): UserDao = mock<UserDao>()

        fun createMockAuthRpcFactory(): AuthRpcFactory = mock<AuthRpcFactory>()

        // ========== observeCurrentUser Tests ==========

        test("observeCurrentUser emits User when user exists") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity()
                every { userDao.observeCurrentUser() } returns flowOf(entity)
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.observeCurrentUser().first().shouldNotBeNull()

                // Then
                user.id.value shouldBe "user-001"
                user.email shouldBe "test@example.com"
                user.displayName shouldBe "Test User"
            }
        }

        test("observeCurrentUser emits null when no user exists") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                every { userDao.observeCurrentUser() } returns flowOf(null)
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.observeCurrentUser().first()

                // Then
                user shouldBe null
            }
        }

        test("observeCurrentUser emits changes when user updates") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val initialUser = createTestUserEntity(displayName = "Initial Name")
                val updatedUser = createTestUserEntity(displayName = "Updated Name")
                // Flow emits null -> user1 -> user2 -> null
                every { userDao.observeCurrentUser() } returns flowOf(null, initialUser, updatedUser, null)
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val emissions = repository.observeCurrentUser().take(4).toList()

                // Then
                emissions[0] shouldBe null
                emissions[1]?.displayName shouldBe "Initial Name"
                emissions[2]?.displayName shouldBe "Updated Name"
                emissions[3] shouldBe null
            }
        }

        // ========== observeIsAdmin Tests ==========

        test("observeIsAdmin emits true when user isRoot is true") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(isRoot = true)
                every { userDao.observeCurrentUser() } returns flowOf(entity)
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val isAdmin = repository.observeIsAdmin().first()

                // Then
                isAdmin shouldBe true
            }
        }

        test("observeIsAdmin emits false when user isRoot is false") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(isRoot = false)
                every { userDao.observeCurrentUser() } returns flowOf(entity)
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val isAdmin = repository.observeIsAdmin().first()

                // Then
                isAdmin shouldBe false
            }
        }

        test("observeIsAdmin emits false when user is null") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                every { userDao.observeCurrentUser() } returns flowOf(null)
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val isAdmin = repository.observeIsAdmin().first()

                // Then
                isAdmin shouldBe false
            }
        }

        test("observeIsAdmin emits changes when admin status changes") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val regularUser = createTestUserEntity(isRoot = false)
                val adminUser = createTestUserEntity(isRoot = true)
                // Flow: null -> regular -> admin -> regular
                every { userDao.observeCurrentUser() } returns flowOf(null, regularUser, adminUser, regularUser)
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val emissions = repository.observeIsAdmin().take(4).toList()

                // Then
                emissions[0] shouldBe false // null user -> not admin
                emissions[1] shouldBe false // regular user -> not admin
                emissions[2] shouldBe true // admin user -> admin
                emissions[3] shouldBe false // demoted -> not admin
            }
        }

        test("observeIsAdmin reacts to MutableStateFlow changes") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val userFlow = MutableStateFlow<UserEntity?>(null)
                every { userDao.observeCurrentUser() } returns userFlow
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When/Then - initial state
                repository.observeIsAdmin().first() shouldBe false

                // When/Then - user becomes admin
                userFlow.value = createTestUserEntity(isRoot = true)
                repository.observeIsAdmin().first() shouldBe true

                // When/Then - user demoted
                userFlow.value = createTestUserEntity(isRoot = false)
                repository.observeIsAdmin().first() shouldBe false

                // When/Then - user logs out
                userFlow.value = null
                repository.observeIsAdmin().first() shouldBe false
            }
        }

        // ========== getCurrentUser Tests ==========

        test("getCurrentUser returns User when user exists") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity()
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser().shouldNotBeNull()

                // Then
                user.id.value shouldBe "user-001"
                user.email shouldBe "test@example.com"
            }
        }

        test("getCurrentUser returns null when no user exists") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                everySuspend { userDao.getCurrentUser() } returns null
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                user shouldBe null
            }
        }

        // ========== refreshCurrentUser (RPC) Tests ==========

        test("refreshCurrentUser fetches the user via RPC AuthServiceAuthed and persists it") {
            runTest {
                // Given - the authed RPC proxy returns a ROOT user
                val userDao = createMockUserDao()
                everySuspend { userDao.upsert(any()) } returns Unit
                val authed = mock<AuthServiceAuthed>()
                everySuspend { authed.currentUser() } returns
                    AppResult.Success(
                        ContractUser(
                            id = UserId("root-1"),
                            email = "root@example.com",
                            displayName = "Root Admin",
                            role = UserRole.ROOT,
                            status = UserStatus.ACTIVE,
                            createdAt = 1_700_000_000_000L,
                        ),
                    )
                val authRpcFactory = createMockAuthRpcFactory()
                everySuspend { authRpcFactory.authedService() } returns authed
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.refreshCurrentUser().shouldNotBeNull()

                // Then - ROOT maps to admin and the user is persisted to Room
                user.email shouldBe "root@example.com"
                user.isAdmin shouldBe true
                verifySuspend { userDao.upsert(any()) }
            }
        }

        test("refreshCurrentUser returns null when the RPC call fails") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val authed = mock<AuthServiceAuthed>()
                everySuspend { authed.currentUser() } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error.TransportError
                            .NetworkUnavailable(),
                    )
                val authRpcFactory = createMockAuthRpcFactory()
                everySuspend { authRpcFactory.authedService() } returns authed
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.refreshCurrentUser()

                // Then
                user shouldBe null
            }
        }

        // ========== Entity to Domain Conversion Tests ==========

        test("toDomain converts id correctly") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(id = "unique-user-id-123")
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                user?.id?.value shouldBe "unique-user-id-123"
            }
        }

        test("toDomain converts email correctly") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(email = "user@listenup.app")
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                user?.email shouldBe "user@listenup.app"
            }
        }

        test("toDomain converts displayName correctly") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(displayName = "Bookworm Betty")
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                user?.displayName shouldBe "Bookworm Betty"
            }
        }

        test("toDomain converts firstName correctly when present") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(firstName = "Elizabeth")
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                user?.firstName shouldBe "Elizabeth"
            }
        }

        test("toDomain converts firstName correctly when null") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(firstName = null)
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                user?.firstName shouldBe null
            }
        }

        test("toDomain converts lastName correctly when present") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(lastName = "Bennet")
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                user?.lastName shouldBe "Bennet"
            }
        }

        test("toDomain converts lastName correctly when null") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(lastName = null)
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                user?.lastName shouldBe null
            }
        }

        test("toDomain converts isRoot to isAdmin correctly when true") {
            runTest {
                // Given - entity uses isRoot, domain uses isAdmin
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(isRoot = true)
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                (user?.isAdmin == true) shouldBe true
            }
        }

        test("toDomain converts isRoot to isAdmin correctly when false") {
            runTest {
                // Given - entity uses isRoot, domain uses isAdmin
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(isRoot = false)
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                (user?.isAdmin == true) shouldBe false
            }
        }

        test("toDomain converts tagline correctly when present") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(tagline = "Fantasy is my escape")
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                user?.tagline shouldBe "Fantasy is my escape"
            }
        }

        test("toDomain converts tagline correctly when null") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity = createTestUserEntity(tagline = null)
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                user?.tagline shouldBe null
            }
        }

        test("toDomain converts createdAt to createdAtMs correctly") {
            runTest {
                // Given - entity uses createdAt, domain uses createdAtMs
                val userDao = createMockUserDao()
                val timestamp = 1704067200000L // 2024-01-01 00:00:00 UTC
                val entity = createTestUserEntity(createdAt = timestamp)
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                user?.createdAtMs shouldBe timestamp
            }
        }

        test("toDomain converts updatedAt to updatedAtMs correctly") {
            runTest {
                // Given - entity uses updatedAt, domain uses updatedAtMs
                val userDao = createMockUserDao()
                val timestamp = 1704153600000L // 2024-01-02 00:00:00 UTC
                val entity = createTestUserEntity(updatedAt = timestamp)
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser()

                // Then
                user?.updatedAtMs shouldBe timestamp
            }
        }

        test("toDomain converts all fields correctly in comprehensive test") {
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
                        tagline = "Keeping things running smoothly",
                        createdAt =
                            com.calypsan.listenup.core
                                .Timestamp(1700000000000L),
                        updatedAt =
                            com.calypsan.listenup.core
                                .Timestamp(1705000000000L),
                    )
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser().shouldNotBeNull()

                // Then - verify every field is correctly mapped
                user.id.value shouldBe "admin-user-42"
                user.email shouldBe "admin@listenup.app"
                user.displayName shouldBe "Admin Alice"
                user.firstName shouldBe "Alice"
                user.lastName shouldBe "Administrator"
                user.isAdmin shouldBe true
                user.tagline shouldBe "Keeping things running smoothly"
                user.createdAtMs shouldBe 1700000000000L
                user.updatedAtMs shouldBe 1705000000000L
            }
        }

        // ========== Edge Cases ==========

        test("toDomain handles empty strings correctly") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity =
                    createTestUserEntity(
                        displayName = "",
                        email = "",
                    )
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser().shouldNotBeNull()

                // Then
                user.displayName shouldBe ""
                user.email shouldBe ""
            }
        }

        test("toDomain handles zero timestamps correctly") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val entity =
                    createTestUserEntity(
                        createdAt = 0L,
                        updatedAt = 0L,
                    )
                everySuspend { userDao.getCurrentUser() } returns entity
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser().shouldNotBeNull()

                // Then
                user.createdAtMs shouldBe 0L
                user.updatedAtMs shouldBe 0L
            }
        }

        test("toDomain handles maximum timestamp values correctly") {
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
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val user = repository.getCurrentUser().shouldNotBeNull()

                // Then
                user.createdAtMs shouldBe maxTimestamp
                user.updatedAtMs shouldBe maxTimestamp
            }
        }

        test("observeCurrentUser correctly transforms entity stream to domain stream") {
            runTest {
                // Given - multiple different users emitted
                val userDao = createMockUserDao()
                val user1Entity =
                    createTestUserEntity(
                        id = "user-1",
                        isRoot = false,
                    )
                val user2Entity =
                    createTestUserEntity(
                        id = "user-2",
                        isRoot = true,
                    )
                every { userDao.observeCurrentUser() } returns flowOf(user1Entity, user2Entity)
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When
                val emissions = repository.observeCurrentUser().take(2).toList()

                // Then
                val user1 = emissions[0].shouldNotBeNull()
                user1.id.value shouldBe "user-1"
                user1.isAdmin shouldBe false

                val user2 = emissions[1].shouldNotBeNull()
                user2.id.value shouldBe "user-2"
                user2.isAdmin shouldBe true
            }
        }

        test("observeCurrentUser handles user with MutableStateFlow updates") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val userFlow = MutableStateFlow<UserEntity?>(null)
                every { userDao.observeCurrentUser() } returns userFlow
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When/Then - initial null
                repository.observeCurrentUser().first() shouldBe null

                // When/Then - user logs in
                userFlow.value = createTestUserEntity(id = "logged-in-user")
                val loggedIn = repository.observeCurrentUser().first().shouldNotBeNull()
                loggedIn.id.value shouldBe "logged-in-user"

                // When/Then - user updates profile
                userFlow.value = createTestUserEntity(id = "logged-in-user", displayName = "New Name")
                val updated = repository.observeCurrentUser().first().shouldNotBeNull()
                updated.displayName shouldBe "New Name"

                // When/Then - user logs out
                userFlow.value = null
                repository.observeCurrentUser().first() shouldBe null
            }
        }

        test("repository correctly implements UserRepository interface") {
            runTest {
                // Given
                val userDao = createMockUserDao()
                val authRpcFactory = createMockAuthRpcFactory()
                every { userDao.observeCurrentUser() } returns flowOf(null)
                everySuspend { userDao.getCurrentUser() } returns null

                // When
                val repository: com.calypsan.listenup.client.domain.repository.UserRepository =
                    UserRepositoryImpl(userDao, authRpcFactory)

                // Then - all methods are accessible via interface
                repository.observeCurrentUser().first() shouldBe null
                repository.observeIsAdmin().first() shouldBe false
                repository.getCurrentUser() shouldBe null
            }
        }

        test("observeIsAdmin correctly maps isRoot field from multiple emissions") {
            runTest {
                // Given - test the specific mapping of isRoot -> isAdmin through flow
                val userDao = createMockUserDao()
                val userFlow = MutableStateFlow<UserEntity?>(null)
                every { userDao.observeCurrentUser() } returns userFlow
                val authRpcFactory = createMockAuthRpcFactory()
                val repository = UserRepositoryImpl(userDao, authRpcFactory)

                // When/Then - null user
                repository.observeIsAdmin().first() shouldBe false

                // When/Then - user with isRoot = false
                userFlow.value = createTestUserEntity(isRoot = false)
                repository.observeIsAdmin().first() shouldBe false

                // When/Then - user with isRoot = true
                userFlow.value = createTestUserEntity(isRoot = true)
                repository.observeIsAdmin().first() shouldBe true
            }
        }
    })
