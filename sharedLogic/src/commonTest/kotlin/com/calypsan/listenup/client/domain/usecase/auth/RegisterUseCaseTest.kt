package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession as ContractAuthSession
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User as ContractUser
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.UserRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private class RegisterFixture {
    val authRepository: AuthRepository = mock()
    val authSession: AuthSession = mock()
    val userRepository: UserRepository = mock()

    fun build(): RegisterUseCase =
        RegisterUseCase(
            authRepository = authRepository,
            authSession = authSession,
            userRepository = userRepository,
            deviceInfoProvider = { DeviceInfo() },
        )
}

private fun createFixture(): RegisterFixture {
    val fixture = RegisterFixture()
    everySuspend { fixture.authSession.savePendingRegistration(any(), any()) } returns Unit
    everySuspend { fixture.authSession.saveAuthTokens(any(), any(), any(), any()) } returns Unit
    everySuspend { fixture.userRepository.saveUser(any()) } returns Unit
    return fixture
}

private fun pendingResult(userId: String = "user-42"): AppResult<RegisterResult> = AppResult.Success(RegisterResult.PendingApproval(UserId(userId)))

private fun authenticatedResult(
    userId: String = "user-7",
    email: String = "user@example.com",
): AppResult<RegisterResult> =
    AppResult.Success(
        RegisterResult.Authenticated(
            session =
                ContractAuthSession(
                    accessToken = AccessToken("access-123"),
                    accessTokenExpiresAt = 1_000_000L,
                    refreshToken = RefreshToken("refresh-456"),
                    refreshTokenExpiresAt = 2_000_000L,
                    sessionId = SessionId("session-789"),
                    user =
                        ContractUser(
                            id = UserId(userId),
                            email = email,
                            displayName = "John Doe",
                            role = UserRole.MEMBER,
                            status = UserStatus.ACTIVE,
                            createdAt = 1704067200000L,
                        ),
                ),
        ),
    )

/**
 * Tests for [RegisterUseCase] over the contract surface.
 */
class RegisterUseCaseTest :
    FunSpec({

        // ========== Validation ==========

        test("register rejects email without at symbol") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "invalid.email",
                        password = "password123",
                        firstName = "John",
                        lastName = "Doe",
                    )

                val ve =
                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                ve.message shouldBe "Please enter a valid email address"
            }
        }

        test("register rejects password shorter than 8 characters") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "user@example.com",
                        password = "1234567",
                        firstName = "John",
                        lastName = "Doe",
                    )

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<ValidationError>()
            }
        }

        test("register rejects blank first name") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "user@example.com",
                        password = "password123",
                        firstName = "   ",
                        lastName = "Doe",
                    )

                val ve =
                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                ve.message shouldBe "First name is required"
            }
        }

        test("register rejects blank last name") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "user@example.com",
                        password = "password123",
                        firstName = "John",
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

        // ========== Successful registration flow ==========

        test("register sends combined displayName and persists pending registration") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.authRepository.register(any()) } returns pendingResult(userId = "user-42")
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "user@example.com",
                        password = "password123",
                        firstName = "John",
                        lastName = "Doe",
                    )

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                verifySuspend {
                    fixture.authRepository.register(
                        RegisterRequest(
                            email = "user@example.com",
                            password = "password123",
                            displayName = "John Doe",
                            deviceInfo = DeviceInfo(),
                        ),
                    )
                }
                verifySuspend {
                    fixture.authSession.savePendingRegistration(userId = "user-42", email = "user@example.com")
                }
            }
        }

        test("register persists the session and authenticates on an Authenticated (open-registration) result") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.authRepository.register(any()) } returns
                    authenticatedResult(userId = "user-7", email = "user@example.com")
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "user@example.com",
                        password = "password123",
                        firstName = "John",
                        lastName = "Doe",
                    )

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                // The user is saved locally BEFORE auth state flips (mirrors LoginUseCase),
                // so the post-login startup check never races an empty database.
                verifySuspend { fixture.userRepository.saveUser(any()) }
                verifySuspend {
                    fixture.authSession.saveAuthTokens(
                        access = AccessToken("access-123"),
                        refresh = RefreshToken("refresh-456"),
                        sessionId = "session-789",
                        userId = "user-7",
                    )
                }
            }
        }

        test("register passes through typed AuthError on failure") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.authRepository.register(any()) } returns
                    AppResult.Failure(AuthError.EmailAlreadyExists())
                val useCase = fixture.build()

                val result =
                    useCase(
                        email = "user@example.com",
                        password = "password123",
                        firstName = "John",
                        lastName = "Doe",
                    )

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.EmailAlreadyExists>()
            }
        }
    })
