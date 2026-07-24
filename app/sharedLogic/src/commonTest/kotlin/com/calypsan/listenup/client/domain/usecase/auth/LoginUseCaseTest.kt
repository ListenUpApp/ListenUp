package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession as ContractAuthSession
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshToken
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
import kotlinx.datetime.TimeZone

private class LoginFixture {
    val authRepository: AuthRepository = mock()
    val authSession: AuthSession = mock()
    val userRepository: UserRepository = mock()
    var deviceInfo: DeviceInfo = DeviceInfo()

    fun build(): LoginUseCase =
        LoginUseCase(
            authRepository = authRepository,
            authSession = authSession,
            userRepository = userRepository,
            deviceInfoProvider = { deviceInfo },
        )
}

private fun createFixture(): LoginFixture {
    val fixture = LoginFixture()
    everySuspend { fixture.authSession.saveAuthTokens(any(), any(), any(), any()) } returns Unit
    everySuspend { fixture.userRepository.saveUser(any()) } returns Unit
    return fixture
}

private fun createAuthSession(
    accessToken: String = "access-token-123",
    refreshToken: String = "refresh-token-456",
    sessionId: String = "session-789",
    userId: String = "user-1",
    email: String = "test@example.com",
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
                displayName = "Test User",
                role = UserRole.MEMBER,
                status = UserStatus.ACTIVE,
                createdAt = 1704067200000L,
            ),
    )

/**
 * Tests for LoginUseCase against the contract-typed [AppResult] surface.
 */
class LoginUseCaseTest :
    FunSpec({

        // ========== Validation ==========

        test("login rejects email without at symbol") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result = useCase(email = "invalid.email", password = "password123")

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<ValidationError>()
            }
        }

        test("login rejects empty email") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result = useCase(email = "", password = "password123")

                val ve =
                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                ve.message shouldBe "Please enter a valid email address"
            }
        }

        test("login rejects short password before hitting the network") {
            runTest {
                val fixture = createFixture()
                val useCase = fixture.build()

                val result = useCase(email = "user@example.com", password = "short")

                val ve =
                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                ve.message shouldBe "Password must be at least 8 characters"
            }
        }

        test("login trims whitespace from email") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.authRepository.login(any()) } returns AppResult.Success(createAuthSession())
                val useCase = fixture.build()

                val result = useCase(email = "  user@example.com  ", password = "password123")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                verifySuspend {
                    fixture.authRepository.login(
                        LoginRequest(
                            email = "user@example.com",
                            password = "password123",
                            deviceInfo = DeviceInfo(),
                            timezone = TimeZone.currentSystemDefault().id,
                        ),
                    )
                }
            }
        }

        test("login sends DeviceInfo from the provider") {
            runTest {
                val fixture = createFixture()
                fixture.deviceInfo = DeviceInfo(deviceModel = "Pixel 10")
                everySuspend { fixture.authRepository.login(any()) } returns AppResult.Success(createAuthSession())
                val useCase = fixture.build()

                useCase(email = "u@x.co", password = "password1")

                verifySuspend {
                    fixture.authRepository.login(
                        LoginRequest(
                            email = "u@x.co",
                            password = "password1",
                            deviceInfo = DeviceInfo(deviceModel = "Pixel 10"),
                            timezone = TimeZone.currentSystemDefault().id,
                        ),
                    )
                }
            }
        }

        // ========== Successful login flow ==========

        test("login persists tokens and user, returns the User on success") {
            runTest {
                val session =
                    createAuthSession(
                        accessToken = "my-access-token",
                        refreshToken = "my-refresh-token",
                        sessionId = "my-session",
                        userId = "user-42",
                        email = "test@example.com",
                    )
                val fixture = createFixture()
                everySuspend { fixture.authRepository.login(any()) } returns AppResult.Success(session)
                val useCase = fixture.build()

                val result = useCase(email = "user@example.com", password = "password123")

                val success = result.shouldBeInstanceOf<AppResult.Success<User>>()
                session.user.id.value shouldBe "user-42"
                success.data.email shouldBe "test@example.com"

                verifySuspend {
                    fixture.authSession.saveAuthTokens(
                        access = AccessToken("my-access-token"),
                        refresh = RefreshToken("my-refresh-token"),
                        sessionId = "my-session",
                        userId = "user-42",
                    )
                }
                verifySuspend { fixture.userRepository.saveUser(any()) }
            }
        }

        // The post-login startup check (AppStartupViewModel) runs the instant
        // authState flips to Authenticated. saveAuthTokens is what flips it, so if
        // it runs before saveUser the check races an empty Room and reads a null
        // user. Record both calls into one sequence so the test fails if the order
        // is ever reversed.
        test("login persists the user locally before flipping auth state to Authenticated") {
            runTest {
                val events = mutableListOf<String>()
                val fixture = LoginFixture()
                everySuspend { fixture.userRepository.saveUser(any()) } calls { events.add("saveUser") }
                everySuspend {
                    fixture.authSession.saveAuthTokens(any(), any(), any(), any())
                } calls { events.add("saveAuthTokens") }
                everySuspend { fixture.authRepository.login(any()) } returns AppResult.Success(createAuthSession())
                val useCase = fixture.build()

                useCase(email = "user@example.com", password = "password123")

                events shouldContainInOrder listOf("saveUser", "saveAuthTokens")
            }
        }

        // ========== Failure pass-through ==========

        test("login passes through typed AuthError from the repository") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.authRepository.login(any()) } returns
                    AppResult.Failure(AuthError.InvalidCredentials())
                val useCase = fixture.build()

                val result = useCase(email = "user@example.com", password = "password123")

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.InvalidCredentials>()
            }
        }
    })
