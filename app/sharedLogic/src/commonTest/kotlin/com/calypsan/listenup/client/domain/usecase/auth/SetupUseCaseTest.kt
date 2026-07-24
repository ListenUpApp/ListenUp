package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession as ContractAuthSession
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User as ContractUser
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.UserRepository
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private class SetupFixture {
    val authRepository: AuthRepository = mock()
    val authSession: AuthSession = mock()
    val userRepository: UserRepository = mock()

    fun build(): SetupUseCase =
        SetupUseCase(
            authRepository = authRepository,
            authSession = authSession,
            userRepository = userRepository,
            deviceInfoProvider = { DeviceInfo() },
        )
}

private fun createFixture(): SetupFixture {
    val fixture = SetupFixture()
    everySuspend { fixture.authSession.saveAuthTokens(any(), any(), any(), any()) } returns Unit
    everySuspend { fixture.userRepository.saveUser(any()) } returns Unit
    return fixture
}

private fun createAuthSession(
    accessToken: String = "access-token-123",
    refreshToken: String = "refresh-token-456",
    sessionId: String = "session-789",
    userId: String = "user-1",
    email: String = "root@example.com",
): ContractAuthSession =
    ContractAuthSession(
        accessToken = AccessToken(accessToken),
        accessTokenExpiresAt = 1_000_000L,
        refreshToken = RefreshToken(refreshToken),
        refreshTokenExpiresAt = 2_000_000L,
        sessionId = SessionId(sessionId),
        user =
            ContractUser(
                id = UserId(userId),
                email = email,
                displayName = "Root Admin",
                role = UserRole.ADMIN,
                status = UserStatus.ACTIVE,
                createdAt = 1704067200000L,
            ),
    )

/**
 * Tests for [SetupUseCase] — bootstraps the root user on a fresh server.
 */
class SetupUseCaseTest :
    FunSpec({

        // ========== Validation ==========

        test("setup rejects blank first name") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "root@example.com",
                        password = "password123",
                        firstName = "   ",
                        lastName = "Admin",
                    )

                val ve =
                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                ve.message shouldBe "First name is required"
            }
        }

        test("setup rejects blank last name") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "root@example.com",
                        password = "password123",
                        firstName = "Root",
                        lastName = "",
                    )

                val ve =
                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                ve.message shouldBe "Last name is required"
            }
        }

        test("setup rejects email without at symbol") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "invalid.email",
                        password = "password123",
                        firstName = "Root",
                        lastName = "Admin",
                    )

                val ve =
                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                ve.message shouldBe "Please enter a valid email address"
            }
        }

        test("setup rejects short password") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "root@example.com",
                        password = "short",
                        firstName = "Root",
                        lastName = "Admin",
                    )

                val ve =
                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                ve.message shouldBe "Password must be at least 8 characters"
            }
        }

        test("setup trims whitespace from email and names") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.authRepository.setup(any()) } returns AppResult.Success(createAuthSession())
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "  root@example.com  ",
                        password = "password123",
                        firstName = "  Root  ",
                        lastName = "  Admin  ",
                    )

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                verifySuspend {
                    fixture.authRepository.setup(
                        RegisterRequest(
                            email = "root@example.com",
                            password = "password123",
                            displayName = "Root Admin",
                            deviceInfo = DeviceInfo(),
                        ),
                    )
                }
            }
        }

        // ========== Successful setup flow ==========

        test("setup persists tokens and user, returns the User on success") {
            runTest {
                val session =
                    createAuthSession(
                        accessToken = "root-access-token",
                        refreshToken = "root-refresh-token",
                        sessionId = "root-session",
                        userId = "user-root",
                        email = "root@example.com",
                    )
                val fixture = createFixture()
                everySuspend { fixture.authRepository.setup(any()) } returns AppResult.Success(session)
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "root@example.com",
                        password = "password123",
                        firstName = "Root",
                        lastName = "Admin",
                    )

                val success = result.shouldBeInstanceOf<AppResult.Success<User>>()
                success.data.email shouldBe "root@example.com"

                verifySuspend {
                    fixture.authSession.saveAuthTokens(
                        access = AccessToken("root-access-token"),
                        refresh = RefreshToken("root-refresh-token"),
                        sessionId = "root-session",
                        userId = "user-root",
                    )
                }
                verifySuspend { fixture.userRepository.saveUser(any()) }
            }
        }

        // The post-login startup check (AppStartupViewModel) runs the instant
        // authState flips to Authenticated. saveAuthTokens is what flips it, so if
        // it runs before saveUser the check races an empty Room, reads a null user,
        // and silently drops the freshly-created admin into the Shell instead of the
        // Create-Library wizard. Record both calls into one sequence so the test
        // fails if the order is ever reversed.
        test("setup persists the user locally before flipping auth state to Authenticated") {
            runTest {
                val events = mutableListOf<String>()
                val fixture = SetupFixture()
                everySuspend { fixture.userRepository.saveUser(any()) } calls { events.add("saveUser") }
                everySuspend {
                    fixture.authSession.saveAuthTokens(any(), any(), any(), any())
                } calls { events.add("saveAuthTokens") }
                everySuspend { fixture.authRepository.setup(any()) } returns AppResult.Success(createAuthSession())
                val useCase = fixture.build()

                useCase(
                    email = "root@example.com",
                    password = "password123",
                    firstName = "Root",
                    lastName = "Admin",
                )

                events shouldContainInOrder listOf("saveUser", "saveAuthTokens")
            }
        }

        // ========== Failure pass-through ==========

        test("setup passes through typed AuthError from the repository") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.authRepository.setup(any()) } returns
                    AppResult.Failure(AuthError.SetupAlreadyComplete())
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "root@example.com",
                        password = "password123",
                        firstName = "Root",
                        lastName = "Admin",
                    )

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.SetupAlreadyComplete>()
            }
        }
    })
