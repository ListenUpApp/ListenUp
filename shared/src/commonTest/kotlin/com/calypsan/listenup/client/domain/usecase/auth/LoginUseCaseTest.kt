package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession as ContractAuthSession
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
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for LoginUseCase against the contract-typed [AppResult] surface.
 */
class LoginUseCaseTest {
    private class TestFixture {
        val authRepository: AuthRepository = mock()
        val authSession: AuthSession = mock()
        val userRepository: UserRepository = mock()

        fun build(): LoginUseCase =
            LoginUseCase(
                authRepository = authRepository,
                authSession = authSession,
                userRepository = userRepository,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()
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

    // ========== Validation ==========

    @Test
    fun `login rejects email without at symbol`() =
        runTest {
            val fixture = createFixture()
            val useCase = fixture.build()

            val result = useCase(email = "invalid.email", password = "password123")

            val failure = assertIs<AppResult.Failure>(result)
            assertIs<ValidationError>(failure.error)
        }

    @Test
    fun `login rejects empty email`() =
        runTest {
            val fixture = createFixture()
            val useCase = fixture.build()

            val result = useCase(email = "", password = "password123")

            val failure = assertIs<AppResult.Failure>(result)
            val ve = assertIs<ValidationError>(failure.error)
            assertEquals("Please enter a valid email address", ve.message)
        }

    @Test
    fun `login rejects short password before hitting the network`() =
        runTest {
            val fixture = createFixture()
            val useCase = fixture.build()

            val result = useCase(email = "user@example.com", password = "short")

            val failure = assertIs<AppResult.Failure>(result)
            val ve = assertIs<ValidationError>(failure.error)
            assertEquals("Password must be at least 8 characters", ve.message)
        }

    @Test
    fun `login trims whitespace from email`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.authRepository.login(any()) } returns AppResult.Success(createAuthSession())
            val useCase = fixture.build()

            val result = useCase(email = "  user@example.com  ", password = "password123")

            assertIs<AppResult.Success<*>>(result)
            verifySuspend {
                fixture.authRepository.login(LoginRequest(email = "user@example.com", password = "password123"))
            }
        }

    // ========== Successful login flow ==========

    @Test
    fun `login persists tokens and user, returns the User on success`() =
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

            val success = assertIs<AppResult.Success<User>>(result)
            assertEquals("user-42", session.user.id.value)
            assertEquals("test@example.com", success.data.email)

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

    // ========== Failure pass-through ==========

    @Test
    fun `login passes through typed AuthError from the repository`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.authRepository.login(any()) } returns
                AppResult.Failure(AuthError.InvalidCredentials())
            val useCase = fixture.build()

            val result = useCase(email = "user@example.com", password = "password123")

            val failure = assertIs<AppResult.Failure>(result)
            assertIs<AuthError.InvalidCredentials>(failure.error)
        }
}
